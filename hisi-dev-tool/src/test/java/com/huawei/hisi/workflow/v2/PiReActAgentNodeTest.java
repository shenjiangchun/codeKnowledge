package com.huawei.hisi.workflow.v2;

import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.workflow.NodeEventSink;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 架构 ReAct 节点在统一会话层之上的职责：按声明（arch-review）装配、把结论转成
 * 输出契约（{@code rootCauseResult / markdownReport / diagrams / degraded}）、
 * 把底座的上下文管理动作显示在进度里。契约校验与修正不再在节点里（见 {@link ReActSession}）。
 */
@DisplayName("PiReActAgentNode on top of ReActSession")
class PiReActAgentNodeTest {

    private final ReActSession session = mock(ReActSession.class);
    private final PiReActAgentNode node = new PiReActAgentNode(
            mock(ArchAgentToolRegistry.class), mock(ArchDiagramGenerator.class),
            session, new AgentFlowCancelRegistry());

    @Test
    @DisplayName("会话给出契约结论：按声明键装配，输出转换成节点契约")
    void aContractResultBecomesTheNodeOutput() {
        when(session.run(any(), anyString(), any(), anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(ReActSession.Result.of("{\"thought\":\"x\",\"final_answer\":{...}}",
                        Map.of("thought", "x", "final_answer", Map.of(
                                "issues", List.of(),
                                "report", Map.of("executive_summary", "健康度低", "views", Map.of(), "roadmap", Map.of()))),
                        null));

        Map<String, Object> input = Map.of("evidencePack", Map.of(
                "projectPaths", List.of("."), "issues", List.of(), "chapters", Map.of()));
        Map<String, Object> output = node.execute(input, mock(NodeEventSink.class));

        assertThat(output).containsKeys("rootCauseResult", "markdownReport", "diagrams", "degraded");
        assertThat(output.get("degraded")).isEqualTo(false);
        // 装配用的是统一声明里的键与只读清单
        ArgumentCaptor<AgentRegistry.ReactAgent> agent = ArgumentCaptor.forClass(AgentRegistry.ReactAgent.class);
        verify(session).run(agent.capture(), anyString(), any(), anyString(), anyString(), any(), any(), isNull());
        assertThat(agent.getValue().key()).isEqualTo("arch-review");
        assertThat(agent.getValue().flow()).isEqualTo("arch-react");
    }

    @Test
    @DisplayName("会话没有产出 JSON：降级为模板报告，不抛异常")
    void anUnparsableResultDegradesToTheTemplate() {
        when(session.run(any(), anyString(), any(), anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(ReActSession.Result.of("", null, "pi 会话超时"));

        Map<String, Object> input = Map.of("evidencePack", Map.of(
                "projectPaths", List.of("."), "issues", List.of(), "chapters", Map.of()));
        List<String> messages = new ArrayList<>();
        Map<String, Object> output = node.execute(input, messages::add);

        assertThat(output.get("degraded")).isEqualTo(true);
        assertThat(messages).anyMatch(m -> m.contains("降级"));
    }

    @Test
    @DisplayName("会话被取消：抛取消异常让执行器收尾")
    void aCancelledSessionThrows() {
        when(session.run(any(), anyString(), any(), anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(ReActSession.Result.of("", null, "已取消"));

        Map<String, Object> input = Map.of("evidencePack", Map.of(
                "projectPaths", List.of("."), "issues", List.of(), "chapters", Map.of()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> node.execute(input, mock(NodeEventSink.class)))
                .isInstanceOf(com.huawei.hisi.agentflow.AgentFlowCancelledException.class);
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
        PiReActAgentNode.reportEvent(Map.of("type", "tool_execution_start", "toolName", "kg_method_detail"), sink);

        assertThat(messages).hasSize(5);
        assertThat(messages.get(0)).contains("39.4").contains("8.7").contains("完整原文");
        assertThat(messages.get(1)).contains("上下文压缩");
        assertThat(messages.get(2)).contains("118000").contains("42000");
        assertThat(messages.get(3)).contains("上下文压缩失败").contains("summary truncated");
        assertThat(messages.get(4)).contains("查询工具：kg_method_detail");
    }
}
