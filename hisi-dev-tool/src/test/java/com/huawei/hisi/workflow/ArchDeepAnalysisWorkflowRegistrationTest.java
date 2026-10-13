package com.huawei.hisi.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * arch-deep-analysis 工作流注册单测：类型可查、节点 1→2→3 顺序、
 * metadata skipHitl=true（一次性批处理分析，无节点间 HITL 门）。
 * 同时覆盖 registerWorkflow 的 fail-fast 校验（未知节点名在注册期即抛错）。
 */
class ArchDeepAnalysisWorkflowRegistrationTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        // 模拟 registerBuiltIns 引用的全部节点 bean（名称与真实 DagNode.name() 一致）
        registry = new WorkflowRegistry(List.of(
                stubNode("clarify"),
                stubNode("impact"),
                stubNode("implement"),
                stubNode("verify"),
                stubNode("tech_plan"),
                stubNode("project_overview"),
                stubNode("phase2_analysis"),
                stubNode("arch_evidence_collect"),
                stubNode("arch_react_agent")));
        WorkflowAutoConfig.registerBuiltIns(registry);
    }

    private static DagNode stubNode(String name) {
        return new DagNode() {
            @Override public String name() { return name; }
            @Override public String agentId() { return name + "-v1"; }
            @Override public java.util.Map<String, Object> execute(java.util.Map<String, Object> input) {
                return java.util.Map.of();
            }
        };
    }

    @Test
    @DisplayName("注册表可查到 arch-deep-analysis 类型")
    void workflowTypeIsRegistered() {
        WorkflowDefinition def = registry.getWorkflow("arch-deep-analysis");
        assertThat(def).isNotNull();
        assertThat(def.displayName()).isEqualTo("架构深度分析");
    }

    @Test
    @DisplayName("节点顺序：证据收集 → ReAct 分析")
    void nodeOrderIsEvidenceThenReactAgent() {
        WorkflowDefinition def = registry.getWorkflow("arch-deep-analysis");
        assertThat(def.nodeNames()).containsExactly(
                "arch_evidence_collect", "arch_react_agent");
        // resolveNodes 按声明顺序可解析
        assertThat(registry.resolveNodes(def.nodeNames()))
                .extracting(DagNode::name)
                .containsExactly("arch_evidence_collect", "arch_react_agent");
    }

    @Test
    @DisplayName("执行策略：skipHitl=true → 无节点间确认门，且发出节点生命周期事件")
    void executionPolicySkipsHitl() {
        WorkflowDefinition def = registry.getWorkflow("arch-deep-analysis");
        ExecutionPolicy policy = ExecutionPolicy.fromMetadata(def.metadata());
        assertThat(policy.interNodeHitlEnabled()).isFalse();
        assertThat(policy.emitNodeEvents()).isTrue();
    }

    @Test
    @DisplayName("fail-fast：注册引用未知节点的工作流在注册期即抛错")
    void registerWorkflowRejectsUnknownNode() {
        assertThatThrownBy(() -> registry.registerWorkflow(new WorkflowDefinition(
                "broken", "坏工作流", "desc", List.of("no_such_node"), java.util.Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_node");
    }
}
