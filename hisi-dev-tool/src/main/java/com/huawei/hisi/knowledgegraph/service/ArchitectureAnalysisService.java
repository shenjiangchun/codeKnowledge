package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.aggregation.stage.DomainNameGenerator;
import com.huawei.hisi.knowledgegraph.aggregation.stage.FreeLayerRoleResolver;
import com.huawei.hisi.knowledgegraph.aggregation.stage.MultiDimensionCommunityDetector;
import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 架构现状分析异步执行服务（独立串行队列）。
 *
 * <p>把原本同步的「领域归纳 → 领域交互边 → 游离节点层级补全」三步搬到后台单消费线程串行执行，
 * 接口立即返回 taskId，前端轮询 {@code generation_task} 表状态。串行保证多项目不会并发打
 * deepseek 触发限流。
 *
 * <p>不复用 {@link KgGenerationQueue}：那是 KG 生成专用（30 分钟级任务），架构分析是独立触发，
 * 混入会与 KG 任务抢队列、语义混乱。
 */
@Slf4j
@Service
public class ArchitectureAnalysisService {

    private static final String TASK_TYPE = "ARCH_ANALYSIS";

    private final GenerationTaskRepository taskRepository;
    private final MultiDimensionCommunityDetector multiDimensionCommunityDetector;
    private final DomainNameGenerator domainNameGenerator;
    private final FreeLayerRoleResolver freeLayerRoleResolver;

    private final LinkedBlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>();
    private Thread consumerThread;
    private volatile boolean running = true;
    /** 当前正在执行的项目（消费线程写入，读取线程读取） */
    private final AtomicReference<String> currentProcessing = new AtomicReference<>(null);

    public ArchitectureAnalysisService(GenerationTaskRepository taskRepository,
                                       MultiDimensionCommunityDetector multiDimensionCommunityDetector,
                                       DomainNameGenerator domainNameGenerator,
                                       FreeLayerRoleResolver freeLayerRoleResolver) {
        this.taskRepository = taskRepository;
        this.multiDimensionCommunityDetector = multiDimensionCommunityDetector;
        this.domainNameGenerator = domainNameGenerator;
        this.freeLayerRoleResolver = freeLayerRoleResolver;
    }

