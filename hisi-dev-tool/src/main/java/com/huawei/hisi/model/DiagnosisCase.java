package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断案例模型
 * 用于存储历史诊断案例，支持相似度匹配
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisCase {

    /**
     * 案例唯一标识
     */
    private String id;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 错误类型（如 NullPointerException, SQLException 等）
     */
    private String errorType;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 堆栈摘要
     */
    private String stackTraceSummary;

    /**
     * 根因分析
     */
    private String rootCauseAnalysis;

    /**
     * 解决方案描述
     */
    private String solutionDescription;

    /**
     * 修复代码片段列表
     */
    private List<String> fixCodeSnippets;

    /**
     * 相关类名列表
     */
    private List<String> relatedClasses;

    /**
     * 语义嵌入向量（用于相似度匹配）
     */
    private float[] semanticEmbedding;

    /**
     * 关键词标签
     */
    private List<String> tags;

    /**
     * 验证状态
     */
    private VerificationStatus verificationStatus;

    /**
     * 使用次数
     */
    private int usageCount;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 验证状态枚举
     */
    public enum VerificationStatus {
        PENDING,      // 待验证
        VERIFIED,     // 已验证有效
        INVALIDATED,  // 已验证无效
        DEPRECATED    // 已废弃
    }

    /**
     * 计算相似度分数
     */
    public double calculateSimilarity(DiagnosisCase other) {
        if (this.semanticEmbedding == null || other.semanticEmbedding == null) {
            return 0.0;
        }
        return cosineSimilarity(this.semanticEmbedding, other.semanticEmbedding);
    }

    /**
     * 余弦相似度计算
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}