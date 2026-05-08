package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphNode 单元测试
 */
class GraphNodeTest {

    @Test
    @DisplayName("测试构建器创建 GraphNode")
    void testBuilder() {
        // When
        GraphNode node = GraphNode.builder()
            .id("com.example.Service.method")
            .name("method")
            .className("com.example.Service")
            .depth(2)
            .inCycle(true)
            .callType("DIRECT")
            .signature("method(String)")
            .filePath("/src/Service.java")
            .startLine(10)
            .build();

        // Then
        assertEquals("com.example.Service.method", node.getId());
        assertEquals("method", node.getName());
        assertTrue(node.getInCycle());
        assertEquals("DIRECT", node.getCallType());
    }

    @Test
    @DisplayName("测试无参构造和Setter")
    void testNoArgsConstructor() {
        // Given
        GraphNode node = new GraphNode();

        // When
        node.setId("test-id");
        node.setName("testName");
        node.setInCycle(false);

        // Then
        assertEquals("test-id", node.getId());
        assertFalse(node.getInCycle());
    }
}
