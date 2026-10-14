package com.huawei.hisi.workflow.nodes;

import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.archdiagnosis.service.ArchSourceScanService;
import com.huawei.hisi.knowledgegraph.aggregation.llm.DeepseekJsonClient;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import com.huawei.hisi.workflow.agent.ObservationTrimmer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 智能体节点单测：手动 ReAct 循环（推理→调工具→观察→收敛）、
 * final_answer 过 react-step schema 校验后输出结构化报告（执行摘要 + 4+1 视图 + 路线图）、
 * 图生成器产物透传、连续失败 / maxSteps 耗尽降级为模板报告、缺 evidencePack 快速失败。
 */
@ExtendWith(MockitoExtension.class)
class ArchReActAgentNodeTest {

    private static final String VALID_FINAL_ANSWER_JSON = """
            {
              "thought": "证据充分，输出结论",
              "final_answer": {
                "issues": [
                  {"ref": "i-1", "title": "循环依赖", "severity": "HIGH",
                   "rootCause": "controller 直接依赖 repository",
                   "evidenceRefs": ["module.cycles"], "suggestion": "引入接口层",
                   "verdict": "confirmed", "reason": "跨层循环属实"}
                ],
                "report": {
                  "executive_summary": "整体健康度一般，核心风险是模块循环依赖",
                  "views": {
                    "logical": {"insight": "分层清晰，controller→service→repository", "diagramRefs": ["layered_architecture"]},
                    "process": {"insight": "同步调用链，无异步", "diagramRefs": ["key_call_chain"]},
                    "development": {"insight": "按模块分包", "diagramRefs": ["dsm_matrix"]},
                    "physical": {"insight": "单服务部署", "diagramRefs": ["deployment_topology"]},
                    "scenario": {"insight": "订单创建主链路", "diagramRefs": []}
                  },
                  "roadmap": {
                    "p0": [{"action": "拆解循环依赖", "impact": "降低耦合", "benefit": "可维护性", "risk": "改动面大"}],
                    "p1": [],
                    "p2": []
                  },
                  "evidence_appendix": "证据 A"
                }
              }
            }
            """;

    private static final String VALID_ACTION_JSON = """
            {"thought": "需要先看项目全景", "action": {"tool": "dashboard", "args": {}}}
            """;

    /** 与 VALID_FINAL_ANSWER_JSON 中 diagramRefs 引用的 4 个图 id 逐一匹配的图产物。 */
    private static final List<Map<String, Object>> VALID_DIAGRAMS = List.of(
            Map.of("id", "layered_architecture", "name", "分层架构图", "mermaid", "flowchart TB"),
            Map.of("id", "key_call_chain", "name", "关键调用链", "mermaid", "flowchart TD"),
            Map.of("id", "dsm_matrix", "name", "模块依赖矩阵", "mermaid", "flowchart LR"),
            Map.of("id", "deployment_topology", "name", "部署拓扑", "mermaid", "flowchart TB"));

    @Mock
    private DeepseekJsonClient llm;
    @Mock
    private KnowledgeGraphController kg;
    @Mock
    private ArchDiagramGenerator diagramGenerator;

    private ArchAgentToolRegistry registry;
    private ArchReActAgentNode node;

    @BeforeEach
    void setUp() {
        registry = new ArchAgentToolRegistry(kg, new ArchSourceScanService());
        node = new ArchReActAgentNode(llm, registry, new ObservationTrimmer(), new SchemaValidator(),
                diagramGenerator, 6, 2000);
        lenient().when(diagramGenerator.generate(anyMap(), anyList())).thenReturn(VALID_DIAGRAMS);
    }

