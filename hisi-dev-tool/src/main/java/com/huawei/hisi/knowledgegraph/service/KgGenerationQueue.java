package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils;
import com.huawei.hisi.model.KnowledgeGraphTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识图谱生成队列服务。
 *
 * <p>保证多项目严格顺序执行：每个项目完整走完「图谱生成 → 描述生成 → 向量生成」后，
 * 才开始下一个项目。前端触发后立即返回 PENDING 状态，可轮询任务状态。
 *
 * <p>内部使用单消费线程 + LinkedBlockingQueue 实现：
 * - 入队：创建 SQLite PENDING 任务，加入队列，立即返回
 * - 消费：循环 take()，执行 KG 构建（内含自动触发向量生成），轮询等待向量任务完成，再取下一个
 */
@Service
@Slf4j
public class KgGenerationQueue {

    private static final String KG_TASK_TYPE = "KG";
    private static final String VECTOR_TASK_TYPE = "VECTOR";
    /** 向量任务轮询间隔（毫秒） */
    private static final long VECTOR_POLL_INTERVAL_MS = 3000;
    /** 向量任务最大等待时间（秒）——防止无限等待 */
    private static final long VECTOR_MAX_WAIT_SECONDS = 3600;

    private final KnowledgeGraphBuilder knowledgeGraphBuilder;
    private final IncrementalKnowledgeGraphBuilder incrementalBuilder;
    private final GenerationTaskRepository taskRepository;

    private final LinkedBlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>();
    private Thread consumerThread;
    private volatile boolean running = true;
    /** 当前正在执行的项目（消费线程写入，读取线程读取） */
    private final AtomicReference<String> currentProcessing = new AtomicReference<>(null);

    public KgGenerationQueue(KnowledgeGraphBuilder knowledgeGraphBuilder,
                             IncrementalKnowledgeGraphBuilder incrementalBuilder,
                             GenerationTaskRepository taskRepository) {
        this.knowledgeGraphBuilder = knowledgeGraphBuilder;
        this.incrementalBuilder = incrementalBuilder;
        this.taskRepository = taskRepository;
    }

    // ==================== 内部队列项 ====================

    record QueueItem(String projectPath, List<String> excludePaths, Long taskId,
                     boolean incremental) {}

    // ==================== 生命周期 ====================

    @PostConstruct
    public void init() {
        consumerThread = new Thread(this::consumeLoop, "kg-queue-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("[KG Queue] Consumer thread started");
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        log.info("[KG Queue] Consumer thread stopped");
    }

    // ==================== 入队 ====================

    /**
     * 单项目入队。
     *
     * @return 创建的 KG 任务（PENDING 状态）
     */
    public KnowledgeGraphTask enqueue(String rawProjectPath, List<String> excludePaths) {
        String projectPath = KnowledgeGraphCommonUtils.normalizePath(rawProjectPath);

        // 前置校验
        validateProjectPath(projectPath);

        // 检查是否已在队列中或正在执行
        if (projectPath.equals(currentProcessing.get())) {
            throw new IllegalStateException("该项目正在生成中: " + projectPath);
        }
        if (isInQueue(projectPath)) {
            throw new IllegalStateException("该项目已在队列中等待: " + projectPath);
        }

        // 覆盖同项目已有的 PENDING/RUNNING 任务
        taskRepository.findLatestByProjectPathAndType(projectPath, KG_TASK_TYPE).ifPresent(existing -> {
            String status = existing.getStatus();
            if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                log.warn("[KG Queue] Overriding stale task: id={}, status={}", existing.getId(), status);
                taskRepository.updateFailed(existing.getId(), "被新任务覆盖");
            }
        });

        // 创建 PENDING 任务
        GenerationTask genTask = GenerationTask.builder()
                .taskType(KG_TASK_TYPE)
                .projectPath(projectPath)
                .status("PENDING")
                .build();
        genTask = taskRepository.insert(genTask);

        queue.offer(new QueueItem(projectPath, excludePaths, genTask.getId(), false));
        log.info("[KG Queue] Enqueued: projectPath={}, taskId={}, queueSize={}",
                projectPath, genTask.getId(), queue.size());

        return toKnowledgeGraphTask(genTask);
    }