    @PostConstruct
    public void init() {
        recoverOrphanTasks();
        consumerThread = new Thread(this::consumeLoop, "arch-analysis-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("[ArchAnalysis] Consumer thread started");
    }

    /**
     * 启动恢复：队列是内存态，重启后遗留的 PENDING/RUNNING 任务无人认领（消费线程已丢），
     * 不处理会导致前端永远轮询不到终态。标 FAILED 让用户可重新触发。
     * 不自动重跑：任务参数未持久化，且分析是分钟级 LLM 重任务，诚实失败优于盲目重放。
     */
    private void recoverOrphanTasks() {
        try {
            List<GenerationTask> orphans = taskRepository.findRunningOrPending(TASK_TYPE);
            for (GenerationTask orphan : orphans) {
                taskRepository.updateFailed(orphan.getId(), "应用重启，任务中断，请重新触发");
                log.warn("[ArchAnalysis] 启动恢复：孤儿任务标 FAILED: id={}, projectPath={}, status={}",
                        orphan.getId(), orphan.getProjectPath(), orphan.getStatus());
            }
            if (!orphans.isEmpty()) {
                log.warn("[ArchAnalysis] 启动恢复完成：共 {} 个孤儿任务", orphans.size());
            }
        } catch (Exception e) {
            log.error("[ArchAnalysis] 启动恢复失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        log.info("[ArchAnalysis] Consumer thread stopped");
    }

    /**
     * 提交一个项目的架构分析：建 PENDING 任务并入队，立即返回 taskId。
     *
     * <p>与 {@link KgGenerationQueue#enqueue} 一致的三段防重：正在执行 / 已在队列 / 覆盖陈旧任务。
     *
     * @throws IllegalStateException 同项目正在执行或排队中
     */
    public Long submit(String projectPath) {
        if (projectPath.equals(currentProcessing.get())) {
            throw new IllegalStateException("该项目架构分析正在执行中: " + projectPath);
        }
        if (isInQueue(projectPath)) {
            throw new IllegalStateException("该项目架构分析已在队列中等待: " + projectPath);
        }

        // 覆盖同项目陈旧 PENDING/RUNNING 任务（如队列线程异常期间的遗留）
        taskRepository.findLatestByProjectPathAndType(projectPath, TASK_TYPE).ifPresent(existing -> {
            String status = existing.getStatus();
            if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                log.warn("[ArchAnalysis] Overriding stale task: id={}, status={}", existing.getId(), status);
                taskRepository.updateFailed(existing.getId(), "被新任务覆盖");
            }
        });

        GenerationTask task = taskRepository.insert(GenerationTask.builder()
                .taskType(TASK_TYPE)
                .projectPath(projectPath)
                .status("PENDING")
                .build());
        queue.offer(new QueueItem(task.getId(), projectPath));
        log.info("[ArchAnalysis] Enqueued: projectPath={}, taskId={}, queueSize={}",
                projectPath, task.getId(), queue.size());
        return task.getId();
    }

    /** 队列项：taskId + projectPath，避免 isInQueue 检查时逐个回查 DB。 */
    private record QueueItem(Long taskId, String projectPath) {}

    private boolean isInQueue(String projectPath) {
        for (QueueItem item : queue) {
            if (item.projectPath().equals(projectPath)) {
                return true;
            }
        }
        return false;
    }

    private void consumeLoop() {
        while (running) {
            QueueItem item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[ArchAnalysis] Consumer interrupted, exiting");
                break;
            }
            currentProcessing.set(item.projectPath());
            try {
                // 必须兜底：executeAnalysis 内任何未捕获异常（如 DB 故障）若逃逸，
                // 会杀死消费线程导致队列永久阻塞，后续任务永远 PENDING。
                try {
                    executeAnalysis(item.taskId());
                } catch (Exception e) {
                    log.error("[ArchAnalysis] 执行异常（兜底）: taskId={}", item.taskId(), e);
                    try {
                        taskRepository.updateFailed(item.taskId(), "执行异常: " + e.getMessage());
                    } catch (Exception ex) {
                        log.error("[ArchAnalysis] 标记任务失败时出错: taskId={}", item.taskId(), ex);
                    }
                }
            } finally {
                currentProcessing.set(null);
            }
        }
    }

    private void executeAnalysis(Long taskId) {
        taskRepository.updateStarted(taskId);
        GenerationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getProjectPath() == null) {
            log.warn("[ArchAnalysis] Task not found: taskId={}", taskId);
            // 此时任务已被 updateStarted 置 RUNNING，必须落一个终态，否则前端永远轮询不到结束
            taskRepository.updateFailed(taskId, "任务不存在或数据损坏");
            return;
        }
        String projectPath = task.getProjectPath();

        try {
            multiDimensionCommunityDetector.detect(projectPath);
            domainNameGenerator.generate(projectPath, false);
        } catch (Exception e) {
            log.warn("[ArchAnalysis] 失败: {} - {}", projectPath, e.getMessage());
            taskRepository.updateFailed(taskId, e.getMessage());
            return;
        }

        // 游离节点层级补全：非致命，失败不影响整体状态（与原同步逻辑一致）
        try {
            freeLayerRoleResolver.resolve(projectPath);
        } catch (Exception e) {
            log.warn("[ArchAnalysis] LLM 层级补全失败: {} - {}", projectPath, e.getMessage());
        }

        taskRepository.updateCompleted(taskId, 100, 1, 1, 0);
        log.info("[ArchAnalysis] Completed: projectPath={}, taskId={}", projectPath, taskId);
    }
}
