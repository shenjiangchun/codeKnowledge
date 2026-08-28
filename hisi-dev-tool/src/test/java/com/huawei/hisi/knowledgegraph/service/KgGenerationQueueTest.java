package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.aggregation.AggregationPipeline;
import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 回归：KG 生成队列的启动恢复。
 *
 * <p>历史缺陷：队列是内存 LinkedBlockingQueue + daemon 线程，重启后 SQLite 里
 * 遗留的 PENDING/RUNNING KG 任务无人认领，前端永远轮询不到终态。
 */
@ExtendWith(MockitoExtension.class)
class KgGenerationQueueTest {

    @Mock
    private KnowledgeGraphBuilder knowledgeGraphBuilder;
    @Mock
    private IncrementalKnowledgeGraphBuilder incrementalBuilder;
    @Mock
    private GenerationTaskRepository taskRepository;
    @Mock
    private AggregationPipeline aggregationPipeline;
    @Mock
    private FrontendGraphOrchestrator frontendGraphOrchestrator;

    private KgGenerationQueue queue;

    @BeforeEach
    void setUp() {
        queue = new KgGenerationQueue(knowledgeGraphBuilder, incrementalBuilder,
                taskRepository, aggregationPipeline, frontendGraphOrchestrator);
    }

    @AfterEach
    void tearDown() {
        queue.destroy();
    }

    /** B2：启动时遗留的 PENDING/RUNNING KG 孤儿任务必须标 FAILED。 */
    @Test
    void startupShouldFailOrphanTasks() {
        GenerationTask pending = GenerationTask.builder().id(20L).taskType("KG")
                .projectPath("D:/proj-x").status("PENDING").build();
        GenerationTask running = GenerationTask.builder().id(21L).taskType("KG")
                .projectPath("D:/proj-y").status("RUNNING").build();
        when(taskRepository.findRunningOrPending("KG")).thenReturn(List.of(pending, running));

        queue.init();

        verify(taskRepository).updateFailed(eq(20L), contains("应用重启"));
        verify(taskRepository).updateFailed(eq(21L), contains("应用重启"));
    }

    /** B2：恢复逻辑自身异常不能阻断队列启动。 */
    @Test
    void recoveryFailureShouldNotBlockStartup() {
        when(taskRepository.findRunningOrPending("KG")).thenThrow(new RuntimeException("db down"));

        queue.init();

        // 消费线程仍被创建（destroy 可正常 interrupt 即证明存活）
        queue.destroy();
    }

    /**
     * B5：陈旧 COMPLETED VECTOR 任务（上一轮遗留）不得放行聚合；
     * 只有本次构建触发的 id 更大的新任务出现后才继续。同时验证 B9 真实方法计数。
     */
    @Test
    void waitForVectorShouldIgnoreStaleCompletedTask(@TempDir Path tempDir) {
        String projectPath = KnowledgeGraphCommonUtils.normalizePath(tempDir.toString());
        GenerationTask stale = vectorTask(5L, projectPath, "COMPLETED");
        GenerationTask fresh = vectorTask(6L, projectPath, "COMPLETED");
        // 新任务在 1.5s 后才"插入"（模拟 @Async 线程延迟）
        AtomicBoolean freshVisible = new AtomicBoolean(false);

        when(taskRepository.insert(any(GenerationTask.class))).thenReturn(kgTask(1L, projectPath));
        when(taskRepository.updateStarted(anyLong())).thenReturn(1);
        // enqueue 内部的陈旧 KG 任务覆盖检查
        when(taskRepository.findLatestByProjectPathAndType(projectPath, "KG")).thenReturn(Optional.empty());
        when(knowledgeGraphBuilder.buildKnowledgeGraph(eq(projectPath), any(), anyBoolean(), any()))
                .thenReturn(Map.of("methodNodeCount", 42));
        when(taskRepository.findLatestByProjectPathAndType(projectPath, "VECTOR"))
                .thenAnswer(inv -> Optional.of(freshVisible.get() ? fresh : stale));

        queue.init();
        queue.enqueue(projectPath, null, true, true, BuildMode.REUSE);

        // 陈旧 COMPLETED(id=5) 不应放行聚合（历史缺陷会立即放行）
        verify(aggregationPipeline, after(1500).never())
                .run(anyString(), anyString(), any(), any());

        // 新任务(id=6)出现后聚合才执行
        freshVisible.set(true);
        verify(aggregationPipeline, org.mockito.Mockito.timeout(5000))
                .run(eq(projectPath), eq("FULL"), any(), any());

        // B9：KG 任务计数传真实方法数（历史缺陷恒传 0）
        verify(taskRepository, org.mockito.Mockito.timeout(5000))
                .updateCompleted(eq(1L), eq(100), eq(42), eq(42), eq(0));
    }

    private GenerationTask kgTask(Long id, String projectPath) {
        return GenerationTask.builder().id(id).taskType("KG")
                .projectPath(projectPath).status("PENDING").build();
    }

    private GenerationTask vectorTask(Long id, String projectPath, String status) {
        return GenerationTask.builder().id(id).taskType("VECTOR")
                .projectPath(projectPath).status(status).build();
    }
}
