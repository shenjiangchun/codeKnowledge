package com.huawei.hisi.workflow;

import java.util.List;
import java.util.Map;

/**
 * Describes a complete workflow DAG: its type, display name, and ordered node names.
 *
 * @param workflowType  unique identifier, e.g. "demand", "status", "phase2", "custom-xxx"
 * @param displayName   UI display name
 * @param description   human-readable description
 * @param nodeNames     node names in topological execution order
 * @param metadata      optional extension data
 */
public record WorkflowDefinition(
        String workflowType,
        String displayName,
        String description,
        List<String> nodeNames,
        Map<String, Object> metadata
) {
    public WorkflowDefinition {
        nodeNames = nodeNames == null ? List.of() : List.copyOf(nodeNames);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
