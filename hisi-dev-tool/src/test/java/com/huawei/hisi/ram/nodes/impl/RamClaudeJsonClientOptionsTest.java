package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.impl.AnthropicHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调用方在 SendOptions 里关掉思考后，这个客户端在换上系统提示词时必须把开关带上。
 * 以前它用四参构造器重建选项，开关被悄悄丢掉——「关闭思考」的修复因此一直没有真正生效。
 */
class RamClaudeJsonClientOptionsTest {

    @Test
    void theThinkingSwitchSurvivesSwappingTheSystemPrompt() {
        AnthropicHttpClient http = mock(AnthropicHttpClient.class);
        when(http.stream(anyList(), anyList(), any())).thenReturn(Flux.empty());
        RamClaudeJsonClient client = new RamClaudeJsonClient(http, "test-key", "model");
        SendOptions noThinking = new SendOptions("model", 1000, 0.2, null).withoutThinking();

        // 空的响应流会让调用报错，这里只关心发出去的选项
        quietly(() -> client.callText("sys", "user", noThinking));
        quietly(() -> client.callJson("sys", "user", noThinking));

        ArgumentCaptor<SendOptions> sent = ArgumentCaptor.forClass(SendOptions.class);
        verify(http, atLeast(2)).stream(anyList(), anyList(), sent.capture());
        assertTrue(sent.getAllValues().stream().allMatch(SendOptions::thinkingDisabled),
                "发出去的请求应保留 thinkingDisabled：" + sent.getAllValues());
    }

    private static void quietly(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // 空响应导致的异常不是这里要验证的
        }
    }
}
