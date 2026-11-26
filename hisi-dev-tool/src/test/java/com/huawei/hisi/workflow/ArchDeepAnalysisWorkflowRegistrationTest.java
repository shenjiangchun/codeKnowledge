package com.huawei.hisi.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * arch-deep-analysis 工作流注册单测：类型可查、节点顺序、执行策略 metadata。
 * arch_react_agent 由 pi 节点工厂在流程启动时创建（不再是注册表里的 bean）：
 * 注册只登记、不校验，未知节点在解析时（resolveNodes / v2 控制器组装）才报错。
 */
class ArchDeepAnalysisWorkflowRegistrationTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        // 模拟 registerBuiltIns 引用的全部节点 bean（名称与真实 DagNode.name() 一致）。
        // arch_react_agent 不在其中：它由 ArchReActNodeV2Factory 按需创建。
        registry = new WorkflowRegistry(List.of(
                stubNode("clarify"),
                stubNode("impact"),
                stubNode("implement"),
                stubNode("verify"),
                stubNode("tech_plan"),
                stubNode("project_overview"),
                stubNode("phase2_analysis"),
                stubNode("arch_evidence_collect")));
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
    @DisplayName("节点顺序：证据收集 → ReAct 分析；react 节点由工厂提供，不在注册表里解析")
    void nodeOrderIsEvidenceThenReactAgent() {
        WorkflowDefinition def = registry.getWorkflow("arch-deep-analysis");
        assertThat(def.nodeNames()).containsExactly(
                "arch_evidence_collect", "arch_react_agent");
        // 证据收集节点可从注册表解析；react 节点交给工厂，直接按名解析应明确报错
        assertThat(registry.resolveNodes(List.of("arch_evidence_collect")))
                .extracting(DagNode::name)
                .containsExactly("arch_evidence_collect");
        assertThatThrownBy(() -> registry.resolveNodes(List.of("arch_react_agent")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arch_react_agent");
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
    @DisplayName("注册只登记不校验；未知节点在解析期才抛错（工厂节点的存在使注册期校验不可能）")
    void unknownNodesFailAtResolutionNotAtRegistration() {
        registry.registerWorkflow(new WorkflowDefinition(
                "broken", "坏工作流", "desc", List.of("no_such_node"), java.util.Map.of()));
        assertThat(registry.getWorkflow("broken")).isNotNull();
        assertThatThrownBy(() -> registry.resolveNodes(List.of("no_such_node")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_node");
    }
}
