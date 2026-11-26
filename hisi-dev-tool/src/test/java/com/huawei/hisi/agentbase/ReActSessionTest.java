package com.huawei.hisi.agentbase;

import com.huawei.hisi.piagent.PiAgentInstaller;
import com.huawei.hisi.piagent.PiFlowClient;
import com.huawei.hisi.ram.contract.SchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct 会话装配：skill 按声明解析、契约不过自动修正、自由文本 agent 不做解析、
 * 动作 agent 的令牌透传给 MCP 通道。
 */
@DisplayName("ReActSession assembles agents from their declarations")
class ReActSessionTest {

    private final PiFlowClient pi = mock(PiFlowClient.class);
    private final PiAgentInstaller installer = mock(PiAgentInstaller.class);
    private final ReActSession session = new ReActSession(pi, installer, new SchemaValidator());

    {
        // 测试里不真同步 skill：目录名不存在的 skill 只会被跳过并告警
        when(installer.skillPath(anyString()))
                .thenAnswer(inv -> Path.of(System.getProperty("java.io.tmpdir"), inv.getArgument(0, String.class)));
    }

    private AgentRegistry.ReactAgent agent(String key) {
        return AgentRegistry.agent(key).orElseThrow();
    }

    @Test
    @DisplayName("契约输出：一次通过，不修正")
    void aContractCompliantOutputPassesOnce() {
        AgentRegistry.ReactAgent clarify = agent("ram-clarify");
        when(pi.runWithToken(eq("ram"), anyString(), any(), any(), anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(ok("{\"intent\":\"给列表加过滤\",\"project_paths\":[\"/p\"],\"acceptance_criteria\":[\"c\"]}"));

        ReActSession.Result result = session.run(clarify, "s1", Path.of("."), "系统", "用户", null, () -> false, null);

        assertThat(result.note()).isNull();
        assertThat(result.json()).containsEntry("intent", "给列表加过滤");
        verify(pi, times(1)).runWithToken(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("契约不符：带着具体问题开新会话修正，修好为止")
    void aContractViolationIsRepaired() {
        AgentRegistry.ReactAgent clarify = agent("ram-clarify");
        when(pi.runWithToken(eq("ram"), anyString(), any(), any(), anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(ok("{\"intent\":\"x\"}"))
                .thenReturn(ok("{\"intent\":\"x\",\"project_paths\":[\"/p\"],\"acceptance_criteria\":[\"c\"]}"));

        ReActSession.Result result = session.run(clarify, "s1", Path.of("."), "系统", "用户", null, () -> false, null);

        assertThat(result.note()).isNull();
        assertThat(result.json()).containsKeys("intent", "project_paths", "acceptance_criteria");
        verify(pi, times(2)).runWithToken(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
        // 修正轮开的是新会话，且提示词带着具体问题与上一份输出
        verify(pi, times(1)).runWithToken(eq("ram"), eq("s1-fix1"), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.contains("没有通过契约校验"), any(), any(), isNull());
    }

    @Test
    @DisplayName("自由文本 agent：正文原样返回，不做 JSON 解析")
    void aFreeTextAgentReturnsRawText() {
        AgentRegistry.ReactAgent followup = agent("log-followup");
        when(pi.runWithToken(anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(ok("这个异常来自连接池耗尽。"));

        ReActSession.Result result = session.run(followup, "s1", Path.of("."), "系统", "用户", null, () -> false, null);

        assertThat(result.text()).isEqualTo("这个异常来自连接池耗尽。");
        assertThat(result.json()).isNull();
    }

    @Test
    @DisplayName("模型报错（正文空、note 带原因）：把原因带出来")
    void aModelErrorIsSurfaced() {
        when(pi.runWithToken(anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(new PiFlowClient.PiResult("", List.of(), "agent_settled", false, "模型调用失败：401 Invalid token"));

        ReActSession.Result result = session.run(agent("ram-clarify"), "s1", Path.of("."), "系统", "用户", null, () -> false, null);

        assertThat(result.note()).contains("401 Invalid token");
    }

    @Test
    @DisplayName("动作 agent 的令牌透传给 MCP 通道；只读 agent 不透传")
    void theScopeTokenReachesOnlyActionAgents() {
        when(pi.runWithToken(anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(ok("好的"));

        session.run(agent("ram-chat"), "s1", Path.of("."), "系统", "用户", null, () -> false, "user-jwt");
        session.run(agent("ram-clarify"), "s2", Path.of("."), "系统", "用户", null, () -> false, null);

        // ram-chat 的首轮调用带令牌；ram-clarify 的只读调用不带
        verify(pi, atLeast(1)).runWithToken(eq("ram-act"), anyString(), any(), any(), anyString(), anyString(), any(), any(), eq("user-jwt"));
        verify(pi, atLeast(1)).runWithToken(eq("ram"), anyString(), any(), any(), anyString(), anyString(), any(), any(), isNull());
    }

    private static PiFlowClient.PiResult ok(String text) {
        return new PiFlowClient.PiResult(text, List.of(), "agent_settled", false, null);
    }
}
