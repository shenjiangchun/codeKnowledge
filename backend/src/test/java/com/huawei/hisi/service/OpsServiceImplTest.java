package com.huawei.hisi.service;

import com.huawei.hisi.model.*;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OpsServiceImpl 单元测试
 * 测试运维服务的健康检查、影响分析、接口文档生成等功能
 * 重构版：使用 Neo4j 仓库替代旧的 CallChainService
 */
@ExtendWith(MockitoExtension.class)
class OpsServiceImplTest {

    @Mock
    private LogCloudService logCloudService;

    @Mock
    private LogAnalysisRepository repository;

    @Mock
    private Neo4jMethodNodeRepository neo4jMethodNodeRepository;

    @Mock
    private Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository;

    @InjectMocks
    private OpsServiceImpl opsService;

    private ImpactAnalysisRequest impactRequest;

    @BeforeEach
    void setUp() {
        // 初始化影响分析请求
        impactRequest = new ImpactAnalysisRequest();
        impactRequest.setClassName("com.example.service.UserService");
        impactRequest.setMethodName("getUserById");
        impactRequest.setProject("example-project");
    }

    // ==================== checkHealth Tests ====================

    @Test
    @DisplayName("健康检查 - 所有组件正常")
    void testCheckHealth_AllComponentsUp() {
        // Given
        when(repository.findById(1L)).thenReturn(new LogAnalysisRepository.LogAnalysisReportEntity());
        when(neo4jMethodNodeRepository.count()).thenReturn(100L);

        // When
        HealthStatus result = opsService.checkHealth();

        // Then
        assertNotNull(result);
        assertEquals("UP", result.getStatus());
        assertNotNull(result.getComponents());
        assertEquals("UP", result.getComponents().get("database"));
        assertEquals("UP", result.getComponents().get("logcloud"));
        assertTrue(result.getComponents().get("neo4j").startsWith("UP"));
        assertNotNull(result.getCheckTime());
    }

    @Test
    @DisplayName("健康检查 - 数据库异常")
    void testCheckHealth_DatabaseDown() {
        // Given
        when(repository.findById(1L)).thenThrow(new RuntimeException("Database connection failed"));
        when(neo4jMethodNodeRepository.count()).thenReturn(100L);

        // When
        HealthStatus result = opsService.checkHealth();

        // Then
        assertNotNull(result);
        assertEquals("DEGRADED", result.getStatus());
        assertNotNull(result.getComponents());
        assertTrue(result.getComponents().get("database").startsWith("DOWN:"));
        assertEquals("UP", result.getComponents().get("logcloud"));
    }

    @Test
    @DisplayName("健康检查 - Neo4j 异常")
    void testCheckHealth_Neo4jDown() {
        // Given
        when(repository.findById(1L)).thenReturn(new LogAnalysisRepository.LogAnalysisReportEntity());
        when(neo4jMethodNodeRepository.count()).thenThrow(new RuntimeException("Neo4j connection failed"));

        // When
        HealthStatus result = opsService.checkHealth();

        // Then
        assertNotNull(result);
        assertEquals("DEGRADED", result.getStatus());
        assertTrue(result.getComponents().get("neo4j").startsWith("DOWN:"));
    }

    // ==================== analyzeImpact Tests ====================

    @Test
    @DisplayName("影响分析 - 正常流程")
    void testAnalyzeImpact_Success() {
        // Given
        String targetMethod = impactRequest.getClassName() + "." + impactRequest.getMethodName();

        // Mock 方法节点
        MethodNode methodNode = new MethodNode();
        methodNode.setNodeId("test-project:com.example.service.UserService.getUserById");
        methodNode.setClassName("com.example.service.UserService");
        methodNode.setMethodName("getUserById");
        methodNode.setProjectPath("test-project");

        // Mock 调用者
        MethodNode caller1 = new MethodNode();
        caller1.setNodeId("test-project:com.example.controller.UserController.getUser");
        caller1.setClassName("com.example.controller.UserController");
        caller1.setMethodName("getUser");

        MethodNode caller2 = new MethodNode();
        caller2.setNodeId("test-project:com.example.controller.AdminController.viewUser");
        caller2.setClassName("com.example.controller.AdminController");
        caller2.setMethodName("viewUser");

        when(neo4jMethodNodeRepository.findByClassName("com.example.service.UserService"))
                .thenReturn(List.of(methodNode));
        when(neo4jMethodNodeRepository.findCallersUpToDepth(anyString(), eq(5)))
                .thenReturn(List.of(caller1, caller2));
        when(neo4jMethodNodeRepository.findEntryPointsCallingMethod(anyString(), anyString()))
                .thenReturn(List.of(
                        new Neo4jMethodNodeRepository.EntryPointInfo("ep1", "HTTP", "GET /api/users/{id}")
                ));

        // When
        ImpactAnalysisResponse result = opsService.analyzeImpact(impactRequest);

        // Then
        assertNotNull(result);
        assertEquals(targetMethod, result.getTargetMethod());
        assertNotNull(result.getAffectedMethods());
        assertEquals(2, result.getAffectedMethods().size());
        assertTrue(result.getAffectedMethods().contains("com.example.controller.UserController.getUser"));
        assertTrue(result.getAffectedMethods().contains("com.example.controller.AdminController.viewUser"));
        assertNotNull(result.getAnalysisTime());
    }