    /**
     * 增量刷新入队。与全量构建共享同一队列，严格串行。
     *
     * @return 创建的 KG 任务（PENDING 状态）
     */
    public KnowledgeGraphTask enqueueIncremental(String rawProjectPath) {
        String projectPath = KnowledgeGraphCommonUtils.normalizePath(rawProjectPath);
        validateProjectPath(projectPath);

        if (projectPath.equals(currentProcessing.get())) {
            throw new IllegalStateException("该项目正在生成中: " + projectPath);
        }
        if (isInQueue(projectPath)) {
            throw new IllegalStateException("该项目已在队列中等待: " + projectPath);
        }

        GenerationTask genTask = GenerationTask.builder()
                .taskType(KG_TASK_TYPE)
                .projectPath(projectPath)
                .status("PENDING")
                .build();
        genTask = taskRepository.insert(genTask);

        queue.offer(new QueueItem(projectPath, null, genTask.getId(), true));
        log.info("[KG Queue] Enqueued incremental: projectPath={}, taskId={}, queueSize={}",
                projectPath, genTask.getId(), queue.size());

        return toKnowledgeGraphTask(genTask);
    }

    /**
     * 批量入队。每个项目独立校验，失败的项目跳过（不阻塞其他项目）。
     *
     * @return 成功入队的任务列表
     */
    public List<KnowledgeGraphTask> enqueueBatch(List<String> rawProjectPaths) {
        return enqueueBatch(rawProjectPaths, null);
    }

    public List<KnowledgeGraphTask> enqueueBatch(List<String> rawProjectPaths, List<String> excludePaths) {
        List<KnowledgeGraphTask> tasks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String rawPath : rawProjectPaths) {
            try {
                tasks.add(enqueue(rawPath, excludePaths));
            } catch (Exception e) {
                log.warn("[KG Queue] Skip enqueue: projectPath={}, reason={}", rawPath, e.getMessage());
                skipped.add(rawPath + ": " + e.getMessage());
            }
        }

