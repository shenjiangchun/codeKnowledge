package com.huawei.hisi.knowledgegraph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a proxy class relationship (proxyClass PROXY targetClass).
 * Persisted to Neo4j via {@code methodNodeRepository.createProxyRelations}.
 * {@code proxyType} carries the proxy kind (e.g. JDK_DYNAMIC, CGLIB, STATIC).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProxyRelation {
    private String proxyClass;
    private String targetClass;
    private String proxyType;
    private String projectPath;
}
