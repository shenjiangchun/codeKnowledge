package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphEdge 单元测试
 */
class GraphEdgeTest {

    @Test
    @DisplayName("测试构建器创建 GraphEdge")
    void testBuilder() {
        // When
        GraphEdge edge = GraphEdge.builder()
            .source("nodeA")
            .target("nodeB")
            .callType("INTERFACE")
            .callLine(25)
            .isCycleEdge(true)
            .build();

        // Then
        assertEquals("nodeA", edge.getSource());
        assertEquals("nodeB", edge.getTarget());
        assertEquals("INTERFACE", edge.getCallType());
        assertTrue(edge.getIsCycleEdge());
    }

    @Test
    @DisplayName("测试普通边（非环边）")
    void testNormalEdge() {
        // When
        GraphEdge edge = GraphEdge.builder()
            .source("A")
            .target("B")
            .callType("DIRECT")
            .isCycleEdge(false)
            .build();

        // Then
        assertFalse(edge.getIsCycleEdge());
    }
}
