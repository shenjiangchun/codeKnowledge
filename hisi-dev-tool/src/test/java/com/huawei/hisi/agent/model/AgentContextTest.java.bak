package com.huawei.hisi.agent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentContext 单元测试
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("AgentContext 单元测试")
class AgentContextTest {

    @Test
    @DisplayName("测试 Builder 创建完整上下文")
    void testBuilderCreatesFullContext() {
        AgentContext context = AgentContext.builder()
                .requestId("req-001")
                .projectPath("/project/src")
                .errorMessage("NullPointerException")
                .stackTrace("at com.example.MyClass.myMethod(MyClass.java:10)")
                .logContent("ERROR log content")
                .traceId("trace-123")
                .entryPoint("MyController.handle")
                .build();

        assertEquals("req-001", context.getRequestId());
        assertEquals("/project/src", context.getProjectPath());
        assertEquals("NullPointerException", context.getErrorMessage());
        assertNotNull(context.getStackTrace());
        assertNotNull(context.getCreateTime());
    }

    @Test
    @DisplayName("测试默认值初始化")
    void testDefaultValues() {
        AgentContext context = AgentContext.builder().build();

        assertNotNull(context.getAttributes());
        assertTrue(context.getAttributes().isEmpty());
        assertNotNull(context.getPreviousResults());
        assertTrue(context.getPreviousResults().isEmpty());
        assertNotNull(context.getCreateTime());
    }

    @Test
    @DisplayName("测试 sessionId 字段的 builder/getter/setter")
    void testSessionIdField() {
        // Builder 方式设置 sessionId
        AgentContext context = AgentContext.builder()
                .sessionId("session-12345")
                .build();
        assertEquals("session-12345", context.getSessionId());

        // Setter 方式设置 sessionId
        context.setSessionId("session-67890");
        assertEquals("session-67890", context.getSessionId());

        // Getter 获取 sessionId
        AgentContext context2 = AgentContext.builder().build();
        assertNull(context2.getSessionId());
    }

    @Test
    @DisplayName("测试添加扩展属性")
    void testAddAttribute() {
        AgentContext context = AgentContext.builder().build();

        context.addAttribute("key1", "value1");
        context.addAttribute("key2", 123);

        assertEquals("value1", context.getAttribute("key1"));
        assertEquals(123, context.getAttribute("key2"));
        assertNull(context.getAttribute("nonexistent"));
    }

    @Test
    @DisplayName("测试添加前序结果")
    void testAddPreviousResult() {
        AgentContext context = AgentContext.builder().build();

        AgentResult result1 = AgentResult.builder()
                .agentType("AGENT_1")
                .requestId("req-001")
                .build();

        context.addPreviousResult(result1);

        assertEquals(1, context.getPreviousResults().size());
        assertEquals("AGENT_1", context.getPreviousResults().get(0).getAgentType());
    }

    @Test
    @DisplayName("测试无参构造和全参构造")
    void testConstructors() {
        // 无参构造
        AgentContext context1 = new AgentContext();
        assertNull(context1.getRequestId());

        // 全参构造 - 包含 sessionId 字段 (11个参数)
        AgentContext context2 = new AgentContext(
                "req-002", "/path", "error", "stack",
                "log", "session-id-123", "trace", "entry",
                Map.of("key", "value"),
                List.of(),
                LocalDateTime.now()
        );
        assertEquals("req-002", context2.getRequestId());
        assertEquals("session-id-123", context2.getSessionId());
    }
}