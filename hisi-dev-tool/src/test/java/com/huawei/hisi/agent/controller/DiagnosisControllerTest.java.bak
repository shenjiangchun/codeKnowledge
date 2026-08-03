package com.huawei.hisi.agent.controller;

import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentResult;
import com.huawei.hisi.agent.model.DiagnosisRequest;
import com.huawei.hisi.agent.orchestrator.AgentOrchestrator;
import com.huawei.hisi.agent.orchestrator.DiagnosisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DiagnosisController 单元测试
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiagnosisController 单元测试")
class DiagnosisControllerTest {

    @Mock
    private AgentOrchestrator agentOrchestrator;

    private DiagnosisController controller;

    @BeforeEach
    void setUp() {
        controller = new DiagnosisController(agentOrchestrator);
    }

    @Test
    @DisplayName("测试同步诊断 - 成功")
    void testDiagnoseSuccess() {
        // 准备请求
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage("NullPointerException")
                .stackTrace("at com.example.Test.method(Test.java:10)")
                .projectPath("/project/src")
                .build();

        // Mock 编排器返回
        DiagnosisResult mockResult = DiagnosisResult.builder()
                .requestId("req-001")
                .primaryConclusion("NullPointerException in Test.method")
                .primaryRootCause("Object is null at line 10")
                .primaryConfidence(0.85)
                .overallConfidence(0.80)
                .combinedAffectedCode(List.of("com.example.Test.method"))
                .combinedFixSuggestions(List.of("Add null check"))
                .agentResults(List.of(
                        AgentResult.builder()
                                .agentType("STACK_TRACE")
                                .status(AgentResult.Status.SUCCESS)
                                .confidence(0.85)
                                .executionTimeMs(100L)
                                .build()
                ))
                .successCount(1)
                .failedCount(0)
                .totalTimeMs(150L)
                .build();

        when(agentOrchestrator.diagnose(any(AgentContext.class))).thenReturn(mockResult);

        // 执行
        var response = controller.diagnose(request);

        // 验证
        assertEquals(200, response.getCode());
        assertNotNull(response.getData());
        assertEquals("req-001", response.getData().getRequestId());
        assertEquals("NullPointerException in Test.method", response.getData().getConclusion());
        assertEquals(0.80, response.getData().getConfidence());
        assertFalse(response.getData().getAffectedCode().isEmpty());
        assertFalse(response.getData().getFixSuggestions().isEmpty());

        verify(agentOrchestrator).diagnose(any(AgentContext.class));
    }

    @Test
    @DisplayName("测试同步诊断 - 无堆栈信息")
    void testDiagnoseNoStackTrace() {
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage("Unknown error")
                .build();

        DiagnosisResult mockResult = DiagnosisResult.empty("req-002", "没有可用的诊断 Agent");
        when(agentOrchestrator.diagnose(any(AgentContext.class))).thenReturn(mockResult);

        var response = controller.diagnose(request);

        assertTrue(response.getCode() == 200);
        assertNotNull(response.getData());
        assertEquals(0.0, response.getData().getConfidence());
    }

    @Test
    @DisplayName("测试异步诊断")
    void testDiagnoseAsync() {
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage("Test error")
                .stackTrace("test stack trace")
                .build();

        when(agentOrchestrator.diagnoseAsync(any(AgentContext.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        DiagnosisResult.builder().requestId("req-async").build()
                ));

        var response = controller.diagnoseAsync(request);

        assertEquals(200, response.getCode());
        assertNotNull(response.getData());  // 返回 request ID
        assertTrue(response.getMessage().contains("WebSocket") || response.getData().contains("requestId"));

        verify(agentOrchestrator).diagnoseAsync(any(AgentContext.class));
    }

    @Test
    @DisplayName("测试获取已注册 Agent")
    void testGetRegisteredAgents() {
        when(agentOrchestrator.getRegisteredAgentTypes()).thenReturn(List.of("STACK_TRACE", "LOG_ANALYSIS"));

        var response = controller.getRegisteredAgents();

        assertEquals(200, response.getCode());
        assertEquals(2, response.getData().size());
        assertTrue(response.getData().contains("STACK_TRACE"));
    }

    @Test
    @DisplayName("测试健康检查")
    void testHealth() {
        when(agentOrchestrator.getAgentCount()).thenReturn(3);
        when(agentOrchestrator.getRegisteredAgentTypes()).thenReturn(List.of("A", "B", "C"));

        var response = controller.health();

        assertEquals(200, response.getCode());
        assertEquals(3, response.getData().getAgentCount());
        assertEquals("UP", response.getData().getStatus());
    }
}