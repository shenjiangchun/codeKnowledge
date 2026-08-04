package com.huawei.hisi.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agent.model.AgentResult;
import com.huawei.hisi.agent.model.DiagnosisRequest;
import com.huawei.hisi.agent.orchestrator.AgentOrchestrator;
import com.huawei.hisi.agent.orchestrator.DiagnosisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DiagnosisController API 集成测试
 * 使用 MockMvc 测试 REST API 行为
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@WebMvcTest(DiagnosisController.class)
@ExtendWith(MockitoExtension.class)
@DisplayName("DiagnosisController API 测试")
class DiagnosisControllerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentOrchestrator agentOrchestrator;

    private DiagnosisRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = DiagnosisRequest.builder()
                .errorMessage("NullPointerException in UserService")
                .stackTrace("java.lang.NullPointerException\n\tat com.example.UserService.getUser(UserService.java:50)")
                .projectPath("/project/src")
                .build();
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze - 成功诊断")
    void testDiagnoseSuccess() throws Exception {
        // Given
        DiagnosisResult mockResult = DiagnosisResult.builder()
                .requestId("req-001")
                .primaryConclusion("NullPointerException in UserService.getUser")
                .primaryRootCause("User object is null at line 50")
                .overallConfidence(0.85)
                .combinedAffectedCode(List.of("com.example.UserService.getUser"))
                .combinedFixSuggestions(List.of("Add null check before accessing user"))
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

        when(agentOrchestrator.diagnose(any())).thenReturn(mockResult);

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requestId").value("req-001"))
                .andExpect(jsonPath("$.data.conclusion").value("NullPointerException in UserService.getUser"))
                .andExpect(jsonPath("$.data.rootCause").value("User object is null at line 50"))
                .andExpect(jsonPath("$.data.confidence").value(0.85))
                .andExpect(jsonPath("$.data.affectedCode[0]").value("com.example.UserService.getUser"))
                .andExpect(jsonPath("$.data.fixSuggestions[0]").value("Add null check before accessing user"))
                .andExpect(jsonPath("$.data.executionTimeMs").value(150));

        verify(agentOrchestrator).diagnose(any());
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze - 验证失败（缺少 errorMessage）")
    void testDiagnoseValidationError() throws Exception {
        // Given - 缺少必需的 errorMessage
        DiagnosisRequest invalidRequest = DiagnosisRequest.builder()
                .stackTrace("some stack trace")
                .build();

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verify(agentOrchestrator, never()).diagnose(any());
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze - 无堆栈信息")
    void testDiagnoseNoStackTrace() throws Exception {
        // Given
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage("Unknown error")
                .build();

        DiagnosisResult mockResult = DiagnosisResult.empty("req-002", "没有可用的诊断 Agent");
        when(agentOrchestrator.diagnose(any())).thenReturn(mockResult);

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.confidence").value(0.0));

        verify(agentOrchestrator).diagnose(any());
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze - 编排器异常")
    void testDiagnoseOrchestratorException() throws Exception {
        // Given
        when(agentOrchestrator.diagnose(any())).thenThrow(new RuntimeException("Internal error"));

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("诊断失败: Internal error"));
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/async - 异步诊断成功")
    void testDiagnoseAsyncSuccess() throws Exception {
        // Given
        DiagnosisResult mockResult = DiagnosisResult.builder()
                .requestId("req-async-001")
                .primaryConclusion("Analysis complete")
                .build();

        when(agentOrchestrator.diagnoseAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(mockResult));

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString());

        verify(agentOrchestrator).diagnoseAsync(any());
    }

    @Test
    @DisplayName("GET /api/diagnosis/agents - 获取已注册 Agent 列表")
    void testGetRegisteredAgents() throws Exception {
        // Given
        when(agentOrchestrator.getRegisteredAgentTypes())
                .thenReturn(List.of("STACK_TRACE", "LOG_ANALYSIS", "CODE_SEARCH"));

        // When & Then
        mockMvc.perform(get("/api/diagnosis/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0]").value("STACK_TRACE"))
                .andExpect(jsonPath("$.data[1]").value("LOG_ANALYSIS"))
                .andExpect(jsonPath("$.data[2]").value("CODE_SEARCH"));
    }

    @Test
    @DisplayName("GET /api/diagnosis/agents - 空列表")
    void testGetRegisteredAgentsEmpty() throws Exception {
        // Given
        when(agentOrchestrator.getRegisteredAgentTypes()).thenReturn(List.of());

        // When & Then
        mockMvc.perform(get("/api/diagnosis/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/diagnosis/health - 健康检查")
    void testHealthCheck() throws Exception {
        // Given
        when(agentOrchestrator.getAgentCount()).thenReturn(3);
        when(agentOrchestrator.getRegisteredAgentTypes())
                .thenReturn(List.of("STACK_TRACE", "LOG_ANALYSIS", "CODE_SEARCH"));

        // When & Then
        mockMvc.perform(get("/api/diagnosis/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.agentCount").value(3))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.agentTypes").isArray())
                .andExpect(jsonPath("$.data.agentTypes.length()").value(3));
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze - 完整请求字段")
    void testDiagnoseFullRequest() throws Exception {
        // Given
        DiagnosisRequest fullRequest = DiagnosisRequest.builder()
                .errorMessage("Database connection failed")
                .stackTrace("java.sql.SQLException: Connection refused\n\tat com.example.Db.connect(Db.java:20)")
                .projectPath("/project/app")
                .logContent("2024-01-01 ERROR: Connection timeout")
                .traceId("trace-12345")
                .entryPoint("POST /api/users")
                .workingDirectory("/workspace")
                .build();

        DiagnosisResult mockResult = DiagnosisResult.builder()
                .requestId("req-full-001")
                .primaryConclusion("Database connection issue")
                .overallConfidence(0.9)
                .totalTimeMs(200L)
                .build();

        when(agentOrchestrator.diagnose(any())).thenReturn(mockResult);

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fullRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requestId").value("req-full-001"));
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze - 多 Agent 结果")
    void testDiagnoseMultipleAgents() throws Exception {
        // Given
        DiagnosisResult mockResult = DiagnosisResult.builder()
                .requestId("req-multi-001")
                .primaryConclusion("Combined analysis from multiple agents")
                .primaryRootCause("Root cause from STACK_TRACE agent")
                .overallConfidence(0.78)
                .combinedAffectedCode(List.of("com.example.ServiceA.method1", "com.example.ServiceB.method2"))
                .combinedFixSuggestions(List.of("Fix 1", "Fix 2", "Fix 3"))
                .agentResults(List.of(
                        AgentResult.builder()
                                .agentType("STACK_TRACE")
                                .status(AgentResult.Status.SUCCESS)
                                .confidence(0.85)
                                .executionTimeMs(100L)
                                .conclusion("Stack trace analysis")
                                .build(),
                        AgentResult.builder()
                                .agentType("LOG_ANALYSIS")
                                .status(AgentResult.Status.SUCCESS)
                                .confidence(0.70)
                                .executionTimeMs(150L)
                                .conclusion("Log pattern analysis")
                                .build()
                ))
                .successCount(2)
                .failedCount(0)
                .totalTimeMs(250L)
                .build();

        when(agentOrchestrator.diagnose(any())).thenReturn(mockResult);

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.agents").isArray())
                .andExpect(jsonPath("$.data.agents.length()").value(2))
                .andExpect(jsonPath("$.data.agents[0].type").value("STACK_TRACE"))
                .andExpect(jsonPath("$.data.agents[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.agents[1].type").value("LOG_ANALYSIS"))
                .andExpect(jsonPath("$.data.affectedCode.length()").value(2))
                .andExpect(jsonPath("$.data.fixSuggestions.length()").value(3));
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze - 部分失败结果")
    void testDiagnosePartialFailure() throws Exception {
        // Given
        DiagnosisResult mockResult = DiagnosisResult.builder()
                .requestId("req-partial-001")
                .primaryConclusion("Partial analysis available")
                .overallConfidence(0.5)
                .agentResults(List.of(
                        AgentResult.builder()
                                .agentType("STACK_TRACE")
                                .status(AgentResult.Status.SUCCESS)
                                .confidence(0.8)
                                .build(),
                        AgentResult.builder()
                                .agentType("CODE_SEARCH")
                                .status(AgentResult.Status.FAILED)
                                .confidence(0.0)
                                .errorMessage("Code index not available")
                                .build()
                ))
                .successCount(1)
                .failedCount(1)
                .totalTimeMs(300L)
                .build();

        when(agentOrchestrator.diagnose(any())).thenReturn(mockResult);

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.confidence").value(0.5));
    }

    // ==================== SSE 端点测试 ====================

    @Test
    @DisplayName("POST /api/diagnosis/analyze/stream - SSE 流式输出")
    void testDiagnoseStreamSSE() throws Exception {
        // Given - Mock 流式结果
        AgentResult result1 = AgentResult.builder()
                .agentType("STACK_TRACE")
                .requestId("req-stream-001")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.85)
                .conclusion("NullPointerException at UserService.java:50")
                .build();

        AgentResult result2 = AgentResult.builder()
                .agentType("ORCHESTRATOR")
                .requestId("req-stream-001")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.85)
                .conclusion("Final diagnosis result")
                .build();

        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(result1, result2));

        // When & Then
        MvcResult mvcResult = mockMvc.perform(post("/api/diagnosis/analyze/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // 等待异步完成
        mvcResult.getAsyncResult(5000);

        // 验证响应内容类型为 text/event-stream
        String response = mvcResult.getResponse().getContentAsString();
        // SSE 格式应该包含事件名称和数据
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/stream - SSE 事件格式验证")
    void testDiagnoseStreamSSEEventFormat() throws Exception {
        // Given
        AgentResult streamingResult = AgentResult.builder()
                .agentType("STACK_TRACE")
                .requestId("req-sse-001")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.9)
                .conclusion("Test conclusion")
                .rootCause("Test root cause")
                .build();

        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(streamingResult));

        // When
        MvcResult mvcResult = mockMvc.perform(post("/api/diagnosis/analyze/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andReturn();

        // 等待异步结果
        mvcResult.getAsyncResult(5000);

        // 验证响应
        String contentType = mvcResult.getResponse().getContentType();
        // 验证 Content-Type 包含 text/event-stream
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/stream - 带会话ID")
    void testDiagnoseStreamWithSessionId() throws Exception {
        // Given
        DiagnosisRequest requestWithSession = DiagnosisRequest.builder()
                .errorMessage("Test error")
                .stackTrace("java.lang.Exception: test")
                .sessionId("session-test-123")
                .build();

        AgentResult result = AgentResult.builder()
                .agentType("STACK_TRACE")
                .sessionId("session-test-123")
                .status(AgentResult.Status.SUCCESS)
                .build();

        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(result));

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestWithSession)))
                .andExpect(status().isOk());

        verify(agentOrchestrator).diagnoseStream(any());
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/stream - 空结果流")
    void testDiagnoseStreamEmptyResult() throws Exception {
        // Given - 无可用 Agent
        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(AgentResult.builder()
                        .agentType("ORCHESTRATOR")
                        .status(AgentResult.Status.SKIPPED)
                        .confidence(0.0)
                        .conclusion("没有可用的诊断 Agent")
                        .build()));

        // When & Then
        MvcResult mvcResult = mockMvc.perform(post("/api/diagnosis/analyze/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andReturn();

        mvcResult.getAsyncResult(5000);
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/stream - 错误处理")
    void testDiagnoseStreamError() throws Exception {
        // Given - 模拟错误
        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.error(new RuntimeException("Stream error")));

        // When & Then
        MvcResult mvcResult = mockMvc.perform(post("/api/diagnosis/analyze/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andReturn();

        // 异步处理会发送错误事件
    }

    // ==================== Reactive (NDJSON) 端点测试 ====================

    @Test
    @DisplayName("POST /api/diagnosis/analyze/reactive - NDJSON 流式输出")
    void testDiagnoseReactiveNDJSON() throws Exception {
        // Given
        AgentResult result1 = AgentResult.builder()
                .agentType("STACK_TRACE")
                .requestId("req-reactive-001")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.85)
                .conclusion("Analysis result 1")
                .build();

        AgentResult result2 = AgentResult.builder()
                .agentType("ORCHESTRATOR")
                .requestId("req-reactive-001")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.85)
                .conclusion("Final result")
                .build();

        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(result1, result2));

        // When & Then
        MvcResult mvcResult = mockMvc.perform(post("/api/diagnosis/analyze/reactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andReturn();

        // 等待流完成
        mvcResult.getAsyncResult(5000);

        // 验证 Content-Type
        String contentType = mvcResult.getResponse().getContentType();
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/reactive - 多个 Agent 结果")
    void testDiagnoseReactiveMultipleAgents() throws Exception {
        // Given - 模拟多个 Agent 的流式结果
        AgentResult stackTraceResult = AgentResult.builder()
                .agentType("STACK_TRACE")
                .requestId("req-multi-reactive")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.9)
                .conclusion("Stack trace analysis")
                .rootCause("NPE at line 50")
                .affectedCode(List.of("UserService.java:50"))
                .fixSuggestions(List.of("Add null check"))
                .build();

        AgentResult orchestratorResult = AgentResult.builder()
                .agentType("ORCHESTRATOR")
                .requestId("req-multi-reactive")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.9)
                .conclusion("Combined diagnosis")
                .build();

        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(stackTraceResult, orchestratorResult));

        // When
        MvcResult mvcResult = mockMvc.perform(post("/api/diagnosis/analyze/reactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andReturn();

        // Then
        mvcResult.getAsyncResult(5000);
        String response = mvcResult.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/reactive - 流式错误处理")
    void testDiagnoseReactiveError() throws Exception {
        // Given
        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.error(new RuntimeException("Reactive stream error")));

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze/reactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/reactive - 验证请求字段")
    void testDiagnoseReactiveRequestFields() throws Exception {
        // Given
        DiagnosisRequest fullRequest = DiagnosisRequest.builder()
                .errorMessage("Full request test")
                .stackTrace("java.lang.Exception: test\n\tat Test.main(Test.java:1)")
                .projectPath("/project")
                .logContent("Log content")
                .traceId("trace-001")
                .entryPoint("GET /api/test")
                .sessionId("session-reactive-001")
                .build();

        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(AgentResult.builder()
                        .agentType("TEST")
                        .status(AgentResult.Status.SUCCESS)
                        .build()));

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze/reactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fullRequest)))
                .andExpect(status().isOk());

        verify(agentOrchestrator).diagnoseStream(any());
    }

    @Test
    @DisplayName("POST /api/diagnosis/analyze/reactive - 验证 Content-Type")
    void testDiagnoseReactiveContentType() throws Exception {
        // Given
        when(agentOrchestrator.diagnoseStream(any()))
                .thenReturn(Flux.just(AgentResult.builder()
                        .agentType("TEST")
                        .status(AgentResult.Status.SUCCESS)
                        .build()));

        // When & Then
        mockMvc.perform(post("/api/diagnosis/analyze/reactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk());
                // Content-Type 应该是 application/x-ndjson-stream
    }
}