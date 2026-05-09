package com.huawei.hisi.knowledgegraph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a method override relationship (subclass#method OVERRIDES superclass#method).
 * Persisted to Neo4j via {@code methodNodeRepository.createOverrideRelations}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodOverride {
    private String subclass;
    private String superclass;
    private String methodName;
    private String projectPath;
}
