package com.huawei.hisi.ram.kg.dto;

/**
 * Carries a method node's source code and metadata for AI-based
 * relevance analysis in {@link com.huawei.hisi.ram.nodes.impact.ScopeNarrowingService}.
 */
public record MethodBodyInfo(
        String nodeId,
        String className,
        String methodName,
        String description,
        String methodBody,
        String filePath
) {
}
