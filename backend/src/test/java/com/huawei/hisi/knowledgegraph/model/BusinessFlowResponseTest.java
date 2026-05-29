package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BusinessFlowResponse 单元测试
 */
class BusinessFlowResponseTest {

    @Test
    @DisplayName("创建响应 - Builder 模式")
    void testBuilder() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        List<BusinessFlowResponse.FlowStep> steps = List.of(
                BusinessFlowResponse.FlowStep.builder()
                        .order(1)
                        .name("Step 1")
                        .description("Description 1")
                        .build()
        );
        List<BusinessFlowResponse.KeyNode> keyNodes = List.of(
                BusinessFlowResponse.KeyNode.builder()
                        .nodeId("node-1")
                        .name("Key Node 1")
                        .type("DATABASE")
                        .build()
        );

        // When
        BusinessFlowResponse response = BusinessFlowResponse.builder()
                .requestId("req-123")
                .mermaidDiagram("graph TD\nA --> B")
                .description("Test description")
                .steps(steps)
                .keyNodes(keyNodes)
                .generatedAt(now)
                .success(true)
                .build();

        // Then
        assertNotNull(response);
        assertEquals("req-123", response.getRequestId());
        assertEquals("graph TD\nA --> B", response.getMermaidDiagram());
        assertEquals("Test description", response.getDescription());
        assertEquals(1, response.getSteps().size());
        assertEquals(1, response.getKeyNodes().size());
        assertEquals(now, response.getGeneratedAt());
        assertTrue(response.getSuccess());
        assertNull(response.getErrorMessage());
    }

    @Test
    @DisplayName("创建响应 - 默认值")
    void testDefaultValues() {
        // Given & When
        BusinessFlowResponse response = BusinessFlowResponse.builder().build();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
    }

    @Test
    @DisplayName("创建响应 - 失败响应")
    void testErrorResponse() {
        // Given & When
        BusinessFlowResponse response = BusinessFlowResponse.builder()
                .requestId("req-456")
                .success(false)
                .errorMessage("Something went wrong")
                .build();

        // Then
        assertFalse(response.getSuccess());
        assertEquals("Something went wrong", response.getErrorMessage());
    }

    @Test
    @DisplayName("测试 FlowStep 内部类")
    void testFlowStep() {
        // Given & When
        BusinessFlowResponse.FlowStep step = BusinessFlowResponse.FlowStep.builder()
                .order(1)
                .name("Test Step")
                .description("Step description")
                .component("TestComponent")
                .operationType("DATABASE")
                .isKeyStep(true)
                .build();

        // Then
        assertEquals(1, step.getOrder());
        assertEquals("Test Step", step.getName());
        assertEquals("Step description", step.getDescription());
        assertEquals("TestComponent", step.getComponent());
        assertEquals("DATABASE", step.getOperationType());
        assertTrue(step.getIsKeyStep());
    }

    @Test
    @DisplayName("测试 KeyNode 内部类")
    void testKeyNode() {
        // Given & When
        BusinessFlowResponse.KeyNode keyNode = BusinessFlowResponse.KeyNode.builder()
                .nodeId("key-node-1")
                .name("Database Node")
                .type("DATABASE")
                .description("Primary database")
                .riskLevel("HIGH")
                .build();

        // Then
        assertEquals("key-node-1", keyNode.getNodeId());
        assertEquals("Database Node", keyNode.getName());
        assertEquals("DATABASE", keyNode.getType());
        assertEquals("Primary database", keyNode.getDescription());
        assertEquals("HIGH", keyNode.getRiskLevel());
    }

    @Test
    @DisplayName("创建响应 - 无参构造函数")
    void testNoArgsConstructor() {
        // Given & When
        BusinessFlowResponse response = new BusinessFlowResponse();
        response.setRequestId("test-id");

        // Then
        assertEquals("test-id", response.getRequestId());
    }

    @Test
    @DisplayName("创建响应 - 全参构造函数")
    void testAllArgsConstructor() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        List<BusinessFlowResponse.FlowStep> steps = List.of();
        List<BusinessFlowResponse.KeyNode> keyNodes = List.of();

        // When
        BusinessFlowResponse response = new BusinessFlowResponse(
                "req-id",
                "mermaid code",
                "description",
                steps,
                keyNodes,
                now,
                true,
                null
        );

        // Then
        assertEquals("req-id", response.getRequestId());
        assertEquals("mermaid code", response.getMermaidDiagram());
        assertEquals("description", response.getDescription());
        assertEquals(now, response.getGeneratedAt());
        assertTrue(response.getSuccess());
    }

    @Test
    @DisplayName("测试 equals 和 hashCode")
    void testEqualsAndHashCode() {
        // Given
        BusinessFlowResponse response1 = BusinessFlowResponse.builder()
                .requestId("req-1")
                .success(true)
                .build();

        BusinessFlowResponse response2 = BusinessFlowResponse.builder()
                .requestId("req-1")
                .success(true)
                .build();

        // Then
        assertEquals(response1, response2);
        assertEquals(response1.hashCode(), response2.hashCode());
    }

    @Test
    @DisplayName("测试 toString")
    void testToString() {
        // Given
        BusinessFlowResponse response = BusinessFlowResponse.builder()
                .requestId("req-123")
                .success(true)
                .build();

        // When
        String str = response.toString();

        // Then
        assertNotNull(str);
        assertTrue(str.contains("req-123"));
    }
}
