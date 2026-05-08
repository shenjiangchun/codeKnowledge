package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.model.VectorGenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 向量生成协调服务
 *
 * 使用统一线程池（固定10线程 + 无界队列）管理所有 API 调用：
 * - LLM 描述生成（智谱AI chat）
 * - 向量生成（SiliconFlow / 智谱AI embedding）
 *
 * 每个线程串行完成 描述生成 → embedding生成 → 落表，原子提交。
 * 天然限制并发为 10，多余任务在队列中排队等待。
 */
@Service
@Slf4j
public class VectorGenerationService {

    private static final String TASK_TYPE = "VECTOR";
    private static final int MAX_METHOD_BODY_LENGTH = 2000;
    private static final String LOG_DIR = "logs/local-model";
    private static final String LOG_FILE = "vector-generation.log";

    private final Neo4jMethodNodeRepository neo4jMethodNodeRepository;
    private final Neo4jSqlNodeRepository neo4jSqlNodeRepository;
    private final GenerationTaskRepository taskRepository;
    private final LLMDescriptionService llmDescriptionService;
    private final EmbeddingService embeddingService;

    /**
     * 固定线程数（同时最多 N 个 API 请求在飞）
     */
    @Value("${vector.generation.concurrency:2}")
    private int concurrency;

    @Value("${vector.generation.progress-update-interval:10}")
    private int progressUpdateInterval;

    /**
     * 统一线程池：固定线程数 + 无界队列（LinkedBlockingQueue）
     * 所有待处理方法直接 submit，多余的在队列中排队
     */
    private ThreadPoolExecutor executorService;
    private final ConcurrentHashMap<String, AtomicInteger> progressTracker = new ConcurrentHashMap<>();

    @Lazy
    @Autowired
    private VectorGenerationService self;

