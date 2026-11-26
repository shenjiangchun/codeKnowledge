package com.huawei.hisi.ram.nodes.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 带工具的调用里，模型查完代码常用一段大白话收尾。真机上需求分析的澄清节点就是这样一路解析失败、回退到桩实现的。
 */
class LlmJsonAnswerTest {

    private static final String PROSE = """
            I have enough context now. The riskLevel grep matches are all in `.worktrees/python-support/`
            but those are for risk assessment services, unrelated to skill market.
            The main src has no riskLevel in SkillMarketController.
            """;

    private static ChatClient.CallResponseSpec answer(String content) {
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(call.content()).thenReturn(content);
        return call;
    }

    @Test
    void aPlainJsonAnswerIsUsedAsIs() {
        Optional<Map<String, Object>> parsed = LlmJsonAnswer.extractObject("{\"intent\": \"加过滤\", \"target_methods\": [\"a.B#c\"]}");

        assertEquals("加过滤", parsed.orElseThrow().get("intent"));
        assertTrue(LlmJsonAnswer.extractObject("```json\n{\"a\": 1}\n```").orElseThrow().containsKey("a"));
        assertTrue(LlmJsonAnswer.extractObject(null).isEmpty());
    }

    @Test
    void jsonEmbeddedInProseIsExtractedEvenWithBracesInsideStringsAndTrailingProse() {
        String text = "Here is the result: {\"intent\": \"a {b} c\", \"target_methods\": [\"a.B#c\"]} — done } hope it helps";

        Map<String, Object> parsed = LlmJsonAnswer.extractObject(text).orElseThrow();

        assertEquals("a {b} c", parsed.get("intent"));
        assertEquals(List.of("a.B#c"), parsed.get("target_methods"));
    }

    @Test
    void pureProseIsNotMistakenForJson() {
        assertTrue(LlmJsonAnswer.extractObject(PROSE).isEmpty());
        assertTrue(LlmJsonAnswer.extractObject("no braces here").isEmpty());
    }

    @Test
    void proseIsReformattedByTheFormatterClientInsteadOfFallingBackToAStub() {
        ChatClient formatter = mock(ChatClient.class, withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));
        when(formatter.prompt().system(Mockito.anyString()).user(Mockito.anyString()).call().content())
                .thenReturn("{\"intent\": \"技能列表按 riskLevel 过滤\","
                        + " \"target_methods\": [\"com.huawei.hisi.controller.SkillMarketController#getSkillList\"]}");

        Map<String, Object> raw = LlmJsonAnswer.fromCall(answer(PROSE), formatter, "schema: {\"intent\": ..., \"target_methods\": ...}");

        assertEquals(List.of("com.huawei.hisi.controller.SkillMarketController#getSkillList"), raw.get("target_methods"));
        // 格式化调用要带上原任务的 schema 说明，以及那段大白话（打桩时也经过同一链路，所以只要出现过即可）
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(formatter.prompt(), Mockito.atLeastOnce()).system(system.capture());
        assertTrue(system.getAllValues().stream().anyMatch(s -> s.contains("target_methods")));
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(formatter.prompt().system(Mockito.anyString()), Mockito.atLeastOnce()).user(user.capture());
        assertTrue(user.getAllValues().stream().anyMatch(s -> s.contains("riskLevel")));
    }

    @Test
    void aJsonAnswerNeverTouchesTheFormatter() {
        ChatClient formatter = mock(ChatClient.class, withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));

        assertEquals("ok", LlmJsonAnswer.fromCall(answer("{\"intent\": \"ok\"}"), formatter, "schema").get("intent"));

        verify(formatter, never()).prompt();
    }

    @Test
    void proseWithoutAFormatterIsAnExplicitError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LlmJsonAnswer.fromCall(answer(PROSE), null, "schema"));

        assertTrue(ex.getMessage().contains("不是 JSON"));
        assertTrue(ex.getMessage().contains("I have enough context"), "带上回答的开头，便于排查");
    }
}
