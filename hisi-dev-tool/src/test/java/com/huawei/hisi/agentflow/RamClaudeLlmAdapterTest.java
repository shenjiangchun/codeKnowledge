package com.huawei.hisi.agentflow;

import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RamClaudeLlmAdapterTest {

    private final RamClaudeJsonClient delegate = mock(RamClaudeJsonClient.class);
    private final RamClaudeLlmAdapter adapter = new RamClaudeLlmAdapter(delegate, 60, false);

    @Test
    void emptyTextIsRetriedOnceWithALargerBudget() {
        when(delegate.callText(anyString(), anyString(), any())).thenReturn("", "正文");

        String text = adapter.completeText("sys", "user", 4000);

        assertEquals("正文", text);
        ArgumentCaptor<SendOptions> options = ArgumentCaptor.forClass(SendOptions.class);
        verify(delegate, times(2)).callText(anyString(), anyString(), options.capture());
        assertEquals(4000, options.getAllValues().get(0).maxTokens());
        assertEquals(12000, options.getAllValues().get(1).maxTokens());
    }

    @Test
    void nonEmptyTextIsNotRetried() {
        when(delegate.callText(anyString(), anyString(), any())).thenReturn("ok");

        assertEquals("ok", adapter.completeText("sys", "user", 4000));

        verify(delegate, times(1)).callText(anyString(), anyString(), any());
    }

    @Test
    void singleShotCallsDisableThinkingUnlessAskedOtherwise() {
        // 一次性结构化输出：会先思考的模型把思考 token 算在预算里，提示词一长正文就为空
        when(delegate.callJson(anyString(), anyString(), any())).thenReturn(Map.of("edits", 1));
        ArgumentCaptor<SendOptions> options = ArgumentCaptor.forClass(SendOptions.class);

        adapter.completeJson("sys", "user", 4000);
        verify(delegate).callJson(anyString(), anyString(), options.capture());
        assertTrue(options.getValue().thinkingDisabled(), "默认关闭思考");

        RamClaudeLlmAdapter thinking = new RamClaudeLlmAdapter(delegate, 60, true);
        thinking.completeJson("sys", "user", 4000);
        ArgumentCaptor<SendOptions> second = ArgumentCaptor.forClass(SendOptions.class);
        verify(delegate, times(2)).callJson(anyString(), anyString(), second.capture());
        assertFalse(second.getAllValues().get(1).thinkingDisabled(), "配置打开后沿用模型默认的思考行为");
    }

    @Test
    void emptyJsonIsRetriedAndStillEmptyJsonIsAnError() {
        when(delegate.callJson(anyString(), anyString(), any())).thenReturn(Map.of(), Map.of("answer", 1));
        assertEquals(1, adapter.completeJson("sys", "user", 4000).get("answer"));

        reset(delegate);
        when(delegate.callJson(anyString(), anyString(), any())).thenReturn(Map.of());
        assertThrows(IllegalStateException.class, () -> adapter.completeJson("sys", "user", 4000));
    }

    @Test
    void truncatedJsonIsRetriedWithALargerBudgetJustLikeAnEmptyAnswer() {
        // 底层客户端遇到「输出被截断、解析不出 JSON」时抛异常而不是返回空：这是输出预算不够，不是调用出错
        when(delegate.callJson(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("Claude response is not valid JSON"))
                .thenReturn(Map.of("edits", 1));

        assertEquals(1, adapter.completeJson("sys", "user", 4000).get("edits"));

        ArgumentCaptor<SendOptions> options = ArgumentCaptor.forClass(SendOptions.class);
        verify(delegate, times(2)).callJson(anyString(), anyString(), options.capture());
        assertEquals(12000, options.getAllValues().get(1).maxTokens());
    }

    @Test
    void otherJsonFailuresAreNotRetried() {
        when(delegate.callJson(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("Anthropic API failed: 503"));

        assertThrows(IllegalStateException.class, () -> adapter.completeJson("sys", "user", 4000));
        verify(delegate, times(1)).callJson(anyString(), anyString(), any());
    }

    @Test
    void retryBudgetIsCapped() {
        assertEquals(24_000, RamClaudeLlmAdapter.retryBudget(8_000));
        assertEquals(32_000, RamClaudeLlmAdapter.retryBudget(32_000));
    }

    @Test
    void callThatExceedsTheTimeLimitIsCancelled() {
        // 1 秒时限，底层调用要睡 5 秒：适配器应在约 1 秒后放弃，而不是等底层 10 分钟的读超时
        RamClaudeLlmAdapter quick = new RamClaudeLlmAdapter(delegate, 1, false);
        when(delegate.callText(anyString(), anyString(), any())).thenAnswer(inv -> {
            Thread.sleep(5000);
            return "太晚了";
        });

        long start = System.currentTimeMillis();
        AgentLlmTimeoutException ex = assertThrows(AgentLlmTimeoutException.class,
                () -> quick.completeText("sys", "user", 4000));

        assertTrue(ex.getMessage().contains("未返回"));
        assertTrue(System.currentTimeMillis() - start < 4000, "应按时限放弃，而不是等底层调用结束");
    }

    @Test
    void failureOfTheUnderlyingCallIsPropagatedAsIs() {
        when(delegate.callText(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("Anthropic API failed: 503"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.completeText("sys", "user", 4000));

        assertEquals("Anthropic API failed: 503", ex.getMessage());
    }
}
