package com.huawei.hisi.piagent;

import com.huawei.hisi.agentflow.AgentLlmTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentLlmClient} 的 pi 实现：各业务流的一次性结构化调用经 pi 执行。
 * 对外行为沿用旧适配器——没拿到 JSON 重试一次、超时抛超时异常、pi 未就绪时不可用。
 */
@DisplayName("PiAgentLlmClient one-shot calls through pi")
class PiAgentLlmClientTest {

    private final PiFlowClient pi = mock(PiFlowClient.class);
    private final PiAgentInstaller installer = mock(PiAgentInstaller.class);
    private final PiAgentProperties properties = new PiAgentProperties();

    private PiAgentLlmClient client(int callTimeoutSeconds) {
        when(installer.ready()).thenReturn(true);
        when(installer.agentDir()).thenReturn(Path.of(System.getProperty("java.io.tmpdir")));
        return new PiAgentLlmClient(pi, installer, properties, callTimeoutSeconds);
    }

    @Test
    @DisplayName("pi 未就绪：不可用（调用方据此走确定性降级）")
    void unavailableWhenPiIsNotReady() {
        PiAgentLlmClient client = client(60);
        when(installer.ready()).thenReturn(false);

        assertThat(client.isAvailable()).isFalse();
        assertThatThrownBy(() -> client.completeJson("s", "u", 1000)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("输出是 JSON：直接解析，不重试")
    void aJsonObjectIsParsedWithoutRetry() {
        answered("{\n  \"hypotheses\": [{\"id\": \"H1\"}]\n}");

        Map<String, Object> json = client(60).completeJson("系统", "用户", 4000);

        assertThat(json).containsKey("hypotheses");
        verify(pi, times(1)).run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), isNull(), any());
    }

    @Test
    @DisplayName("第一次写成了大白话：带着上一次的输出要求只改成 JSON，内容沿用")
    void proseIsRepairedIntoJsonOnce() {
        answered("好的，结论如下：", "{\"root_cause\": \"连接池耗尽\"}");

        Map<String, Object> json = client(60).completeJson("系统", "用户", 4000);

        assertThat(json).containsEntry("root_cause", "连接池耗尽");
        verify(pi, times(2)).run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), isNull(), any());
    }

    @Test
    @DisplayName("两次都不是 JSON：抛异常，由调用方兜底")
    void twoNonJsonAnswersThrow() {
        answered("无法确定", "仍然无法确定");

        assertThatThrownBy(() -> client(60).completeJson("系统", "用户", 4000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未返回可解析的 JSON");
    }

    @Test
    @DisplayName("模型报错（pi 照常结束但正文为空）：把原因带出来，不重试")
    void aModelErrorSurfacesWithItsReason() {
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), isNull(), any()))
                .thenReturn(new PiFlowClient.PiResult("", List.of(), "agent_settled", false, "模型调用失败：401 Invalid token"));

        assertThatThrownBy(() -> client(60).completeText("系统", "用户", 4000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401 Invalid token");
    }

    @Test
    @DisplayName("超过单次调用时限：抛超时异常")
    void aCallPastItsDeadlineTimesOut() {
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), isNull(), any()))
                .thenAnswer(inv -> new PiFlowClient.PiResult("", List.of(), "cancelled", false, null));

        assertThatThrownBy(() -> client(0).completeText("系统", "用户", 4000))
                .isInstanceOf(AgentLlmTimeoutException.class);
    }

    @Test
    @DisplayName("开关关闭：不可用")
    void theFlowSwitchTurnsItOff() {
        properties.getFlows(); // 默认开启
        assertThat(client(60).isAvailable()).isTrue();
        properties.setFlows(Map.of(FlowToolManifest.ONE_SHOT, false));

        assertThat(client(60).isAvailable()).isFalse();
        verify(pi, never()).run(anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("取正文里第一个配对完整的 JSON 对象：前后带说明、代码围栏、尾部工具标记都能取到")
    void firstObjectToleratesSurroundingNoise() {
        assertThat(PiAgentLlmClient.firstObject("结论：```json\n{\"a\": 1}\n```")).containsEntry("a", 1);
        assertThat(PiAgentLlmClient.firstObject("{\"a\": \"}{\"}\n</tool_calls>")).containsEntry("a", "}{");
        assertThat(PiAgentLlmClient.firstObject("没有结构化内容")).isNull();
        assertThat(PiAgentLlmClient.firstObject("{\"broken\": ")).isNull();
    }

    private void answered(String... replies) {
        PiFlowClient.PiResult[] results = java.util.Arrays.stream(replies)
                .map(text -> new PiFlowClient.PiResult(text, List.of(), "agent_settled", false, null))
                .toArray(PiFlowClient.PiResult[]::new);
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), isNull(), any()))
                .thenReturn(results[0], java.util.Arrays.copyOfRange(results, 1, results.length));
    }
}
