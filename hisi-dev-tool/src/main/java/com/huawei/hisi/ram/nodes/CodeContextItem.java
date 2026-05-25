package com.huawei.hisi.ram.nodes;

/**
 * A code snippet found by semantic search, providing project context
 * to the clarify LLM so it can answer technical questions itself rather
 * than blindly asking the user.
 *
 * <p>Populated by {@link ClarifyNode#searchProjectContext} using the
 * knowledge-graph hybrid search, then passed into the LLM prompt by
 * {@link com.huawei.hisi.ram.nodes.impl.ClaudeClarifyLlmClient}.</p>
 */
public record CodeContextItem(
        String className,
        String methodName,
        String signature,
        String filePath,
        String description,
        String methodBody,
        double score
) {}
