package com.huawei.hisi.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行策略单测：默认策略保持既有 HITL 门行为；metadata 派生策略可关闭门。
 * 既有调用方（RAM / MergeAnalysis / 3-arg run）零改动、零行为变化由本类与
 * {@code DagExecutorTest}（真实 SQLite 集成）共同保障。
 */
class ExecutionPolicyTest {

    @Test
    @DisplayName("默认策略：节点间 HITL 门开启（既有行为）")
    void defaultPolicyKeepsHitlGate() {
        assertThat(ExecutionPolicy.DEFAULT.interNodeHitlEnabled()).isTrue();
    }

    @Test
    @DisplayName("metadata skipHitl=true：派生策略关闭 HITL 门")
    void metadataSkipHitlDisablesGate() {
        ExecutionPolicy p = ExecutionPolicy.fromMetadata(Map.of("skipHitl", true));
        assertThat(p.interNodeHitlEnabled()).isFalse();
    }

    @Test
    @DisplayName("metadata 无 skipHitl：派生策略等价默认（门开）")
    void metadataWithoutSkipHitlEqualsDefault() {
        assertThat(ExecutionPolicy.fromMetadata(Map.of())).isEqualTo(ExecutionPolicy.DEFAULT);
        assertThat(ExecutionPolicy.fromMetadata(null)).isEqualTo(ExecutionPolicy.DEFAULT);
        // 显式 false 也保持门开
        assertThat(ExecutionPolicy.fromMetadata(Map.of("skipHitl", false)).interNodeHitlEnabled()).isTrue();
    }
}
