package com.huawei.hisi.controller;

import com.huawei.hisi.model.*;
import com.huawei.hisi.service.OpsService;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("OpsController 单元测试")
class OpsControllerTest {

    @Mock
    private OpsService opsService;

    @InjectMocks
    private OpsController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("健康检查 - 正常返回")
    void health_shouldReturnStatus() {
        HealthStatus status = HealthStatus.builder()
                .status("UP")
                .components(Map.of("database", "UP", "llm", "UP"))
                .build();
        when(opsService.checkHealth()).thenReturn(status);

        ApiResponse<HealthStatus> response = controller.health();

        assertEquals(200, response.getCode());
        assertEquals("UP", response.getData().getStatus());
    }

    @Test
    @DisplayName("影响分析 - 正常返回")
    void analyzeImpact_shouldReturnAnalysis() {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName("com.example.Service");
        request.setMethodName("doSomething");

        ImpactAnalysisResponse analysis = ImpactAnalysisResponse.builder()
                .targetMethod("com.example.Service.doSomething")
                .affectedMethods(List.of("com.example.Controller.handle"))
                .affectedUris(List.of("GET /api/test"))
                .depth(2)
                .build();

        when(opsService.analyzeImpact(any())).thenReturn(analysis);

        ApiResponse<ImpactAnalysisResponse> response = controller.analyzeImpact(request);

        assertEquals(200, response.getCode());
        assertEquals(2, response.getData().getDepth());
    }

    @Test
    @DisplayName("生成接口文档 - 正常返回")
    void generateInterfaceDoc_shouldReturnDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("uri", "/api/test");
        doc.put("callChain", List.of());

        when(opsService.generateInterfaceDoc("/api/test")).thenReturn(doc);

        ApiResponse<Map<String, Object>> response = controller.generateInterfaceDoc("/api/test");

        assertEquals(200, response.getCode());
        assertEquals("/api/test", response.getData().get("uri"));
    }

    @Test
    @DisplayName("下载日志 - 正常返回")
    void downloadLogs_shouldReturnLogs() {
        Map<String, String> request = new HashMap<>();
        request.put("service", "test-service");
        request.put("level", "ERROR");

        Map<String, Object> logs = new HashMap<>();
        logs.put("total", 10);

        when(opsService.downloadErrorLogs("test-service", null, "ERROR")).thenReturn(logs);

        ApiResponse<Map<String, Object>> response = controller.downloadLogs(request);

        assertEquals(200, response.getCode());
        assertEquals(10, response.getData().get("total"));
    }
}