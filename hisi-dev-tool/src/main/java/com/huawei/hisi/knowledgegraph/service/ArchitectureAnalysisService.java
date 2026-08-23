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

import java.util.concurrent.LinkedBlockingQueue;

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

    private final LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private Thread consumerThread;
    private volatile boolean running = true;

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
        consumerThread = new Thread(this::consumeLoop, "arch-analysis-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("[ArchAnalysis] Consumer thread started");
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
     */
    public Long submit(String projectPath) {
        GenerationTask task = taskRepository.insert(GenerationTask.builder()
                .taskType(TASK_TYPE)
                .projectPath(projectPath)
                .status("PENDING")
                .build());
        queue.offer(task.getId());
        log.info("[ArchAnalysis] Enqueued: projectPath={}, taskId={}, queueSize={}",
                projectPath, task.getId(), queue.size());
        return task.getId();
    }

    private void consumeLoop() {
        while (running) {
            Long taskId;
            try {
                taskId = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[ArchAnalysis] Consumer interrupted, exiting");
                break;
            }
            executeAnalysis(taskId);
        }
    }

    private void executeAnalysis(Long taskId) {
        taskRepository.updateStarted(taskId);
        GenerationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getProjectPath() == null) {
            log.warn("[ArchAnalysis] Task not found: taskId={}", taskId);
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
