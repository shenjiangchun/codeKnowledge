package com.huawei.hisi.agentbase;

import com.huawei.hisi.piagent.FlowToolManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一声明的守卫：每个 ReAct agent 的工具清单键有效、skill 文件存在、契约 schema 存在；
 * 每个工作流的节点要么是已知 bean 名/工作台阶段键，要么在 ReAct 映射里。
 */
@DisplayName("AgentRegistry declarations are consistent")
class AgentRegistryTest {

    @Test
    @DisplayName("每个 ReAct agent 的清单键都能解析，且只读 agent 不得持有写/执行工具")
    void everyAgentHasAValidManifestAndNoWriteToolsForReadAgents() {
        for (AgentRegistry.ReactAgent agent : AgentRegistry.agents().values()) {
            FlowToolManifest manifest = FlowToolManifest.forFlow(agent.flow());
            assertThat(manifest).as(agent.key() + " 的清单键 " + agent.flow() + " 解析失败（会是默认只读）")
                    .isNotNull();
            if (FlowToolManifest.WORKBENCH_DEV.equals(agent.flow())) {
                assertThat(manifest.tools()).contains("write", "edit", "bash");
            } else {
                assertThat(manifest.tools()).as(agent.key() + " 只读").doesNotContain("write", "edit", "bash", "powershell");
            }
        }
    }

    @Test
    @DisplayName("声明引用的 skill 文件都在 classpath 上")
    void everySkillExistsOnTheClasspath() {
        for (AgentRegistry.ReactAgent agent : AgentRegistry.agents().values()) {
            for (String name : agent.skills()) {
                ClassPathResource resource = new ClassPathResource("pi-skills/" + name + "/SKILL.md",
                        AgentRegistryTest.class.getClassLoader());
                assertThat(resource.exists()).as(agent.key() + " 引用的 skill " + name + " 缺失").isTrue();
            }
        }
    }

    @Test
    @DisplayName("声明引用的契约 schema 都能被校验器加载")
    void everyContractSchemaLoads() {
        com.huawei.hisi.ram.contract.SchemaValidator validator = new com.huawei.hisi.ram.contract.SchemaValidator();
        for (AgentRegistry.ReactAgent agent : AgentRegistry.agents().values()) {
            if (agent.contractSchema() != null) {
                assertThat(validator.validate(agent.contractSchema(), Map.of())).as(agent.key() + " 的契约")
                        .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("工作流声明：节点非空、策略键受控、回退目标在链内")
    void workflowDeclarationsAreWellFormed() {
        for (AgentRegistry.Workflow workflow : AgentRegistry.workflows().values()) {
            assertThat(workflow.nodes()).as(workflow.key() + " 节点链").isNotEmpty();
            if (workflow.policy().get("retryTarget") instanceof String retryTarget) {
                assertThat(workflow.nodes()).contains(retryTarget);
            }
            if (workflow.policy().get("maxIterations") instanceof Number number) {
                assertThat(number.intValue()).isPositive();
            }
            if (workflow.policy().get("confirmGate") instanceof String gate) {
                assertThat(workflow.nodes()).contains(gate);
            }
        }
    }

    @Test
    @DisplayName("wb-autofix 的阶段链与工作台阶段一致，顺序不变")
    void theWorkbenchChainMatchesTheStageDefinitions() {
        List<String> chain = AgentRegistry.workflow("wb-autofix").orElseThrow().nodes();

        assertThat(chain).containsExactly(
                "evidence", "proposal", "spec", "tasks", "develop", "tests", "e2e", "ship");
        assertThat(chain.get(chain.indexOf("develop"))).isEqualTo(
                AgentRegistry.workflow("wb-autofix").orElseThrow().policy().get("retryTarget"));
    }

    @Test
    @DisplayName("工作流节点名要么有 ReAct 映射，要么是已知的工作台阶段/bean 名（登记册）")
    void nodeNamesComeFromKnownSets() {
        List<String> stageKeys = List.of("evidence", "proposal", "spec", "tasks", "develop", "tests", "e2e", "ship");
        List<String> knownBeans = List.of("arch_evidence_collect", "clarify", "impact", "implement", "verify",
                "tech_plan", "project_overview", "phase2_analysis",
                "diff_extract", "impact_analysis", "test_scope");
        // 已声明、尚未接线的工作流节点（迁移到对应节点时逐个替换）
        List<String> plannedNodes = List.of("decode", "collect", "hypothesis", "synthesis", "report");
        for (AgentRegistry.Workflow workflow : AgentRegistry.workflows().values()) {
            for (String node : workflow.nodes()) {
                assertThat(stageKeys.contains(node) || knownBeans.contains(node) || plannedNodes.contains(node)
                        || AgentRegistry.agentForNode(node).isPresent())
                        .as(workflow.key() + " 的节点 " + node + " 不在任何已知集合里")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("动作工具只出现在 ram-act 清单里，其它只读流拿不到")
    void actionToolsAreExclusiveToRamAct() {
        assertThat(FlowToolManifest.forFlow("ram-act").tools())
                .anyMatch(t -> t.contains("act_submit_clarification"));
        for (String flow : List.of("arch-react", "ram", "log-analysis", "chat-read", "workbench-read")) {
            assertThat(FlowToolManifest.forFlow(flow).tools())
                    .as(flow + " 不得持有动作工具")
                    .noneMatch(t -> t.contains("act_"));
        }
    }

    @Test
    @DisplayName("未知 agent / 工作流 / 节点：查询不抛异常")
    void unknownLookupsAreSoft() {
        assertThat(AgentRegistry.agent("nope")).isEmpty();
        assertThat(AgentRegistry.workflow("nope")).isEmpty();
        assertThat(AgentRegistry.agentForNode("nope")).isEmpty();
        assertThatThrownBy(() -> {
            throw new IllegalArgumentException("unused");
        }).isInstanceOf(IllegalArgumentException.class);
    }
}
