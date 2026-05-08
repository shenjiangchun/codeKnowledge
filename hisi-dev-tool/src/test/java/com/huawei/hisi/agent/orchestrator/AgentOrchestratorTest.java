package com.huawei.hisi.agent.orchestrator;

import com.huawei.hisi.agent.DiagnosticAgent;
import com.huawei.hisi.agent.event.AgentEventPublisher;
import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AgentOrchestrator 单元测试
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentOrchestrator 单元测试")
class AgentOrchestratorTest {

    @Mock
    private AgentEventPublisher eventPublisher;

    private List<DiagnosticAgent> agents;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // 创建测试 Agent
        agents = new ArrayList<>();

        // 高优先级 Agent
        agents.add(new TestAgent("HIGH_PRIORITY", 10, 0.8));

        // 中优先级 Agent
        agents.add(new TestAgent("MEDIUM_PRIORITY", 50, 0.6));

        // 低优先级 Agent（会被跳过）
        agents.add(new TestAgent("LOW_CONFIDENCE", 100, 0.2));

        orchestrator = new AgentOrchestrator(eventPublisher, agents);
    }

    @Test
    @DisplayName("测试编排器初始化")
    void testInitialization() {
        assertEquals(3, orchestrator.getAgentCount());
        List<String> types = orchestrator.getRegisteredAgentTypes();
        assertTrue(types.contains("HIGH_PRIORITY"));
        assertTrue(types.contains("MEDIUM_PRIORITY"));
        assertTrue(types.contains("LOW_CONFIDENCE"));
    }

    @Test
    @DisplayName("测试诊断执行 - 正常流程")
    void testDiagnoseNormalFlow() {
        AgentContext context = AgentContext.builder()
                .requestId("req-001")
                .errorMessage("Test error")
                .stackTrace("at com.example.Test.method(Test.java:10)")
                .build();

        DiagnosisResult result = orchestrator.diagnose(context);

        assertNotNull(result);
        assertEquals("req-001", result.getRequestId());
        assertTrue(result.getSuccessCount() >= 1);  // 至少一个 Agent 成功
        assertTrue(result.getTotalTimeMs() > 0);
    }

    @Test
    @DisplayName("测试 Agent 置信度筛选")
    void testAgentConfidenceFiltering() {
        // 上下文会导致 LOW_CONFIDENCE Agent 被跳过
        AgentContext context = AgentContext.builder()
                .requestId("req-002")
                .errorMessage("Test error")
                .stackTrace("test stack trace")
                .build();

        DiagnosisResult result = orchestrator.diagnose(context);

        // LOW_CONFIDENCE Agent 应被跳过（置信度 0.2 < 0.3）
        assertTrue(result.getSuccessCount() <= 2);
    }

    @Test
    @DisplayName("测试无 Agent 时的空结果")
    void testEmptyResultWhenNoAgents() {
        AgentOrchestrator emptyOrchestrator = new AgentOrchestrator(eventPublisher, null);

        AgentContext context = AgentContext.builder()
                .requestId("req-empty")
                .build();

        DiagnosisResult result = emptyOrchestrator.diagnose(context);

        assertEquals(0, result.getSuccessCount());
        assertFalse(result.hasValidConclusion());
    }

    @Test
    @DisplayName("测试异步诊断")
    void testDiagnoseAsync() {
        AgentContext context = AgentContext.builder()
                .requestId("req-async")
                .errorMessage("Async test")
                .build();

        var future = orchestrator.diagnoseAsync(context);

        assertNotNull(future);
        DiagnosisResult result = future.join();
        assertNotNull(result);
    }

    @Test
    @DisplayName("测试结果聚合")
    void testResultAggregation() {
        AgentContext context = AgentContext.builder()
                .requestId("req-agg")
                .errorMessage("Aggregation test")
                .stackTrace("test stack trace")
                .build();

        DiagnosisResult result = orchestrator.diagnose(context);

        // 验证聚合结果
        assertNotNull(result.getPrimaryConclusion());
        assertNotNull(result.getAgentResults());
        assertFalse(result.getAgentResults().isEmpty());
    }

    @Test
    @DisplayName("测试事件发布")
    void testEventPublishing() {
        AgentContext context = AgentContext.builder()
                .requestId("req-event")
                .errorMessage("Event test")
                .stackTrace("test")
                .build();

        orchestrator.diagnose(context);

        // 验证事件被发布（至少编排开始和结束事件）
        verify(eventPublisher, atLeast(2)).publishEventAsync(any());
    }

    /**
     * 测试用的简单 Agent 实现
     */
    private static class TestAgent implements DiagnosticAgent {
        private final String type;
        private final int priority;
        private final double fixedConfidence;

        TestAgent(String type, int priority, double fixedConfidence) {
            this.type = type;
            this.priority = priority;
            this.fixedConfidence = fixedConfidence;
        }

        @Override
        public String getAgentType() {
            return type;
        }

        @Override
        public String getAgentName() {
            return "Test Agent " + type;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public double calculateConfidence(AgentContext context) {
            return fixedConfidence;
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .agentType(type)
                    .requestId(context.getRequestId())
                    .status(AgentResult.Status.SUCCESS)
                    .confidence(fixedConfidence)
                    .conclusion("Conclusion from " + type)
                    .rootCause("Root cause from " + type)
                    .executionTimeMs(50L)
                    .build();
        }
    }
}