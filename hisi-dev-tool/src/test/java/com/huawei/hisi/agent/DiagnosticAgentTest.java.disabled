package com.huawei.hisi.agent;

import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiagnosticAgent 接口测试
 * 测试接口默认方法行为
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("DiagnosticAgent 接口测试")
class DiagnosticAgentTest {

    /**
     * 测试用的简单 Agent 实现
     */
    private static class TestAgent implements DiagnosticAgent {
        @Override
        public String getAgentType() {
            return "TEST_AGENT";
        }

        @Override
        public String getAgentName() {
            return "测试 Agent";
        }

        @Override
        public double calculateConfidence(AgentContext context) {
            return context.getStackTrace() != null ? 0.8 : 0.2;
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .agentType(getAgentType())
                    .requestId(context.getRequestId())
                    .status(AgentResult.Status.SUCCESS)
                    .confidence(0.8)
                    .build();
        }

        @Override
        public int getPriority() {
            return 50;  // 更高优先级
        }
    }

    /**
     * 带依赖的 Agent 实现
     */
    private static class DependentAgent implements DiagnosticAgent {
        @Override
        public String getAgentType() {
            return "DEPENDENT_AGENT";
        }

        @Override
        public String getAgentName() {
            return "依赖 Agent";
        }

        @Override
        public double calculateConfidence(AgentContext context) {
            return 0.7;
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .agentType(getAgentType())
                    .requestId(context.getRequestId())
                    .status(AgentResult.Status.SUCCESS)
                    .confidence(0.7)
                    .build();
        }

        @Override
        public List<String> getDependencies() {
            return List.of("TEST_AGENT");
        }
    }

    @Test
    @DisplayName("测试默认优先级")
    void testDefaultPriority() {
        DiagnosticAgent defaultAgent = new DiagnosticAgent() {
            @Override
            public String getAgentType() { return "DEFAULT"; }
            @Override
            public String getAgentName() { return "Default Agent"; }
            @Override
            public double calculateConfidence(AgentContext context) { return 0.5; }
            @Override
            public AgentResult execute(AgentContext context) {
                return AgentResult.builder().build();
            }
        };

        assertEquals(100, defaultAgent.getPriority());
    }

    @Test
    @DisplayName("测试自定义优先级")
    void testCustomPriority() {
        TestAgent agent = new TestAgent();
        assertEquals(50, agent.getPriority());
    }

    @Test
    @DisplayName("测试 canSkip 默认行为")
    void testDefaultCanSkip() {
        TestAgent agent = new TestAgent();

        // 置信度 < 0.3 应跳过
        assertTrue(agent.canSkip(0.1));
        assertTrue(agent.canSkip(0.25));

        // 置信度 >= 0.3 不应跳过
        assertFalse(agent.canSkip(0.3));
        assertFalse(agent.canSkip(0.5));
        assertFalse(agent.canSkip(0.8));
    }

    @Test
    @DisplayName("测试默认依赖列表为空")
    void testDefaultDependencies() {
        TestAgent agent = new TestAgent();
        List<String> dependencies = agent.getDependencies();

        assertNotNull(dependencies);
        assertTrue(dependencies.isEmpty());
    }

    @Test
    @DisplayName("测试自定义依赖列表")
    void testCustomDependencies() {
        DependentAgent agent = new DependentAgent();
        List<String> dependencies = agent.getDependencies();

        assertNotNull(dependencies);
        assertEquals(1, dependencies.size());
        assertEquals("TEST_AGENT", dependencies.get(0));
    }

    @Test
    @DisplayName("测试置信度计算逻辑")
    void testCalculateConfidence() {
        TestAgent agent = new TestAgent();

        // 有堆栈时置信度高
        AgentContext contextWithStack = AgentContext.builder()
                .requestId("req-001")
                .stackTrace("at com.example.Test.method(Test.java:10)")
                .build();
        assertEquals(0.8, agent.calculateConfidence(contextWithStack));

        // 无堆栈时置信度低
        AgentContext contextWithoutStack = AgentContext.builder()
                .requestId("req-002")
                .build();
        assertEquals(0.2, agent.calculateConfidence(contextWithoutStack));
    }

    @Test
    @DisplayName("测试 execute 方法")
    void testExecute() {
        TestAgent agent = new TestAgent();
        AgentContext context = AgentContext.builder()
                .requestId("req-001")
                .stackTrace("test stack trace")
                .build();

        AgentResult result = agent.execute(context);

        assertEquals("TEST_AGENT", result.getAgentType());
        assertEquals("req-001", result.getRequestId());
        assertEquals(AgentResult.Status.SUCCESS, result.getStatus());
    }
}