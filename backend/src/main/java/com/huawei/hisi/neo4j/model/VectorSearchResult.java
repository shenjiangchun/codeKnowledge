package com.huawei.hisi.neo4j.model;

import java.util.List;
import java.util.Map;

/**
 * 向量搜索结果包装类
 * 用于携带搜索结果列表和每个节点的相似度分数映射
 *
 * @param <T> 结果列表中的节点类型 (MethodNode 或 SqlNode)
 */
public record VectorSearchResult<T>(List<T> results, Map<String, Double> scores) {

    /**
     * 创建不带分数的搜索结果
     */
    public static <T> VectorSearchResult<T> empty() {
        return new VectorSearchResult<>(List.of(), Map.of());
    }
}