    private static Map<String, Object> evidencePack() {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("projectPaths", List.of("p1"));
        pack.put("healthScore", 72);
        pack.put("grade", "C");
        pack.put("issues", List.of());
        return pack;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rootCauseResult(Map<String, Object> out) {
        return (Map<String, Object>) out.get("rootCauseResult");
    }

    @Test
    @DisplayName("节点元数据：name/agentId")
    void nodeMetadata() {
        assertThat(node.name()).isEqualTo("arch_react_agent");
        assertThat(node.agentId()).isNotBlank();
    }

    @Test
    @DisplayName("final_answer 直接收敛：输出结构化报告 + 根因列表")
    void finalAnswerConverges() {
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(VALID_FINAL_ANSWER_JSON);

        Map<String, Object> out = node.execute(Map.of("evidencePack", evidencePack()));

        assertThat(out.get("degraded")).isEqualTo(false);
        assertThat((String) out.get("markdownReport"))
                .contains("## 执行摘要")
                .contains("## 逻辑视图").contains("## 过程视图")
                .contains("## 开发视图").contains("## 物理视图")
                .contains("## 场景视图")
                .contains("## 重构路线图")
                .contains("## 证据附录");
        Map<String, Object> rc = rootCauseResult(out);
        assertThat((List<?>) rc.get("issues")).hasSize(1);
        assertThat(rc.get("degraded")).isEqualTo(false);
        assertThat(rc.get("report")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("图生成器产物透传到输出 diagrams")
    void diagramsPassedThrough() {
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(VALID_FINAL_ANSWER_JSON);

        Map<String, Object> out = node.execute(Map.of("evidencePack", evidencePack()));

        assertThat((List<?>) out.get("diagrams")).hasSize(4);
    }

    @Test
    @DisplayName("灰度验证：final_answer.issues 的 verdict/reason 透传到 rootCauseResult")
    void verdictPassesThroughToRootCause() {
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(VALID_FINAL_ANSWER_JSON);

        Map<String, Object> out = node.execute(Map.of("evidencePack", evidencePack()));

        List<?> issues = (List<?>) rootCauseResult(out).get("issues");
        assertThat(issues).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) issues.get(0);
        assertThat(first)
                .containsEntry("verdict", "confirmed")
                .containsEntry("reason", "跨层循环属实");
    }

    @Test
    @DisplayName("缺 verdict/reason 的 issue 未过 schema 校验 → 降级（灰度字段强制）")
    void missingVerdictFailsSchemaAndDegrades() {
        String json = """
                {"thought":"总结","final_answer":{"issues":[
                  {"ref":"i-1","title":"循环依赖","severity":"HIGH","rootCause":"x",
                   "evidenceRefs":["m"],"suggestion":"y"}],
                  "report":{"executive_summary":"s","views":{
                    "logical":{"insight":"a"},"process":{"insight":"b"},"development":{"insight":"c"},
                    "physical":{"insight":"d"},"scenario":{"insight":"e"}},
                    "roadmap":{"p0":[],"p1":[],"p2":[]}}}}
                """;
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(json);

        Map<String, Object> out = node.execute(Map.of("evidencePack", evidencePack()));

        assertThat(out.get("degraded")).isEqualTo(true);
    }

    @Test
    @DisplayName("diagramRefs 引用不存在的图 id → 运行时校验失败 → 降级")
    void diagramRefsUnknownIdFailsValidationAndDegrades() {
        String json = """
                {"thought":"总结","final_answer":{"issues":[
                  {"ref":"i-1","title":"循环依赖","severity":"HIGH","rootCause":"x",
                   "evidenceRefs":["m"],"suggestion":"y","verdict":"confirmed","reason":"r"}],
                  "report":{"executive_summary":"s","views":{
                    "logical":{"insight":"a","diagramRefs":["loop_rings"]},
                    "process":{"insight":"b"},"development":{"insight":"c"},
                    "physical":{"insight":"d"},"scenario":{"insight":"e"}},
                    "roadmap":{"p0":[],"p1":[],"p2":[]}}}}
                """;
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(json);

        Map<String, Object> out = node.execute(Map.of("evidencePack", evidencePack()));

        assertThat(out.get("degraded")).isEqualTo(true);
    }

    @Test
    @DisplayName("先调工具再收敛：工具派发到 KG 端点并观察后产出结论")
    void callsToolThenConverges() {
        when(kg.getDashboard(isNull(), anyList(), isNull()))
                .thenReturn(ApiResponse.success(Map.of("healthScore", 72)));
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(VALID_ACTION_JSON, VALID_FINAL_ANSWER_JSON);

        Map<String, Object> out = node.execute(Map.of("evidencePack", evidencePack()));

        verify(kg).getDashboard(isNull(), anyList(), isNull());
        assertThat(out.get("degraded")).isEqualTo(false);
        assertThat((String) out.get("markdownReport")).contains("## 逻辑视图");
    }

    @Test
    @DisplayName("多项目：证据包 projectPaths 多值透传给工具调用")
    void multiProjectPathsPassedToTools() {
        when(kg.getDashboard(isNull(), eq(List.of("p1", "p2")), isNull()))
                .thenReturn(ApiResponse.success(Map.of("healthScore", 72)));
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(VALID_ACTION_JSON, VALID_FINAL_ANSWER_JSON);

        Map<String, Object> pack = evidencePack();
        pack.put("projectPaths", List.of("p1", "p2"));
        node.execute(Map.of("evidencePack", pack));

        verify(kg).getDashboard(isNull(), eq(List.of("p1", "p2")), isNull());
    }

    @Test
    @DisplayName("LLM 连续失败：降级为模板报告，degraded=true 不抛异常")
    void degradesWhenLlmAlwaysFails() {
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(null);

        Map<String, Object> out = node.execute(Map.of("evidencePack", evidencePack()));

        assertThat(out.get("degraded")).isEqualTo(true);
        assertThat((String) out.get("markdownReport"))
                .contains("## 逻辑视图").contains("## 过程视图")
                .contains("## 开发视图").contains("## 物理视图")
                .contains("## 场景视图").contains("## 问题分析")
                .contains("## 证据附录");
        assertThat(rootCauseResult(out).get("degraded")).isEqualTo(true);
    }

    @Test
    @DisplayName("maxSteps 耗尽（一直调工具不收敛）：降级")
    void degradesWhenMaxStepsExhausted() {
        ArchReActAgentNode shortNode = new ArchReActAgentNode(
                llm, registry, new ObservationTrimmer(), new SchemaValidator(), diagramGenerator, 3, 2000);
        when(kg.getDashboard(isNull(), anyList(), isNull()))
                .thenReturn(ApiResponse.success(Map.of()));
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(VALID_ACTION_JSON);

        Map<String, Object> out = shortNode.execute(Map.of("evidencePack", evidencePack()));

        assertThat(out.get("degraded")).isEqualTo(true);
        assertThat((String) out.get("markdownReport")).contains("## 逻辑视图");
    }

    @Test
    @DisplayName("执行期间通过 sink 产出推理/工具/观察/完成过程消息")
    void emitsProgressMessages() {
        when(llm.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.<Class<String>>any()))
                .thenReturn(VALID_FINAL_ANSWER_JSON);

        List<String> messages = new ArrayList<>();
        node.execute(Map.of("evidencePack", evidencePack()), messages::add);

        assertThat(messages).anyMatch(m -> m.contains("推理"));
        assertThat(messages).anyMatch(m -> m.contains("完成"));
    }

    @Test
    @DisplayName("缺 evidencePack：快速失败")
    void failsFastWhenEvidencePackMissing() {
        assertThatCode(() -> node.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidencePack");
    }
}
