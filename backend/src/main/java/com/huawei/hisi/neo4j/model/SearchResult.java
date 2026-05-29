package com.huawei.hisi.neo4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 检索结果模型
 * 表示混合检索的完整结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    /**
     * 原始查询文本
     */
    private String query;

    /**
     * 解析出的查询意图
     */
    private QueryIntent intent;

    /**
     * 查询类型（多规则评分识别）
     */
    private QueryType queryType;

    /**
     * 检索结果列表（方法节点，兼容旧接口）
     */
    private List<MethodNode> results;

    /**
     * 增强搜索结果列表（包含上下文信息）
     */
    private List<SearchResultItem> items;

    /**
     * 结果总数
     */
    private Integer totalCount;

    /**
     * 检索耗时（毫秒）
     */
    private Long costTimeMs;

    /**
     * 搜索提示信息（无结果或结果较少时提供建议）
     */
    private String searchTips;

    /**
     * 搜索建议列表（无结果时的替代查询建议）
     */
    private List<String> suggestions;
}