    @Autowired
    public VectorGenerationService(Neo4jMethodNodeRepository neo4jMethodNodeRepository,
                                    Neo4jSqlNodeRepository neo4jSqlNodeRepository,
                                    GenerationTaskRepository taskRepository,
                                    LLMDescriptionService llmDescriptionService,
                                    EmbeddingService embeddingService) {
        this.neo4jMethodNodeRepository = neo4jMethodNodeRepository;
        this.neo4jSqlNodeRepository = neo4jSqlNodeRepository;
        this.taskRepository = taskRepository;
        this.llmDescriptionService = llmDescriptionService;
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() {
        executorService = new ThreadPoolExecutor(
                concurrency,                     // corePoolSize = 5
                concurrency,                     // maxPoolSize = 5（固定）
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()       // 无界队列，多余任务排队
        );
        fileLog("========== VectorGenerationService 初始化完成: 线程池固定=" + concurrency + ", 队列=无界 ==========");
    }

    @PreDestroy
    public void destroy() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(120, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void fileLog(String message) {
        try {
            Path logDir = Paths.get(LOG_DIR);
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            Path logFile = logDir.resolve(LOG_FILE);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            String logLine = "[" + timestamp + "] " + message + "\n";
            Files.write(logFile, logLine.getBytes(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // ignore
        }
        log.info(message);
    }

    private void fileLog(String format, Object... args) {
        String message = String.format(format, args);
        fileLog(message);
    }

    /**
     * 启动向量生成（异步）
     * 所有方法一次性提交到线程池队列，线程池固定5线程自动消费
     */
    @Async("analysisTaskExecutor")
    public void startVectorGeneration(String projectPath) {
        fileLog("========== [向量生成-双向量] 异步任务开始执行 ========== projectPath=" + projectPath);
        long startTime = System.currentTimeMillis();

        GenerationTask task = GenerationTask.builder()
            .taskType(TASK_TYPE)
            .projectPath(projectPath)
            .status("RUNNING")
            .startedAt(Instant.now().getEpochSecond())
            .successCount(0)
            .failCount(0)
            .build();
        task = taskRepository.insert(task);
        fileLog("[向量生成] 任务已创建, taskId=" + task.getId());

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        progressTracker.put(projectPath, processedCount);

        try {
            // 1. 查询所有方法节点
            fileLog("[向量生成] 从Neo4j查询方法节点...");
            long queryStartTime = System.currentTimeMillis();
            List<MethodNode> allMethods = neo4jMethodNodeRepository.findByProjectPathWithoutRelationships(projectPath);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            fileLog("[向量生成] 查询完成, 耗时: " + queryTime + "ms, 总数量: " + allMethods.size());

            // 2. 断点续传：过滤已有 descriptionEmbedding 的方法
            List<MethodNode> methods = allMethods.stream()
                    .filter(m -> m.getDescriptionEmbedding() == null)
                    .collect(Collectors.toList());
            int skippedCount = allMethods.size() - methods.size();
            fileLog("[向量生成] 断点续传: 总方法数=" + allMethods.size() + ", 跳过已处理=" + skippedCount + ", 待处理=" + methods.size());

            task.setTotalCount(methods.size());
            task.setProgress(0);
            fileLog("[向量生成] Saving initial task state - id={}, totalCount={}", task.getId(), methods.size());
            taskRepository.save(task);
            fileLog("[向量生成] Initial task state saved");

            if (!methods.isEmpty()) {
                fileLog("[向量生成] 开始提交到线程池: totalMethods=" + methods.size() +
                        ", 固定线程=" + concurrency + ", 队列排队");

                // 3. 所有方法一次性提交到线程池，多余的在队列中排队
                final String finalProjectPath = projectPath;
                int totalMethods = methods.size();

                List<CompletableFuture<Void>> futures = new ArrayList<>(totalMethods);
                for (MethodNode method : methods) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        processMethod(method, successCount, failCount);

                        // 更新进度
                        int processed = processedCount.incrementAndGet();
                        fileLog("[向量生成] Method processed - processed={}/{}, success={}, fail={}",
                            processed, totalMethods, successCount.get(), failCount.get());
                        if (processed % progressUpdateInterval == 0 || processed == totalMethods) {
                            // 查询最新的任务来获取 ID
                            Optional<GenerationTask> latestTask = taskRepository.findLatestByProjectPathAndType(finalProjectPath, TASK_TYPE);
                            if (latestTask.isPresent()) {
                                Long taskId = latestTask.get().getId();
                                fileLog("[向量生成] Calling updateProgress for task id={}, processed={}/{}, success={}, fail={}",
                                    taskId, processed, totalMethods, successCount.get(), failCount.get());
                                try {
                                    int rows = taskRepository.updateProgress(taskId, processed, successCount.get(), failCount.get());
                                    fileLog("[向量生成] updateProgress completed, rows updated={}", rows);
                                } catch (Exception e) {
                                    log.error("[向量生成] Failed to update progress", e);
                                }
                            } else {
                                fileLog("[向量生成] Could not find latest task to update progress");
                            }
                            fileLog("[进度] " + processed + "/" + totalMethods +
                                    " (成功=" + successCount.get() + ", 失败=" + failCount.get() +
                                    ", 队列=" + executorService.getQueue().size() + ")");
                        }
                    }, executorService);
                    futures.add(future);
                }

                fileLog("[向量生成] 已提交 " + totalMethods + " 个任务到队列, 当前队列大小: " + executorService.getQueue().size());

                // 等待所有任务完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } else {
                fileLog("[向量生成] 没有需要处理的方法，跳转到 SQL 向量生成...");
            }

            // 4. 生成 SQL 向量
            fileLog("[向量生成] 开始处理 SQL 向量...");
            processSqlNodes(projectPath, task);

            // 5. 完成
            long totalTime = System.currentTimeMillis() - startTime;
            int totalMethods = methods.size();
            // 查询最新的任务来获取 ID
            Optional<GenerationTask> latestTask = taskRepository.findLatestByProjectPathAndType(projectPath, TASK_TYPE);
            if (latestTask.isPresent()) {
                Long taskId = latestTask.get().getId();
                fileLog("[向量生成] Calling updateCompleted - id={}, totalMethods={}, success={}, fail={}",
                    taskId, totalMethods, successCount.get(), failCount.get());
                try {
                    int rows = taskRepository.updateCompleted(taskId, totalMethods, totalMethods, successCount.get(), failCount.get());
                    fileLog("[向量生成] updateCompleted executed, rows updated={}", rows);
                } catch (Exception e) {
                    log.error("[向量生成] Failed to update completed state", e);
                }
            } else {
                fileLog("[向量生成] Could not find latest task to update completed state");
            }

            fileLog("=".repeat(80));
            fileLog("[性能报告] 向量生成完成: projectPath=" + projectPath);
            fileLog("[性能报告] 方法向量: 成功=" + successCount.get() + ", 失败=" + failCount.get());
            fileLog("[性能报告] 总耗时: " + totalTime + "ms");
            fileLog("=".repeat(80));

        } catch (Exception e) {
            fileLog("[FATAL] 任务执行异常: " + e.getMessage());
            log.error("任务执行异常", e);
            fileLog("[FATAL] Calling updateFailed for task id={}", task.getId());
            try {
                int rows = taskRepository.updateFailed(task.getId(), e.getMessage());
                fileLog("[FATAL] updateFailed executed, rows updated={}", rows);
            } catch (Exception ex) {
                log.error("[FATAL] Failed to update failed state", ex);
            }
        } finally {
            progressTracker.remove(projectPath);
            fileLog("[向量生成] Task completed, removed from progressTracker");
        }
    }

    /**
     * 处理单个方法：生成双向量（descriptionEmbedding + codeEmbedding）
     */
    private void processMethod(MethodNode method,
                               AtomicInteger successCount,
                               AtomicInteger failCount) {
        try {
            fileLog("[双向量生成] 处理方法: " + method.getClassName() + "." + method.getMethodName());

            // 1. LLM 描述生成
            String description = llmDescriptionService.generateDescriptionWithBody(method);

            if (description == null || description.trim().isEmpty()) {
                description = method.getClassName() + "." + method.getMethodName() + " - " + method.getSignature();
                fileLog("[双向量生成] LLM返回空描述，使用方法签名: " + description);
            }

            // 2. descriptionEmbedding
            float[] descriptionEmbedding = embeddingService.generateEmbedding(description);

            // 3. codeEmbedding
            String codeText = buildCodeText(method);
            float[] codeEmbedding = embeddingService.generateEmbedding(codeText);

            // 4. 更新 Neo4j
            neo4jMethodNodeRepository.updateDescriptionAndCodeEmbedding(
                    method.getNodeId(), description,
                    toDoubleList(descriptionEmbedding), toDoubleList(codeEmbedding));

            successCount.incrementAndGet();

        } catch (Exception e) {
            fileLog("[ERROR] 处理方法失败: nodeId=" + method.getNodeId() +
                    ", className=" + method.getClassName() +
                    ", methodName=" + method.getMethodName() +
                    ", error=" + e.getMessage());
            failCount.incrementAndGet();
        }
    }

    private String buildCodeText(MethodNode method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getClassName()).append(".").append(method.getMethodName());
        sb.append("(").append(method.getSignature() != null ? method.getSignature() : "").append(")");
        sb.append("\n");

        String methodBody = method.getMethodBody();
        if (methodBody != null && !methodBody.isEmpty()) {
            if (methodBody.length() > MAX_METHOD_BODY_LENGTH) {
                sb.append(methodBody, 0, MAX_METHOD_BODY_LENGTH);
            } else {
                sb.append(methodBody);
            }
        }
        return sb.toString();
    }

