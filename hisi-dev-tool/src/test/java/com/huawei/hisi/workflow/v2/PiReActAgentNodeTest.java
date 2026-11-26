package com.huawei.hisi.workflow.v2;

import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.knowledgegraph.aggregation.llm.DeepseekJsonClient;
import com.huawei.hisi.piagent.PiFlowClient;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.workflow.NodeEventSink;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import com.huawei.hisi.workflow.agent.ObservationTrimmer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * pi 版 ReAct 节点与底座的衔接：pi 的最终输出要按契约校验但不能因为一个说明性字段把整份结论丢掉；
 * 底座的上下文管理动作要能显示在进度里。
 */
@DisplayName("PiReActAgentNode contract and progress reporting")
class PiReActAgentNodeTest {

    private final PiReActAgentNode node = new PiReActAgentNode(
            mock(DeepseekJsonClient.class), mock(ArchAgentToolRegistry.class), mock(ObservationTrimmer.class),
            new SchemaValidator(), mock(ArchDiagramGenerator.class), 10, 2000,
            mock(PiFlowClient.class), mock(AgentFlowCancelRegistry.class));

    @Test
    @DisplayName("pi 只给了 final_answer（没有 thought）：补默认值后契约通过")
    void aFinalAnswerWithoutThoughtPassesTheContract() {
        Map<String, Object> step = node.finalStep(
                "{\"final_answer\":{\"issues\":[],\"report\":{\"executive_summary\":\"架构健康度低\"}}}");

        assertThat(step).isNotNull();
        // 这里只关心 thought 不再是缺项（final_answer 内部的嵌套要求由真实结论负责）
        assertThat(new SchemaValidator().validate("arch/react-step", step).missingFields())
                .as("真机上模型漏掉 thought 时，缺项里只有它").doesNotContain("thought");
    }

    @Test
    @DisplayName("结论前后带了说明文字和代码围栏：照样取得 final_answer")
    void proseAndFencesAroundTheJsonAreTolerated() {
        Map<String, Object> step = node.finalStep("结论如下：\n```json\n{\"thought\":\"查证完毕\",\"final_answer\":{\"issues\":[],\"report\":{}}}\n```\n以上。");

        assertThat(step).isNotNull();
        assertThat(step.get("thought")).isEqualTo("查证完毕");
        assertThat(new SchemaValidator().validate("arch/react-step", step).missingFields()).doesNotContain("thought");
    }

    @Test
    @DisplayName("没有 final_answer：返回 null，由节点走降级")
    void outputWithoutAFinalAnswerIsNull() {
        assertThat(node.finalStep("证据不足，无法得出结论。")).isNull();
        assertThat(node.finalStep("{\"thought\":\"只有思考\"}")).isNull();
        assertThat(node.finalStep("")).isNull();
    }

    @Test
    @DisplayName("真机踩到的两种不合契约输出：结论后面拖着工具调用标记、evidence_appendix 给成对象")
    void theTwoMalformedShapesSeenLiveAreTolerated() {
        String trailingToolMarkup = "{\"thought\":\"查证完毕\",\"final_answer\":{\"issues\":[],\"report\":{"
                + "\"executive_summary\":\"摘要\",\"views\":{\"logical\":{\"insight\":\"洞察\"}},"
                + "\"roadmap\":{\"p0\":[]},\"evidence_appendix\":\"附录\"}}}\n"
                + "</｜｜DSML｜｜parameter>\n</｜｜DSML｜｜invoke>\n</｜｜DSML｜｜tool_calls>";
        Map<String, Object> first = node.finalStep(trailingToolMarkup);
        assertThat(first).isNotNull();
        assertThat(new SchemaValidator().validate("arch/react-step", first).missingFields()).doesNotContain("thought");

        Map<String, Object> objectAppendix = node.finalStep("{\"thought\":\"查证完毕\",\"final_answer\":{\"issues\":[],\"report\":{"
                + "\"executive_summary\":\"摘要\",\"views\":{\"logical\":{\"insight\":\"洞察\"}},\"roadmap\":{\"p0\":[]},"
                + "\"evidence_appendix\":{\"sources\":[\"a\",\"b\"]}}}}");
        assertThat(objectAppendix).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) ((Map<String, Object>) objectAppendix.get("final_answer")).get("report");
        assertThat(report.get("evidence_appendix")).isInstanceOf(String.class).asString().contains("\"sources\"");
    }

    @Test
    @DisplayName("修正提示词带着具体的契约问题与上一份输出")
    void theRepairPromptCarriesTheProblemAndThePreviousOutput() {
        String prompt = PiReActAgentNode.repairPrompt("{\"final_answer\":{}}", "契约校验未过：[缺少 roadmap]");

        assertThat(prompt).contains("契约校验未过：[缺少 roadmap]").contains("{\"final_answer\":{}}")
                .contains("不要调用任何工具").contains("只输出一个 JSON 对象");
    }

    @Test
    @DisplayName("上下文预算与会话压缩的动作显示在进度里")
    void contextManagementIsVisibleInTheProgressLog() {
        List<String> messages = new ArrayList<>();
        NodeEventSink sink = messages::add;

        PiReActAgentNode.reportEvent(Map.of("type", "context_compaction", "beforeChars", 394152, "afterChars", 86960,
                "structured", true), sink);
        PiReActAgentNode.reportEvent(Map.of("type", "auto_compaction_start", "tokensBefore", 118000), sink);
        PiReActAgentNode.reportEvent(Map.of("type", "auto_compaction_end", "tokensBefore", 118000, "tokensAfter", 42000), sink);
        PiReActAgentNode.reportEvent(Map.of("type", "auto_compaction_end", "errorMessage", "summary truncated"), sink);

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).contains("39.4").contains("8.7").contains("完整原文");
        assertThat(messages.get(1)).contains("上下文压缩");
        assertThat(messages.get(2)).contains("118000").contains("42000");
        assertThat(messages.get(3)).contains("上下文压缩失败").contains("summary truncated");
    }
}
