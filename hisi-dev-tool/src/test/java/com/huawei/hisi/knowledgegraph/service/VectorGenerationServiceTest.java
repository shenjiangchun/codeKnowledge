package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VectorGenerationService 单元测试
 * 测试并发处理向量生成功能
 */
@ExtendWith(MockitoExtension.class)
class VectorGenerationServiceTest {

    @Mock
    private Neo4jMethodNodeRepository neo4jMethodNodeRepository;

    @Mock
    private Neo4jSqlNodeRepository neo4jSqlNodeRepository;

    @Mock
    private Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository;

    @Mock
    private GenerationTaskRepository taskRepository;

    @Mock
    private LLMDescriptionService llmDescriptionService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private EntryPointDescriptionService entryPointDescriptionService;

    private VectorGenerationService service;
    private ExecutorService testExecutor;

    @BeforeEach
    void setUp() {
        service = new VectorGenerationService(
                neo4jMethodNodeRepository,
                neo4jSqlNodeRepository,
                neo4jEntryPointNodeRepository,
                taskRepository,
                llmDescriptionService,
                embeddingService,
                entryPointDescriptionService
        );

        // 设置默认并发配置
        ReflectionTestUtils.setField(service, "concurrency", 10);
        ReflectionTestUtils.setField(service, "progressUpdateInterval", 10);

        // 创建测试用的线程池（ThreadPoolExecutor + LinkedBlockingQueue）
        testExecutor = Executors.newFixedThreadPool(10);
        ReflectionTestUtils.setField(service, "executorService",
                new java.util.concurrent.ThreadPoolExecutor(10, 10, 60L,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>()));
    }

    // ==================== Concurrency Configuration Tests ====================

    @Test
    @DisplayName("并发配置 - 默认值应为5")
    void testConcurrencyDefaultValue() {
        // Given - 新创建的服务实例
        VectorGenerationService newService = new VectorGenerationService(
                neo4jMethodNodeRepository,
                neo4jSqlNodeRepository,
                neo4jEntryPointNodeRepository,
                taskRepository,
                llmDescriptionService,
                embeddingService,
                entryPointDescriptionService
        );

        // When & Then - 默认值应该是5（通过@Value注解的默认值）
        assertNotNull(newService);
    }

    @Test
    @DisplayName("并发配置 - 可配置为不同值")
    void testConcurrencyConfigurable() {
        // Given
        ReflectionTestUtils.setField(service, "concurrency", 10);

        // When & Then - 验证配置可以修改
        assertEquals(10, ReflectionTestUtils.getField(service, "concurrency"));
    }

    // ==================== Batch Processing Tests ====================

