package com.huawei.hisi.ram.sdk;

import com.huawei.hisi.ram.config.ChatModelProperties;

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

    private static final double DEFAULT_TEMPERATURE = 0.7;

    public static SendOptions defaults() {
        return new SendOptions(null, 4096, DEFAULT_TEMPERATURE, null);
    }

    public static SendOptions forScenario(ChatModelProperties props, String modelId, String scenario) {
        var spec = props.getModels().get(modelId);
        if (spec == null) {
            throw new IllegalArgumentException("unknown model: " + modelId);
        }
        Integer max = spec.getScenarioMaxTokens().get(scenario);
        if (max == null) {
            throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        return new SendOptions(modelId, max, DEFAULT_TEMPERATURE, null);
    }
}
