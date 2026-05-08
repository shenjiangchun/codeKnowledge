package com.huawei.hisi.agent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentEvent 单元测试
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("AgentEvent 单元测试")
class AgentEventTest {

    @Test
    @DisplayName("测试 Builder 创建事件")
    void testBuilderCreatesEvent() {
        AgentEvent event = AgentEvent.builder()
                .requestId("req-001")
                .eventType(AgentEvent.EventType.AGENT_STARTED)
                .agentType("STACK_TRACE")
                .message("Agent STACK_TRACE started")
                .phase("解析阶段")
                .progress(50)
                .confidence(0.8)
                .build();

        assertEquals("req-001", event.getRequestId());
        assertEquals(AgentEvent.EventType.AGENT_STARTED, event.getEventType());
        assertEquals("STACK_TRACE", event.getAgentType());
        assertEquals("解析阶段", event.getPhase());
        assertEquals(50, event.getProgress());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @DisplayName("测试静态工厂方法 - requestReceived")
    void testStaticFactoryRequestReceived() {
        AgentEvent event = AgentEvent.requestReceived("req-001");

        assertEquals("req-001", event.getRequestId());
        assertEquals(AgentEvent.EventType.REQUEST_RECEIVED, event.getEventType());
        assertEquals("诊断请求已接收", event.getMessage());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @DisplayName("测试静态工厂方法 - agentStarted")
    void testStaticFactoryAgentStarted() {
        AgentEvent event = AgentEvent.agentStarted("req-001", "STACK_TRACE", "解析阶段");

        assertEquals("req-001", event.getRequestId());
        assertEquals(AgentEvent.EventType.AGENT_STARTED, event.getEventType());
        assertEquals("STACK_TRACE", event.getAgentType());
        assertEquals("解析阶段", event.getPhase());
        assertTrue(event.getMessage().contains("STACK_TRACE"));
        assertTrue(event.getMessage().contains("开始执行"));
    }

    @Test
    @DisplayName("测试静态工厂方法 - agentCompleted")
    void testStaticFactoryAgentCompleted() {
        AgentEvent event = AgentEvent.agentCompleted("req-001", "STACK_TRACE", 0.85);

        assertEquals("req-001", event.getRequestId());
        assertEquals(AgentEvent.EventType.AGENT_COMPLETED, event.getEventType());
        assertEquals("STACK_TRACE", event.getAgentType());
        assertEquals(0.85, event.getConfidence());
        assertTrue(event.getMessage().contains("执行完成"));
    }

    @Test
    @DisplayName("测试静态工厂方法 - agentFailed")
    void testStaticFactoryAgentFailed() {
        AgentEvent event = AgentEvent.agentFailed("req-001", "STACK_TRACE", "Connection timeout");

        assertEquals("req-001", event.getRequestId());
        assertEquals(AgentEvent.EventType.AGENT_FAILED, event.getEventType());
        assertEquals("STACK_TRACE", event.getAgentType());
        assertTrue(event.getMessage().contains("执行失败"));
        assertTrue(event.getMessage().contains("Connection timeout"));
    }

    @Test
    @DisplayName("测试静态工厂方法 - orchestrationStart")
    void testStaticFactoryOrchestrationStart() {
        AgentEvent event = AgentEvent.orchestrationStart("req-001");

        assertEquals("req-001", event.getRequestId());
        assertEquals(AgentEvent.EventType.ORCHESTRATION_START, event.getEventType());
        assertEquals("初始化", event.getPhase());
        assertEquals("多Agent诊断编排开始", event.getMessage());
    }

    @Test
    @DisplayName("测试静态工厂方法 - orchestrationEnd")
    void testStaticFactoryOrchestrationEnd() {
        AgentEvent event = AgentEvent.orchestrationEnd("req-001");

        assertEquals("req-001", event.getRequestId());
        assertEquals(AgentEvent.EventType.ORCHESTRATION_END, event.getEventType());
        assertEquals("多Agent诊断编排结束", event.getMessage());
    }

    @Test
    @DisplayName("测试事件类型枚举")
    void testEventTypeEnum() {
        assertEquals(9, AgentEvent.EventType.values().length);

        // 验证所有枚举值存在
        assertNotNull(AgentEvent.EventType.valueOf("REQUEST_RECEIVED"));
        assertNotNull(AgentEvent.EventType.valueOf("AGENT_STARTED"));
        assertNotNull(AgentEvent.EventType.valueOf("AGENT_PROGRESS"));
        assertNotNull(AgentEvent.EventType.valueOf("AGENT_COMPLETED"));
        assertNotNull(AgentEvent.EventType.valueOf("AGENT_FAILED"));
        assertNotNull(AgentEvent.EventType.valueOf("AGENT_SKIPPED"));
        assertNotNull(AgentEvent.EventType.valueOf("ORCHESTRATION_START"));
        assertNotNull(AgentEvent.EventType.valueOf("ORCHESTRATION_END"));
        assertNotNull(AgentEvent.EventType.valueOf("FINAL_RESULT"));
    }

    @Test
    @DisplayName("测试默认时间戳")
    void testDefaultTimestamp() {
        AgentEvent event = AgentEvent.builder()
                .requestId("req-001")
                .eventType(AgentEvent.EventType.AGENT_STARTED)
                .build();

        assertNotNull(event.getTimestamp());
    }
}