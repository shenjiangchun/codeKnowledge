package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.agent.tools.AgentTools;
import com.huawei.hisi.ram.nodes.CodeContextItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多轮澄清：第一轮带着工具查过代码，之后的轮次没有工具、只看用户的回答。
 * 这几轮必须把已经查到的代码位置带下去——以前后续轮次的 schema 里没有 target_methods，
 * 影响面分析拿到的输入里就没有要改的方法。
 */
@DisplayName("ClaudeClarifyLlmClient carries code findings across clarify rounds")
class ClaudeClarifyLlmClientCarryForwardTest {

    private static final Map<String, Object> HINTS = Map.of(
            "projectHints", List.of("/proj/a"),
            "clarify_history", List.of(Map.of(
                    "questions", List.of("技能列表指哪个接口？"),
                    "answers", Map.of("技能列表指哪个接口？", "SkillMarketController"),
                    "analysis", Map.of(
                            "target_modules", List.of("com.x.skill"),
                            "target_methods", List.of("com.x.SkillMarketController#getSkillList"),
                            "code_analysis_summary", "现状：列表不支持风险等级。"))));

    private final ChatClient agent = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final ChatClient formatter = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final ClaudeClarifyLlmClient client = new ClaudeClarifyLlmClient(
            agent, new StubClarifyLlmClient(), formatter, mock(AgentTools.class));

    @Test
    @DisplayName("模型这轮没带代码位置时，沿用之前提问那轮查到的")
    void codeFoundEarlierSurviveWhenTheAnswerOmitsThem() {
        answerWith("""
                {"needs_clarification": false, "clarify_questions": [],
                 "intent": "给技能列表加 riskLevel 过滤", "project_paths": ["/proj/a"],
                 "acceptance_criteria": ["列表支持 riskLevel 过滤"]}
                """);

        Map<String, Object> out = client.extractRequirements("加 riskLevel", HINTS, List.of());

        assertThat(out.get("target_methods")).isEqualTo(List.of("com.x.SkillMarketController#getSkillList"));
        assertThat(out.get("target_modules")).isEqualTo(List.of("com.x.skill"));
        assertThat(out.get("code_analysis_summary")).isEqualTo("现状：列表不支持风险等级。");
        assertThat(out.get("acceptance_criteria")).isEqualTo(List.of("列表支持 riskLevel 过滤"));
    }

    @Test
    @DisplayName("模型这轮给了新的代码位置时以模型为准")
    void newCodeFindingsFromTheModelWin() {
        answerWith("""
                {"needs_clarification": false, "clarify_questions": [],
                 "intent": "改另一个接口", "project_paths": ["/proj/a"], "acceptance_criteria": ["a"],
                 "target_methods": ["com.x.SkillController#getList"]}
                """);

        Map<String, Object> out = client.extractRequirements("加 riskLevel", HINTS, List.of());

        assertThat(out.get("target_methods")).isEqualTo(List.of("com.x.SkillController#getList"));
        assertThat(out.get("target_modules")).as("模型没给、之前查到的仍然有效").isEqualTo(List.of("com.x.skill"));
    }

    @Test
    @DisplayName("之前查到的代码位置写进了提示词，且后续轮次的 schema 包含 target_methods")
    void earlierFindingsAreShownToTheModel() {
        answerWith("""
                {"needs_clarification": true, "clarify_questions": ["非法取值怎么处理？"],
                 "intent": "x", "project_paths": ["/proj/a"], "acceptance_criteria": []}
                """);

        client.extractRequirements("加 riskLevel", HINTS, List.of());

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        // 打桩的那次调用也算一次调用，取真正发出去的那一次（最后一次）
        verify(agent.prompt(), atLeast(1)).system(system.capture());
        verify(agent.prompt().system(anyString()), atLeast(1)).user(user.capture());
        assertThat(system.getValue())
                .as("后续轮次的 schema 不能丢掉 target_methods")
                .contains("\"target_methods\"").contains("\"code_analysis_summary\"");
        assertThat(user.getValue())
                .as("提问那轮查到的代码位置要给模型看")
                .contains("target_methods: [com.x.SkillMarketController#getSkillList]")
                .contains("A: SkillMarketController");
        // 这几轮没有工具可调：代码位置只能靠带进来的
        verify(agent.prompt().system(anyString()), never()).tools(any(AgentTools.class));
    }

    private void answerWith(String json) {
        when(agent.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(json);
    }
}
