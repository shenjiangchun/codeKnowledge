package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import com.huawei.hisi.ram.sdk.impl.AnthropicHttpClient;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link RamClaudeJsonClient#callJsonWithToolsAndStreaming} exposes a
 * {@link Disposable} handle via the {@code disposableSink} parameter, and that
 * disposing it mid-stream stops further {@code onAssistantDelta} callbacks.
 *
 * <p>The mocked {@link AnthropicHttpClient#stream} emits five SSE
 * {@code content_block_delta} events spaced 100ms apart. After ~150ms we call
 * {@link Disposable#dispose()} — the count of received deltas MUST be strictly
 * less than 5 (typically 1–2).
 */
class RamClaudeJsonClientCancellationTest {

    private static String delta(String text) {
        return "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}";
    }

    @Test
    void disposingHandleMidStreamStopsFurtherDeltas() throws Exception {
        AnthropicHttpClient http = mock(AnthropicHttpClient.class);
        Flux<String> streamed = Flux.just(
                delta("a"), delta("b"), delta("c"), delta("d"), delta("e")
        ).delayElements(Duration.ofMillis(100));
        when(http.stream(any(), any(), any())).thenReturn(streamed);

        RamClaudeJsonClient client = new RamClaudeJsonClient(http, "test-key", "test-model");

        AtomicInteger deltaCount = new AtomicInteger();
        StreamCallbacks callbacks = new StreamCallbacks() {
            @Override public void onAssistantDelta(String d) { deltaCount.incrementAndGet(); }
            @Override public void onToolUseStart(String n, Map<String, Object> i) {}
            @Override public void onToolResult(String n, String r) {}
            @Override public void onRoundComplete(int r, String s) {}
        };

        AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        Consumer<Disposable> sink = disposableRef::set;

        List<ToolDefinition> tools = List.of(
                new ToolDefinition("dummy", "dummy tool", "{\"type\":\"object\"}"));

        SendOptions opts = new SendOptions("test-model", 100, 0.7, "sys");

        Thread caller = new Thread(() -> {
            try {
                client.callJsonWithToolsAndStreaming(
                        "sys", "hi", tools, Map.of(), opts, callbacks, sink);
            } catch (Throwable ignored) {
                // parseJsonResponse on partial text may throw — that's expected.
            }
        }, "cancellation-test-caller");
        caller.setDaemon(true);
        caller.start();

        // Wait for the disposable to arrive (subscription established).
        long deadline = System.currentTimeMillis() + 1000;
        while (disposableRef.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(disposableRef.get())
                .as("disposableSink must be invoked once subscription is established")
                .isNotNull();

        Thread.sleep(150);
        disposableRef.get().dispose();

        caller.join(2000);

        assertThat(deltaCount.get())
                .as("after dispose(), no further deltas should be delivered (expect 1-2)")
                .isLessThan(5);
    }
}
