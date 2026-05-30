package com.huawei.hisi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 日志分析响应
 */
@Data
public class LogAnalyzeResponse {
    /**
     * 分析 ID
     */
    private String analyzeId;

    /**
     * 分析状态
     */
    private String status;

    /**
     * 错误摘要
     */
    private ErrorSummary errorSummary;

    /**
     * 根因分析
     */
    private RootCauseAnalysis rootCause;

    /**
     * 修复建议
     */
    private List<FixSuggestion> fixSuggestions;

    /**
     * 相关代码段
     */
    private List<CodeSnippet> codeSnippets;

    /**
     * 错误摘要
     */
    @Data
    public static class ErrorSummary {
        /**
         * 错误类型
         */
        private String errorType;

        /**
         * 错误消息
         */
        private String errorMessage;

        /**
         * 错误位置（类。方法）
         */
        private String errorLocation;

        /**
         * 错误描述
         */
        private String description;
    }

    /**
     * 根因分析
     */
    @Data
    public static class RootCauseAnalysis {
        /**
         * 根因类型
         */
        private String rootCauseType;

        /**
         * 根因描述
         */
        private String description;

        /**
         * 影响范围
         */
        private String impact;

        /**
         * 发生概率
         */
        private String probability;
    }

    /**
     * 修复建议
     */
    @Data
    public static class FixSuggestion {
        /**
         * 优先级（HIGH/MEDIUM/LOW）
         */
        private String priority;

        /**
         * 建议类型
         */
        private String suggestionType;

        /**
         * 建议描述
         */
        private String description;

        /**
         * 修复步骤
         */
        private List<String> steps;

        /**
         * 相关代码位置
         */
        private String codeLocation;
    }

    /**
     * 代码段
     */
    @Data
    public static class CodeSnippet {
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
         * 代码内容
         */
        private String code;

        /**
         * 问题描述
         */
        private String issue;
    }
}