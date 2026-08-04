package com.huawei.hisi.apm.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the APM Failure Locator pipeline, bound from the
 * {@code hisi.apm.diagnose.*} namespace in {@code application.yml}.
 *
 * <p>Centralizes async executor sizing, feature flags (LLM / KG enrichment) and
 * confidence thresholds for the diagnose pipeline.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "hisi.apm.diagnose")
public class ApmDiagnoseProperties {

    /** Global feature flag — when false the executor bean is not created. */
    private boolean enabled = true;

    /** Core (always-alive) worker count for the diagnose executor. */
    @Min(1)
    private int executorCorePoolSize = 2;

    /** Max worker count for burst load. */
    @Min(1)
    private int executorMaxPoolSize = 8;

    /** Bounded queue capacity; overflow triggers CallerRunsPolicy. */
    @Min(1)
    private int executorQueueCapacity = 100;

    /** Keep-alive (seconds) for idle workers above the core size. */
    @Min(1)
    private int executorKeepAliveSeconds = 60;

    /** Overall deadline (seconds) for a single diagnose run. */
    @Min(1)
    private int timeoutSeconds = 60;

    /** Feature flag — when false, falls back to template-only output (no LLM call). */
    private boolean llmEnabled = true;

    /** LLM call deadline (seconds). Must be less than {@link #timeoutSeconds}. */
    @Min(1)
    private int llmTimeoutSeconds = 45;

    /** Feature flag — when false, KG enrichment is skipped. */
    private boolean kgEnabled = true;

    /** Confidence below this threshold transitions to LOW_CONFIDENCE terminal state. */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double confidenceLowThreshold = 0.5;

    /**
     * Cross-field invariant: the LLM deadline must be strictly less than the
     * overall diagnose deadline so the orchestrator can still finalize a
     * TIMEOUT report after the LLM call is abandoned.
     */
    @AssertTrue(message = "llmTimeoutSeconds must be < timeoutSeconds")
    public boolean isLlmTimeoutWithinOverall() {
        return llmTimeoutSeconds < timeoutSeconds;
    }
}
