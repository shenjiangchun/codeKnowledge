package com.huawei.hisi.ram.sdk;

/**
 * Per-call options for {@link ClaudeSessionService#sendUserMessage}.
 *
 * <p>{@link #systemPrompt} may be null when no system prompt is desired.
 */
public record SendOptions(
        String model,
        int maxTokens,
        double temperature,
        String systemPrompt
) {

    public static SendOptions defaults() {
        return new SendOptions("claude-opus-4-5", 4096, 0.7, null);
    }
}
