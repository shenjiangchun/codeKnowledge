package com.huawei.hisi.apm.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the dedicated APM Failure Locator LLM client.
 *
 * <p>Bound from the {@code hisi.apm.diagnose.llm.*} namespace. Decoupled from
 * the project's {@code text-model} (used by {@code UnifiedTextService} for
 * cheap KG description generation on glm-4-flash) so the diagnose pipeline can
 * call a higher-capability model (Claude via dmxapi.cn) under a strict global
 * concurrency cap.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "hisi.apm.diagnose.llm")
public class ApmLlmProperties {

    /** OpenAI-compatible base URL, e.g. {@code https://www.dmxapi.cn}. */
    @NotBlank
    private String baseUrl;

    /** Model identifier sent in the {@code model} field of the chat request. */
    @NotBlank
    private String model;

    /**
     * Bearer token. Intentionally not validated as {@code @NotBlank} to allow
     * the test profile to leave it empty; the client fails fast with a clear
     * message if it is blank when actually invoked.
     */
    private String apiKey;

    /** HTTP connect/read timeout (seconds). */
    @Min(1)
    private int timeoutSeconds = 40;

    /** Hard global concurrency limit enforced by an in-process semaphore. */
    @Min(1)
    @Max(16)
    private int maxConcurrency = 2;

    /** Sampling temperature passed to the upstream API. */
    private double temperature = 0.2;

    /**
     * Maximum tokens to generate per call.
     *
     * <p>Default 8192 — large enough for reasoning-style models (e.g. GLM-5.1-wenshu)
     * whose internal "reasoning" phase consumes tokens before the visible content
     * is produced. With the previous default of 1024, all tokens could be eaten by
     * reasoning, leaving {@code choices[0].message.content} null.
     */
    @Min(1)
    private int maxTokens = 8192;
}
