package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CallCycleInfo 单元测试
 */
class CallCycleInfoTest {

    @Test
    @DisplayName("测试构建器创建 CallCycleInfo")
    void testBuilder() {
        // When
        CallCycleInfo cycle = CallCycleInfo.builder()
            .cycleId("cycle-001")
            .cyclePath(List.of("A", "B", "C", "A"))
            .startNodeId("A")
            .cycleLength(3)
            .build();

        // Then
        assertEquals("cycle-001", cycle.getCycleId());
        assertEquals(4, cycle.getCyclePath().size());
        assertEquals("A", cycle.getStartNodeId());
        assertEquals(3, cycle.getCycleLength());
    }

    @Test
    @DisplayName("测试无参构造和Setter")
    void testNoArgsConstructor() {
        // Given
        CallCycleInfo cycle = new CallCycleInfo();

        // When
        cycle.setCycleId("cycle-002");
        cycle.setCyclePath(List.of("X", "Y", "X"));
        cycle.setStartNodeId("X");
        cycle.setCycleLength(2);

        // Then
        assertEquals("cycle-002", cycle.getCycleId());
        assertEquals(3, cycle.getCyclePath().size());
    }

    @Test
    @DisplayName("测试全参构造")
    void testAllArgsConstructor() {
        // When
        CallCycleInfo cycle = new CallCycleInfo(
            "cycle-003",
            List.of("P", "Q", "R", "P"),
            "P",
            3
        );

        // Then
        assertEquals("cycle-003", cycle.getCycleId());
        assertEquals("P", cycle.getStartNodeId());
    }
}
