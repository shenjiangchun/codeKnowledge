package com.huawei.hisi.knowledgegraph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a class inheritance relationship (subclass EXTENDS superclass).
 * Persisted to Neo4j via {@code methodNodeRepository.createExtendsRelations}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassExtends {
    private String subclass;
    private String superclass;
    private String projectPath;
}
