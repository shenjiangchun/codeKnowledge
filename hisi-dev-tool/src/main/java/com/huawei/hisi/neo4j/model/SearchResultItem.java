package com.huawei.hisi.neo4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索结果项
 * 表示单个搜索结果，包含方法节点信息和关联上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultItem {

    /**
     * 节点唯一标识
     */
    private String nodeId;

    /**
     * 节点类型: "Method" / "Sql" / "EntryPoint"
     */
    private String nodeType;

    /**
     * 全限定类名
     */
    private String className;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 方法签名
     */
    private String signature;

    /**
     * 源文件路径
     */
    private String filePath;

    /**
     * 起始行号
     */
    private Integer startLine;

    /**
     * 结束行号
     */
    private Integer endLine;

    /**
     * 方法描述 (LLM 生成的自然语言描述)
     */
    private String description;

    /**
     * 相似度分数（多路召回时被 RRF 分数替换）
     */
    private Double similarityScore;

    /**
     * 命中该结果的子查询列表（多路召回时记录，前端按分词筛选用）
     */
    private List<String> matchedSubQueries;

    /**
     * 命中该结果的意图类型列表（多路召回时记录，前端展示意图标签用）
     */
    private List<IntentType> intentTypes;

    /**
     * Post-filter 注解加分（如 @Scheduled 命中加分）
     */
    private Double bonusScore;

    /**
     * 调用者摘要列表 (前3个)
     */
    private List<CallerSummary> callers;

    /**
     * 被调用者摘要列表 (前3个)
     */
    private List<CalleeSummary> callees;

    /**
     * 关联入口点列表
     */
    private List<EntryPointSummary> entryPoints;

    /**
     * 关联SQL列表
     */
    private List<SqlSummary> sqlNodes;

    /**
     * 调用者摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallerSummary {
        private String className;
        private String methodName;
        private String signature;
    }

    /**
     * 被调用者摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalleeSummary {
        private String className;
        private String methodName;
        private String signature;
    }

    /**
     * 入口点摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryPointSummary {
        private String entryType;
        private String entryKey;
    }

    /**
     * SQL摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SqlSummary {
        private String sqlId;
        private String statementType;
        private String sqlStatement;
    }
}
