package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CallChainGraphResponse 单元测试
 */
class CallChainGraphResponseTest {

    @Test
    @DisplayName("测试构建完整的响应对象")
    void testFullResponse() {
        // Given
        List<GraphNode> nodes = List.of(
            GraphNode.builder().id("A").name("methodA").build(),
            GraphNode.builder().id("B").name("methodB").inCycle(true).build()
        );

        List<GraphEdge> edges = List.of(
            GraphEdge.builder().source("A").target("B").callType("DIRECT").build()
        );

        List<CallCycleInfo> cycles = List.of(
            CallCycleInfo.builder().cycleId("c1").cyclePath(List.of("B", "C", "B")).build()
        );

        // When
        CallChainGraphResponse response = CallChainGraphResponse.builder()
            .entryId("entry-001")
            .entryType("HTTP")
            .entryKey("/api/test")
            .maxDepth(5)
            .totalNodes(10)
            .nodes(nodes)
            .edges(edges)
            .cycles(cycles)
            .cycleCount(1)
            .nodesInCycle(Set.of("B", "C"))
            .build();

        // Then
        assertEquals("entry-001", response.getEntryId());
        assertEquals(2, response.getNodes().size());
        assertEquals(1, response.getEdges().size());
        assertEquals(1, response.getCycleCount());
        assertTrue(response.getNodesInCycle().contains("B"));
    }

    @Test
    @DisplayName("测试无环的响应")
    void testNoCyclesResponse() {
        // When
        CallChainGraphResponse response = CallChainGraphResponse.builder()
            .entryId("entry-002")
            .cycleCount(0)
            .nodesInCycle(Set.of())
            .build();

        // Then
        assertEquals(0, response.getCycleCount());
        assertTrue(response.getNodesInCycle().isEmpty());
    }
}