    /**
     * 处理 SQL 节点的向量生成（也通过线程池，排队执行）
     */
    private void processSqlNodes(String projectPath, GenerationTask task) {
        fileLog("[SQL向量生成] 开始处理 SQL 节点...");

        List<SqlNode> allSqlNodes = neo4jSqlNodeRepository.findByProjectPath(projectPath);
        fileLog("[SQL向量生成] 查询到 SQL 节点数: " + allSqlNodes.size());

        if (allSqlNodes.isEmpty()) {
            fileLog("[SQL向量生成] 没有 SQL 节点需要处理");
            return;
        }

        List<SqlNode> sqlNodes = allSqlNodes.stream()
                .filter(s -> s.getSqlEmbedding() == null)
                .collect(Collectors.toList());
        int skippedCount = allSqlNodes.size() - sqlNodes.size();
        fileLog("[SQL向量生成] 断点续传: 总SQL数=" + allSqlNodes.size() +
                ", 跳过已处理=" + skippedCount + ", 待处理=" + sqlNodes.size());

        if (sqlNodes.isEmpty()) {
            fileLog("[SQL向量生成] 所有 SQL 节点已处理，跳过");
            return;
        }

        // 通过线程池并发处理 SQL 节点
        AtomicInteger sqlSuccess = new AtomicInteger(0);
        AtomicInteger sqlFail = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>(sqlNodes.size());
        for (SqlNode sqlNode : sqlNodes) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processSqlNode(sqlNode);
                    sqlSuccess.incrementAndGet();
                } catch (Exception e) {
                    fileLog("[ERROR] 处理 SQL 节点失败: nodeId=" + sqlNode.getNodeId() +
                            ", sqlId=" + sqlNode.getSqlId() + ", error=" + e.getMessage());
                    sqlFail.incrementAndGet();
                }
            }, executorService);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        fileLog("[SQL向量生成] 完成: 成功=" + sqlSuccess.get() + ", 失败=" + sqlFail.get());
    }

    private void processSqlNode(SqlNode sqlNode) {
        fileLog("[SQL向量生成] 处理 SQL: " + sqlNode.getSqlId());

        String sqlStatement = sqlNode.getSqlStatement();
        if (sqlStatement == null || sqlStatement.trim().isEmpty()) {
            fileLog("[SQL向量生成] SQL 语句为空，跳过: " + sqlNode.getSqlId());
            return;
        }

        float[] sqlEmbedding = embeddingService.generateEmbedding(sqlStatement);
        neo4jSqlNodeRepository.updateSqlEmbedding(sqlNode.getNodeId(), sqlEmbedding);
    }

    public VectorGenerationTask getTaskStatus(String projectPath) {
        return taskRepository.findLatestByProjectPathAndType(projectPath, TASK_TYPE)
            .map(task -> {
                // 检查任务是否已卡住（RUNNING状态超过1天）
                if ("RUNNING".equals(task.getStatus()) && task.getStartedAt() != null) {
                    long now = Instant.now().getEpochSecond();
                    long hours = (now - task.getStartedAt()) / 3600;
                    if (hours >= 24) {
                        // 认为任务已失败
                        taskRepository.updateFailed(task.getId(), "任务超时（超过1天）");
                        task.setStatus("FAILED");
                        task.setErrorMessage("任务超时（超过1天）");
                        log.warn("任务超时自动标记为FAILED: taskId={}, projectPath={}", task.getId(), projectPath);
                    }
                }
                return toVectorGenerationTask(task);
            })
            .orElse(null);
    }

    @Async("analysisTaskExecutor")
    public void regenerateAll(String projectPath) {
        fileLog("========== [全量重新生成] 开始: projectPath=" + projectPath + " ==========");

        fileLog("[全量重新生成] 步骤1: 清除现有方法描述和向量...");
        long clearedMethodCount = neo4jMethodNodeRepository.clearDescriptionsAndEmbeddings(projectPath);
        fileLog("[全量重新生成] 已清除 " + clearedMethodCount + " 个方法的描述和向量");

        fileLog("[全量重新生成] 步骤2: 清除现有 SQL 向量...");
        long clearedSqlCount = neo4jSqlNodeRepository.clearSqlEmbeddings(projectPath);
        fileLog("[全量重新生成] 已清除 " + clearedSqlCount + " 个 SQL 的向量");

        fileLog("[全量重新生成] 步骤3: 开始重新生成...");
        self.startVectorGeneration(projectPath);
    }

    private static List<Double> toDoubleList(float[] arr) {
        if (arr == null) {
            return java.util.Collections.emptyList();
        }
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add((double) v);
        }
        return list;
    }

    private VectorGenerationTask toVectorGenerationTask(GenerationTask gt) {
        return VectorGenerationTask.builder()
            .id(gt.getId())
            .projectPath(gt.getProjectPath())
            .status(gt.getStatus())
            .totalMethods(gt.getTotalCount())
            .processedMethods(gt.getProgress())
            .successCount(gt.getSuccessCount())
            .failCount(gt.getFailCount())
            .errorMessage(gt.getErrorMessage())
            .startTime(gt.getStartedAt() != null ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(gt.getStartedAt()), ZoneId.systemDefault()) : null)
            .endTime(gt.getFinishedAt() != null ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(gt.getFinishedAt()), ZoneId.systemDefault()) : null)
            .costTimeMs(gt.getStartedAt() != null && gt.getFinishedAt() != null ?
                (gt.getFinishedAt() - gt.getStartedAt()) * 1000 : null)
            .createdAt(gt.getCreatedAt() != null ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(gt.getCreatedAt()), ZoneId.systemDefault()) : null)
            .build();
    }
}