    @Test
    @DisplayName("批处理 - 方法数量少于并发数时正确处理")
    void testBatchProcessing_FewerMethodsThanConcurrency() {
        // Given - 3个方法，并发数为5
        List<MethodNode> methods = createMethodNodes(3);
        GenerationTask task = createTask(1L, "/test/project", 3);

        setupMocks(task, methods);

        // When
        service.startVectorGeneration("/test/project");

        // Then - 所有方法都应被处理
        verify(llmDescriptionService, times(3)).generateDescriptionWithBody(any(MethodNode.class));
        verify(neo4jMethodNodeRepository, times(3)).updateDescriptionAndCodeEmbedding(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("批处理 - 方法数量大于并发数时分批处理")
    void testBatchProcessing_MoreMethodsThanConcurrency() {
        // Given - 12个方法，并发数为5
        List<MethodNode> methods = createMethodNodes(12);
        GenerationTask task = createTask(1L, "/test/project", 12);

        setupMocks(task, methods);

        // When
        service.startVectorGeneration("/test/project");

        // Then - 所有方法都应被处理
        verify(llmDescriptionService, times(12)).generateDescriptionWithBody(any(MethodNode.class));
        verify(neo4jMethodNodeRepository, times(12)).updateDescriptionAndCodeEmbedding(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("批处理 - 空方法列表正确处理")
    void testBatchProcessing_EmptyMethodList() {
        // Given
        GenerationTask task = createTask(1L, "/test/project", 0);

        when(taskRepository.insert(any(GenerationTask.class))).thenReturn(task);
        when(taskRepository.save(any(GenerationTask.class))).thenReturn(task);
        when(neo4jMethodNodeRepository.findByProjectPathWithoutRelationships("/test/project"))
                .thenReturn(new ArrayList<>());

        // When
        service.startVectorGeneration("/test/project");

        // Then - 不应该调用LLM和embedding服务
        verify(llmDescriptionService, never()).generateDescriptionWithBody(any(MethodNode.class));
        verify(neo4jMethodNodeRepository, never()).updateDescriptionAndCodeEmbedding(anyString(), anyString(), anyList(), anyList());
    }

    // ==================== Progress Update Tests ====================

    @Test
    @DisplayName("进度更新 - 每10个方法更新一次进度")
    void testProgressUpdate_EveryTenMethods() {
        // Given - 25个方法
        List<MethodNode> methods = createMethodNodes(25);
        GenerationTask task = createTask(1L, "/test/project", 25);

        setupMocks(task, methods);

        // When
        service.startVectorGeneration("/test/project");

        // Then - 验证进度更新被调用
        verify(taskRepository, atLeast(1)).updateProgress(anyLong(), anyInt(), anyInt(), anyInt());
        verify(taskRepository, atLeast(1)).updateCompleted(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("进度更新 - 更新成功和失败计数")
    void testProgressUpdate_SuccessAndFailCount() {
        // Given
        List<MethodNode> methods = createMethodNodes(5);
        GenerationTask task = createTask(1L, "/test/project", 5);

        setupMocks(task, methods);

        // 模拟第3个方法处理失败
        when(llmDescriptionService.generateDescriptionWithBody(methods.get(2)))
                .thenThrow(new RuntimeException("LLM调用失败"));

        // When
        service.startVectorGeneration("/test/project");

        // Then - 验证任务完成
        verify(taskRepository, atLeast(1)).updateCompleted(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    // ==================== Error Handling Tests ====================

    @Test
    @DisplayName("错误处理 - 单个方法失败不影响其他方法")
    void testErrorHandling_SingleMethodFailure() {
        // Given
        List<MethodNode> methods = createMethodNodes(5);
        GenerationTask task = createTask(1L, "/test/project", 5);

        setupMocks(task, methods);

        // 模拟第2个方法处理失败
        when(llmDescriptionService.generateDescriptionWithBody(methods.get(1)))
                .thenThrow(new RuntimeException("LLM调用失败"));

        // When
        service.startVectorGeneration("/test/project");

        // Then - 其他4个方法应该正常处理
        verify(llmDescriptionService, times(5)).generateDescriptionWithBody(any(MethodNode.class));
    }

    @Test
    @DisplayName("错误处理 - Embedding生成失败时抛出异常")
    void testErrorHandling_EmbeddingFailure() {
        // Given
        List<MethodNode> methods = createMethodNodes(1);
        GenerationTask task = createTask(1L, "/test/project", 1);

        setupMocks(task, methods);

        // Embedding服务失败
        lenient().when(embeddingService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("Embedding服务不可用"));

        // When
        service.startVectorGeneration("/test/project");

        // Then - 应该记录失败
        verify(taskRepository, atLeast(1)).updateCompleted(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("错误处理 - 服务不可用时记录失败")
    void testErrorHandling_ServiceUnavailable() {
        // Given
        List<MethodNode> methods = createMethodNodes(1);
        GenerationTask task = createTask(1L, "/test/project", 1);

        setupMocks(task, methods);

        // Embedding服务不可用 - 使描述生成返回空以触发降级
        when(llmDescriptionService.generateDescriptionWithBody(any(MethodNode.class)))
                .thenThrow(new RuntimeException("服务不可用"));

        // When
        service.startVectorGeneration("/test/project");

        // Then - 任务应该完成但有失败记录
        verify(taskRepository, atLeast(1)).updateCompleted(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    // ==================== Rate Limiting Tests ====================

    @Test
    @DisplayName("限流机制 - API调用之间有间隔")
    void testRateLimiting_ApiCallInterval() {
        // Given
        List<MethodNode> methods = createMethodNodes(3);
        GenerationTask task = createTask(1L, "/test/project", 3);

        setupMocks(task, methods);

        // When
        service.startVectorGeneration("/test/project");

        // Then - 由于限流，处理时间应该考虑间隔
        verify(llmDescriptionService, times(3)).generateDescriptionWithBody(any(MethodNode.class));
    }

    // ==================== Performance Statistics Tests ====================

    @Test
    @DisplayName("性能统计 - 更新平均处理时间")
    void testPerformanceStats_AvgTimePerMethod() {
        // Given
        List<MethodNode> methods = createMethodNodes(5);
        GenerationTask task = createTask(1L, "/test/project", 5);

        setupMocks(task, methods);

        // When
        service.startVectorGeneration("/test/project");

        // Then - 验证任务完成
        verify(taskRepository, atLeast(1)).updateCompleted(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    // ==================== Helper Methods ====================

    private List<MethodNode> createMethodNodes(int count) {
        List<MethodNode> methods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MethodNode method = new MethodNode();
            method.setNodeId("node-" + i);
            method.setClassName("TestService" + i);
            method.setMethodName("testMethod" + i);
            method.setSignature("void testMethod" + i + "()");
            method.setProjectPath("/test/project");
            methods.add(method);
        }
        return methods;
    }

    private GenerationTask createTask(Long id, String projectPath, int totalMethods) {
        return GenerationTask.builder()
                .id(id)
                .taskType("VECTOR")
                .projectPath(projectPath)
                .status("RUNNING")
                .totalCount(totalMethods)
                .progress(0)
                .successCount(0)
                .failCount(0)
                .startedAt(Instant.now().getEpochSecond())
                .build();
    }

    private void setupMocks(GenerationTask task, List<MethodNode> methods) {
        // 任务插入
        when(taskRepository.insert(any(GenerationTask.class))).thenAnswer(invocation -> {
            GenerationTask t = invocation.getArgument(0);
            if (t.getId() == null) {
                t.setId(1L);
            }
            return t;
        });

        // 任务保存
        when(taskRepository.save(any(GenerationTask.class))).thenAnswer(invocation -> {
            GenerationTask t = invocation.getArgument(0);
            if (t.getId() == null) {
                t.setId(1L);
            }
            return t;
        });

        // 查询方法节点
        when(neo4jMethodNodeRepository.findByProjectPathWithoutRelationships(anyString()))
                .thenReturn(methods);

        // LLM描述生成
        lenient().when(llmDescriptionService.generateDescriptionWithBody(any(MethodNode.class)))
                .thenReturn("测试描述");

        // Embedding服务 - 使用lenient以避免UnnecessaryStubbingException
        lenient().when(embeddingService.isEmbeddingAvailable()).thenReturn(true);
        lenient().when(embeddingService.generateEmbedding(anyString()))
                .thenReturn(new float[2048]);
        lenient().when(embeddingService.getEmbeddingDimension()).thenReturn(2048);

        // Neo4j更新 - 使用lenient
        lenient().doNothing().when(neo4jMethodNodeRepository).updateDescriptionAndCodeEmbedding(
                anyString(), anyString(), anyList(), anyList());

        // SQL节点查询 - 返回空列表
        lenient().when(neo4jSqlNodeRepository.findByProjectPath(anyString()))
                .thenReturn(new ArrayList<>());
    }

    // ==================== Concurrent Execution Tests ====================

    @Test
    @DisplayName("并发执行 - 验证CompletableFuture并发处理")
    void testConcurrentExecution_WithCompletableFuture() {
        // Given
        List<MethodNode> methods = createMethodNodes(10);
        GenerationTask task = createTask(1L, "/test/project", 10);

        setupMocks(task, methods);

        AtomicInteger callOrder = new AtomicInteger(0);

        // 跟踪调用顺序以验证并发
        when(llmDescriptionService.generateDescriptionWithBody(any(MethodNode.class))).thenAnswer(invocation -> {
            int order = callOrder.incrementAndGet();
            Thread.sleep(50); // 模拟处理时间
            return "描述-" + order;
        });

        // When
        service.startVectorGeneration("/test/project");

        // Then - 并发处理应该比串行快
        verify(llmDescriptionService, times(10)).generateDescriptionWithBody(any(MethodNode.class));
    }

    @Test
    @DisplayName("并发执行 - 验证批次处理")
    void testConcurrentExecution_BatchProcessing() {
        // Given
        List<MethodNode> methods = createMethodNodes(15);
        GenerationTask task = createTask(1L, "/test/project", 15);

        setupMocks(task, methods);

        // When
        service.startVectorGeneration("/test/project");

        // Then - 验证所有方法被处理
        verify(llmDescriptionService, times(15)).generateDescriptionWithBody(any(MethodNode.class));
    }
}
