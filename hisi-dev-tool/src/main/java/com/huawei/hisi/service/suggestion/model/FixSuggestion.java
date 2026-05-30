package com.huawei.hisi.service.suggestion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 修复建议模型
 * 用于存储诊断后生成的修复建议
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixSuggestion {

    /**
     * 建议ID
     */
    private String id;

    /**
     * 建议类型
     */
    private SuggestionType type;

    /**
     * 建议标题
     */
    private String title;

    /**
     * 建议描述
     */
    private String description;

    /**
     * 置信度 (0-1)
     */
    private double confidence;

    /**
     * 优先级 (1-5, 1最高)
     */
    private int priority;

    /**
     * 相关类名
     */
    private String className;

    /**
     * 相关方法名
     */
    private String methodName;

    /**
     * 建议的代码修改
     */
    private CodeChange codeChange;

    /**
     * 修复步骤
     */
    private List<FixStep> steps;

    /**
     * 参考文档链接
     */
    private List<String> references;

    /**
     * 相关历史案例ID
     */
    private String relatedCaseId;

    /**
     * 建议类型枚举
     */
    public enum SuggestionType {
        CODE_FIX,           // 代码修复
        CONFIG_CHANGE,      // 配置变更
        DEPENDENCY_UPDATE,  // 依赖更新
        REFACTORING,        // 重构建议
        BEST_PRACTICE,      // 最佳实践
        SECURITY_FIX,       // 安全修复
        PERFORMANCE_FIX     // 性能优化
    }

    /**
     * 代码变更
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeChange {
        private String filePath;
        private int startLine;
        private int endLine;
        private String originalCode;
        private String suggestedCode;
        private String changeDescription;
    }

    /**
     * 修复步骤
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FixStep {
        private int stepNumber;
        private String description;
        private String codeSnippet;
        private String verificationMethod;
    }
}