    @Test
    @DisplayName("影响分析 - 未找到方法")
    void testAnalyzeImpact_MethodNotFound() {
        // Given
        when(neo4jMethodNodeRepository.findByClassName("com.example.service.UserService"))
                .thenReturn(Collections.emptyList());

        // When
        ImpactAnalysisResponse result = opsService.analyzeImpact(impactRequest);

        // Then
        assertNotNull(result);
        assertTrue(result.getAffectedMethods().isEmpty());
        assertTrue(result.getAffectedUris().isEmpty());
    }

    // ==================== generateInterfaceDoc Tests ====================

    @Test
    @DisplayName("生成接口文档 - 正常流程")
    void testGenerateInterfaceDoc_Success() {
        // Given
        String uri = "GET /api/users/{id}";

        EntryPointNode entryPoint = new EntryPointNode();
        entryPoint.setEntryId("test-project:HTTP_getUser");
        entryPoint.setEntryType("HTTP");
        entryPoint.setEntryKey(uri);
        entryPoint.setProjectPath("test-project");

        // Mock 图遍历结果
        Neo4jMethodNodeRepository.GraphTraversalResult node1 =
                new Neo4jMethodNodeRepository.GraphTraversalResult(
                        "test-project:com.example.controller.UserController.getUser",
                        "com.example.controller.UserController",
                        "getUser",
                        "getUser(Long id)",
                        "UserController.java",
                        10,
                        0
                );

        Neo4jMethodNodeRepository.GraphTraversalResult node2 =
                new Neo4jMethodNodeRepository.GraphTraversalResult(
                        "test-project:com.example.service.UserService.getUserById",
                        "com.example.service.UserService",
                        "getUserById",
                        "getUserById(Long id)",
                        "UserService.java",
                        25,
                        1
                );

        when(neo4jEntryPointNodeRepository.findByEntryKey(uri))
                .thenReturn(Optional.of(entryPoint));
        when(neo4jMethodNodeRepository.getCallChainNodesByEntryKey(uri, "test-project", 20))
                .thenReturn(List.of(node1, node2));

        // When
        Map<String, Object> result = opsService.generateInterfaceDoc(uri);

        // Then
        assertNotNull(result);
        assertEquals(uri, result.get("uri"));
        assertNotNull(result.get("generatedAt"));
        assertEquals("test-project:HTTP_getUser", result.get("entryId"));
        assertEquals("HTTP", result.get("entryType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callChain = (List<Map<String, Object>>) result.get("callChain");
        assertEquals(2, callChain.size());
    }

    @Test
    @DisplayName("生成接口文档 - 未找到入口点")
    void testGenerateInterfaceDoc_EntryPointNotFound() {
        // Given
        String uri = "GET /api/nonexistent";
        when(neo4jEntryPointNodeRepository.findByEntryKey(uri))
                .thenReturn(Optional.empty());

        // When
        Map<String, Object> result = opsService.generateInterfaceDoc(uri);

        // Then
        assertNotNull(result);
        assertEquals(uri, result.get("uri"));
        assertEquals(0, result.get("callChainDepth"));

        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) result.get("methods");
        assertTrue(methods.isEmpty());
    }

    // ==================== downloadErrorLogs Tests ====================

    @Test
    @DisplayName("下载错误日志 - 正常流程")
    void testDownloadErrorLogs_Success() {
        // Given
        String service = "user-service";
        String timeRange = "1h";
        String level = "ERROR";

        List<LogEntry> mockLogs = new ArrayList<>();
        LogEntry log1 = new LogEntry();
        log1.setId(1L);
        log1.setLevel("ERROR");
        log1.setMessage("NullPointerException occurred");
        mockLogs.add(log1);

        LogEntry log2 = new LogEntry();
        log2.setId(2L);
        log2.setLevel("ERROR");
        log2.setMessage("Connection timeout");
        mockLogs.add(log2);

        when(logCloudService.queryLogs(any(LogQueryDto.class))).thenReturn(mockLogs);

        // When
        Map<String, Object> result = opsService.downloadErrorLogs(service, timeRange, level);

        // Then
        assertNotNull(result);
        assertEquals(2, result.get("total"));
        assertEquals(mockLogs, result.get("logs"));
        assertNotNull(result.get("downloadTime"));

        // 验证 LogQueryDto 设置正确
        verify(logCloudService).queryLogs(argThat(query ->
            service.equals(query.getAppId()) && level.equals(query.getLogLevel())
        ));
    }

    @Test
    @DisplayName("下载错误日志 - 无日志")
    void testDownloadErrorLogs_NoLogs() {
        // Given
        String service = "empty-service";
        String timeRange = "24h";
        String level = "WARN";

        when(logCloudService.queryLogs(any(LogQueryDto.class))).thenReturn(Collections.emptyList());

        // When
        Map<String, Object> result = opsService.downloadErrorLogs(service, timeRange, level);

        // Then
        assertNotNull(result);
        assertEquals(0, result.get("total"));
        @SuppressWarnings("unchecked")
        List<LogEntry> logs = (List<LogEntry>) result.get("logs");
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("下载错误日志 - null 参数")
    void testDownloadErrorLogs_WithNullParameters() {
        // Given
        when(logCloudService.queryLogs(any(LogQueryDto.class))).thenReturn(Collections.emptyList());

        // When
        Map<String, Object> result = opsService.downloadErrorLogs(null, null, null);

        // Then
        assertNotNull(result);
        assertEquals(0, result.get("total"));

        // 验证即使参数为 null，也能正常调用
        verify(logCloudService).queryLogs(any(LogQueryDto.class));
    }
}
