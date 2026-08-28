package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.aggregation.stage.DomainNameGenerator;
import com.huawei.hisi.knowledgegraph.aggregation.stage.FreeLayerRoleResolver;
import com.huawei.hisi.knowledgegraph.aggregation.stage.MultiDimensionCommunityDetector;
import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1 回归：架构分析消费线程的异常保护。
 *
 * <p>历史缺陷：consumeLoop 中 executeAnalysis 无 try-catch，DB 异常逃逸会杀死消费线程，
 * 队列永久阻塞、后续任务永远 PENDING。
 */
@ExtendWith(MockitoExtension.class)
class ArchitectureAnalysisServiceTest {

    private static final long VERIFY_TIMEOUT_MS = 5000;

    @Mock
    private GenerationTaskRepository taskRepository;
    @Mock
    private MultiDimensionCommunityDetector communityDetector;
    @Mock
    private DomainNameGenerator domainNameGenerator;
    @Mock
    private FreeLayerRoleResolver freeLayerRoleResolver;

    private ArchitectureAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ArchitectureAnalysisService(taskRepository, communityDetector,
                domainNameGenerator, freeLayerRoleResolver);
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    /** 任务不存在时必须落终态 FAILED，而非裸 return 留下永远 RUNNING 的任务。 */
    @Test
    void taskNotFoundShouldMarkFailed() {
        when(taskRepository.insert(any(GenerationTask.class))).thenReturn(taskWithId(1L, "D:/proj-a"));
        when(taskRepository.updateStarted(anyLong())).thenReturn(1);
        when(taskRepository.findById(1L)).thenReturn(Optional.empty());

        service.submit("D:/proj-a");

        verify(taskRepository, timeout(VERIFY_TIMEOUT_MS))
                .updateFailed(eq(1L), contains("任务不存在"));
        verify(taskRepository, never()).updateCompleted(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 核心场景：第一个任务的 DB 异常逃逸后，消费线程必须存活并继续处理后续任务
     * （历史上该异常会直接杀死消费线程）。
     */
    @Test
    void consumerThreadShouldSurviveDbException() {
        // 第 1 个任务：updateStarted 抛异常，模拟 DB 故障
        when(taskRepository.insert(any(GenerationTask.class)))
                .thenReturn(taskWithId(1L, "D:/proj-a"))
                .thenReturn(taskWithId(2L, "D:/proj-a"));
        doThrow(new RuntimeException("sqlite locked")).when(taskRepository).updateStarted(1L);
        when(taskRepository.updateStarted(2L)).thenReturn(1);
        when(taskRepository.findById(2L)).thenReturn(Optional.of(taskWithId(2L, "D:/proj-a")));

        service.submit("D:/proj-a");
        // 第 1 个任务被兜底标 FAILED
        verify(taskRepository, timeout(VERIFY_TIMEOUT_MS))
                .updateFailed(eq(1L), contains("执行异常"));

        service.submit("D:/proj-a");
        // 线程存活的证据：第 2 个任务走完正常路径并标记完成
        verify(taskRepository, timeout(VERIFY_TIMEOUT_MS))
                .updateCompleted(eq(2L), eq(100), eq(1), eq(1), eq(0));
    }

    /** executeAnalysis 内的 LLM 阶段失败时，任务标 FAILED（既有行为回归）。 */
    @Test
    void llmFailureShouldMarkTaskFailed() {
        when(taskRepository.insert(any(GenerationTask.class))).thenReturn(taskWithId(3L, "D:/proj-b"));
        when(taskRepository.updateStarted(3L)).thenReturn(1);
        when(taskRepository.findById(3L)).thenReturn(Optional.of(taskWithId(3L, "D:/proj-b")));
        doThrow(new RuntimeException("deepseek 5xx")).when(communityDetector).detect(anyString());

        service.submit("D:/proj-b");

        verify(taskRepository, timeout(VERIFY_TIMEOUT_MS))
                .updateFailed(eq(3L), contains("deepseek 5xx"));
        verify(taskRepository, never()).updateCompleted(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /** B2：启动时遗留的 PENDING/RUNNING 孤儿任务必须标 FAILED（历史上永远悬挂）。 */
    @Test
    void startupShouldFailOrphanTasks() {
        GenerationTask pending = GenerationTask.builder().id(10L).taskType("ARCH_ANALYSIS")
                .projectPath("D:/proj-x").status("PENDING").build();
        GenerationTask running = GenerationTask.builder().id(11L).taskType("ARCH_ANALYSIS")
                .projectPath("D:/proj-y").status("RUNNING").build();
        when(taskRepository.findRunningOrPending("ARCH_ANALYSIS")).thenReturn(List.of(pending, running));

        ArchitectureAnalysisService fresh = new ArchitectureAnalysisService(taskRepository,
                communityDetector, domainNameGenerator, freeLayerRoleResolver);
        fresh.init();
        fresh.destroy();

        verify(taskRepository).updateFailed(eq(10L), contains("应用重启"));
        verify(taskRepository).updateFailed(eq(11L), contains("应用重启"));
    }

    /** B3：同项目已在队列中等待时，重复提交必须拒绝（历史上会重复排队重复执行 LLM）。 */
    @Test
    void duplicateSubmitWhileQueuedShouldReject() {
        ArchitectureAnalysisService notStarted = new ArchitectureAnalysisService(taskRepository,
                communityDetector, domainNameGenerator, freeLayerRoleResolver);
        when(taskRepository.insert(any(GenerationTask.class))).thenReturn(taskWithId(1L, "D:/proj-a"));

        notStarted.submit("D:/proj-a");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> notStarted.submit("D:/proj-a"));
    }

    /** B3：同项目正在执行中时，重复提交必须拒绝。 */
    @Test
    void duplicateSubmitWhileRunningShouldReject() throws Exception {
        CountDownLatch analysisStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            analysisStarted.countDown();
            release.await();
            return null;
        }).when(communityDetector).detect(anyString());
        when(taskRepository.insert(any(GenerationTask.class))).thenReturn(taskWithId(1L, "D:/proj-a"));
        when(taskRepository.updateStarted(anyLong())).thenReturn(1);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(taskWithId(1L, "D:/proj-a")));

        service.submit("D:/proj-a");
        org.junit.jupiter.api.Assertions.assertTrue(analysisStarted.await(5, TimeUnit.SECONDS),
                "分析应已开始执行");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.submit("D:/proj-a"));

        release.countDown();
    }

    /** B3：提交时应覆盖同项目陈旧 PENDING 任务（如队列线程异常期间的遗留）。 */
    @Test
    void submitShouldOverrideStalePendingTask() {
        ArchitectureAnalysisService notStarted = new ArchitectureAnalysisService(taskRepository,
                communityDetector, domainNameGenerator, freeLayerRoleResolver);
        GenerationTask stale = GenerationTask.builder().id(1L).taskType("ARCH_ANALYSIS")
                .projectPath("D:/proj-a").status("PENDING").build();
        when(taskRepository.findLatestByProjectPathAndType("D:/proj-a", "ARCH_ANALYSIS"))
                .thenReturn(Optional.of(stale));
        when(taskRepository.insert(any(GenerationTask.class))).thenReturn(taskWithId(2L, "D:/proj-a"));

        notStarted.submit("D:/proj-a");

        verify(taskRepository).updateFailed(eq(1L), eq("被新任务覆盖"));
    }

    private GenerationTask taskWithId(Long id, String projectPath) {
        return GenerationTask.builder()
                .id(id)
                .taskType("ARCH_ANALYSIS")
                .projectPath(projectPath)
                .status("PENDING")
                .build();
    }
}
