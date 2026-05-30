package com.huawei.hisi.apm.service.locator;

/**
 * Minimal LLM chat port used by {@link LlmDiagnoseAdapter}. Decouples the
 * adapter from any particular vendor SDK and keeps unit tests simple — they
 * can mock this SAM interface directly.
 *
 * <p>The default Spring bean (registered conditionally in
 * {@code ApmDiagnoseConfig}) is backed by the project's
 * {@link com.huawei.hisi.service.UnifiedTextService}; alternative
 * implementations can be supplied to swap the upstream provider.
 *
 * @author HiSi DevTool Team
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * Send a single chat completion call to the underlying LLM and return the
     * raw assistant text (no schema interpretation here).
     *
     * @param systemPrompt the system-role prompt
     * @param userPrompt   the user-role prompt
     * @return the raw assistant text; never null
     */
    String chat(String systemPrompt, String userPrompt);
}
