package com.huawei.hisi.ram.sdk;


/**
 * Per-call options for {@link ClaudeSessionService#sendUserMessage}.
 *
 * <p>{@link #systemPrompt} may be null when no system prompt is desired.
 * {@link #thinkingDisabled} 为真时请求里显式带 {@code thinking: {type: disabled}}：
 * 会先思考的推理模型把思考 token 也算在 {@link #maxTokens} 里，一次性结构化输出
 * （生成改动、Spec、任务清单这类）经常是预算全花在思考上、正文为空。
 */
public record SendOptions(
        String model,
        int maxTokens,
        double temperature,
        String systemPrompt,
        boolean thinkingDisabled
) {

    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** 默认不改思考设置（沿用模型/网关的默认行为）。 */
    public SendOptions(String model, int maxTokens, double temperature, String systemPrompt) {
        this(model, maxTokens, temperature, systemPrompt, false);
    }

    public static SendOptions defaults() {
        return new SendOptions(null, 4096, DEFAULT_TEMPERATURE, null);
    }


    /** 同样的选项，但显式关闭思考。 */
    public SendOptions withoutThinking() {
        return new SendOptions(model, maxTokens, temperature, systemPrompt, true);
    }
}
