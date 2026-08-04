package com.huawei.hisi.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 日志分析报告 DTO
 */
@Data
public class LogAnalysisReport {
    /**
     * 报告 ID
     */
    private Long id;

    /**
     * 报告编号
     */
    private String reportNo;

    /**
     * 查询时间
     */
    private LocalDateTime queryTime;

    /**
     * 查询条件（JSON 格式）
     */
    private String queryCondition;

    /**
     * 日志摘要
     */
    private String logSummary;

    /**
     * 错误堆栈
     */
    private String errorStack;

    /**
     * 根 URI
     */
    private String rootUri;

    /**
     * 调用链（JSON 格式）
     */
    private String callChain;

    /**
     * 根因分析
     */
    private String rootCause;

    /**
     * 修复建议
     */
    private String fixSuggestion;

    /**
     * 状态
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 相关代码列表
     */
    private List<RelatedCode> relatedCode;

    /**
     * 相关代码 DTO
     */
    @Data
    public static class RelatedCode {
        /**
         * 类名
         */
        private String className;

        /**
         * 方法名
         */
        private String methodName;

        /**
         * 行号
         */
        private Integer lineNumber;

        /**
         * 代码片段
         */
        private String codeSnippet;
    }
}