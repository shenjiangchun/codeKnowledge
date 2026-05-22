package com.huawei.hisi.ram.nodes;

import java.util.Map;

/**
 * Abstraction over the LLM call used by the clarify stage to extract
 * structured requirement fields from a free-form user request.
 *
 * <p>Implementations may call Claude, a stub, or any other model; the
 * orchestrator only relies on the returned map being suitable for
 * {@code clarify.output} schema validation.</p>
 */
public interface ClarifyLlmClient {

    /**
     * Extract structured requirements from the user's natural-language request.
     *
     * @param userRequest free-form user request (never {@code null})
     * @param hints       additional context the caller wants to surface to the
     *                    model (e.g. {@code projectHints}); never {@code null}
     * @return map shaped to satisfy the {@code clarify.output} schema
     */
    Map<String, Object> extractRequirements(String userRequest, Map<String, Object> hints);
}
