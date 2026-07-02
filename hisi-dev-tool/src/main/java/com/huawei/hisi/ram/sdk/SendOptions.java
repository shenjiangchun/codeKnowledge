package com.huawei.hisi.ram.sdk;

import com.huawei.hisi.ram.config.ChatModelProperties;

import java.util.Objects;

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
        return new SendOptions(null, 4096, 0.7, null);
    }

    public static SendOptions forScenario(ChatModelProperties props, String modelId, String scenario) {
        var spec = Objects.requireNonNull(props.getModels().get(modelId),
            () -> "unknown model: " + modelId);
        int max = Objects.requireNonNull(spec.getScenarioMaxTokens().get(scenario),
            () -> "unknown scenario: " + scenario);
        return new SendOptions(modelId, max, 0.7, null);
    }
}