        if (!skipped.isEmpty()) {
            log.warn("[KG Queue] Batch enqueue skipped: {}", skipped);
        }
        return tasks;
    }

    // ==================== 状态查询 ====================

    /**
     * 获取队列状态：当前执行中项目 + 排队中项目列表 + 队列长度。
     */
    public Map<String, Object> getQueueStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("currentProject", currentProcessing.get());
        status.put("queueSize", queue.size());

        List<Map<String, String>> waitingList = new ArrayList<>();
        for (QueueItem item : queue) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("projectPath", item.projectPath());
            entry.put("taskId", String.valueOf(item.taskId()));
            waitingList.add(entry);
        }
        status.put("queue", waitingList);
        return status;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public Optional<String> getCurrentProcessing() {
        return Optional.ofNullable(currentProcessing.get());
    }

    // ==================== 消费循环 ====================

    private void consumeLoop() {
        while (running) {
            try {
                QueueItem item = queue.take();
                currentProcessing.set(item.projectPath());
                log.info("[KG Queue] Start processing: projectPath={}, queueRemaining={}",
                        item.projectPath(), queue.size());

                try {
                    processItem(item);
                } catch (Exception e) {
                    log.error("[KG Queue] Failed: projectPath={}, error={}",
                            item.projectPath(), e.getMessage(), e);
                    taskRepository.updateFailed(item.taskId(), e.getMessage());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[KG Queue] Consumer interrupted, exiting");
                break;
            } finally {
                currentProcessing.set(null);
            }
        }
    }

    /**
     * 处理单个项目：根据 incremental 标记分发到全量或增量构建。
     */
    private void processItem(QueueItem item) {
        long startTime = System.currentTimeMillis();
        taskRepository.updateStarted(item.taskId());

        if (item.incremental()) {
            // 增量刷新
            IncrementalKnowledgeGraphBuilder.RefreshResult result =
                    incrementalBuilder.incrementalRefresh(item.projectPath());
            if (result.success()) {
                taskRepository.updateCompleted(item.taskId(), 0, 0, 0, 0);
            } else {
                taskRepository.updateFailed(item.taskId(), "Incremental refresh returned failure");
                return;
            }
        } else {
            // 全量构建（内部自动触发异步向量生成）
            knowledgeGraphBuilder.buildKnowledgeGraph(item.projectPath(), item.excludePaths());
            taskRepository.updateCompleted(item.taskId(), 0, 0, 0, 0);
        }

        long kgTime = System.currentTimeMillis() - startTime;
        log.info("[KG Queue] {} completed: projectPath={}, costMs={}",
                item.incremental() ? "Incremental" : "KG", item.projectPath(), kgTime);

        // 等待向量生成完成
        waitForVectorCompletion(item.projectPath());

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("[KG Queue] Full pipeline completed: projectPath={}, totalCostMs={}",
                item.projectPath(), totalTime);
    }

    /**
     * 轮询等待向量任务完成（COMPLETED 或 FAILED）。
     */
    private void waitForVectorCompletion(String projectPath) {
        log.info("[KG Queue] Waiting for vector generation: projectPath={}", projectPath);
        long waitStart = System.currentTimeMillis();

        // 等待向量任务出现（KG 完成后异步触发，可能有短暂延迟）
        int maxAppearAttempts = 30;
        for (int i = 0; i < maxAppearAttempts; i++) {
            Optional<GenerationTask> vectorTask =
                    taskRepository.findLatestByProjectPathAndType(projectPath, VECTOR_TASK_TYPE);
            if (vectorTask.isPresent() && !"COMPLETED".equals(vectorTask.get().getStatus())
                    && !"FAILED".equals(vectorTask.get().getStatus())) {
                break;
            }
            if (vectorTask.isEmpty() && i < maxAppearAttempts - 1) {
                sleepQuietly(1000);
            }
        }

        // 轮询等待完成
        while (true) {
            Optional<GenerationTask> vectorTask =
                    taskRepository.findLatestByProjectPathAndType(projectPath, VECTOR_TASK_TYPE);

            if (vectorTask.isEmpty()) {
                // 没有向量任务——可能项目没有方法节点，直接返回
                log.info("[KG Queue] No vector task found, skipping wait: projectPath={}", projectPath);
                return;
            }

            String status = vectorTask.get().getStatus();
            if ("COMPLETED".equals(status)) {
                long waitMs = System.currentTimeMillis() - waitStart;
                log.info("[KG Queue] Vector generation completed: projectPath={}, waitMs={}",
                        projectPath, waitMs);
                return;
            }
            if ("FAILED".equals(status)) {
                log.warn("[KG Queue] Vector generation failed: projectPath={}, error={}",
                        projectPath, vectorTask.get().getErrorMessage());
                return;
            }

            // 超时检查
            long elapsed = (System.currentTimeMillis() - waitStart) / 1000;
            if (elapsed > VECTOR_MAX_WAIT_SECONDS) {
                log.warn("[KG Queue] Vector generation timeout ({}s): projectPath={}",
                        VECTOR_MAX_WAIT_SECONDS, projectPath);
                return;
            }

            sleepQuietly(VECTOR_POLL_INTERVAL_MS);
        }
    }

    // ==================== 辅助方法 ====================

    private boolean isInQueue(String projectPath) {
        for (QueueItem item : queue) {
            if (item.projectPath().equals(projectPath)) {
                return true;
            }
        }
        return false;
    }

    private void validateProjectPath(String projectPath) {
        File dir = new File(projectPath);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("项目路径不存在: " + projectPath);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private KnowledgeGraphTask toKnowledgeGraphTask(GenerationTask gt) {
        KnowledgeGraphTask task = new KnowledgeGraphTask();
        task.setId(gt.getId());
        task.setProjectPath(gt.getProjectPath());
        task.setProjectName(gt.getProjectPath() != null ? new File(gt.getProjectPath()).getName() : null);
        task.setStatus(gt.getStatus());
        task.setErrorMessage(gt.getErrorMessage());
        task.setMethodNodeCount(gt.getTotalCount());
        task.setRecordsProcessed(gt.getProgress());
        if (gt.getStartedAt() != null) {
            task.setStartTime(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(gt.getStartedAt()), ZoneId.systemDefault()));
        }
        if (gt.getFinishedAt() != null) {
            task.setEndTime(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(gt.getFinishedAt()), ZoneId.systemDefault()));
        }
        if (gt.getStartedAt() != null && gt.getFinishedAt() != null) {
            task.setCostTimeMs((gt.getFinishedAt() - gt.getStartedAt()) * 1000);
        }
        if (gt.getCreatedAt() != null) {
            task.setCreatedAt(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(gt.getCreatedAt()), ZoneId.systemDefault()));
        }
        return task;
    }
}
