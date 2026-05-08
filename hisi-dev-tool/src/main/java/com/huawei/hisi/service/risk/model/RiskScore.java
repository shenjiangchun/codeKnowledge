package com.huawei.hisi.service.risk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 风险评分模型
 * 用于量化代码变更的风险评估
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskScore {

    /**
     * 评估ID
     */
    private String id;

    /**
     * 整体风险等级
     */
    private RiskLevel overallRiskLevel;

    /**
     * 整体风险分数 (0-100)
     */
    private int overallRiskScore;

    /**
     * 影响范围分数 (0-100)
     */
    private int impactScopeScore;

    /**
     * 业务重要性分数 (0-100)
     */
    private int businessCriticalityScore;

    /**
     * 代码复杂度分数 (0-100)
     */
    private int codeComplexityScore;

    /**
     * 测试覆盖率分数 (0-100)
     */
    private int testCoverageScore;

    /**
     * 变更频率分数 (0-100)
     */
    private int changeFrequencyScore;

    /**
     * 依赖风险分数 (0-100)
     */
    private int dependencyRiskScore;

    /**
     * 风险项列表
     */
    private List<RiskItem> riskItems;

    /**
     * 缓解建议
     */
    private List<String> recommendations;

    /**
     * 详细评分分解
     */
    private Map<String, Integer> scoreBreakdown;

    /**
     * 置信度 (0-100)
     */
    private int confidenceLevel;

    /**
     * 评估时间
     */
    private LocalDateTime assessmentTime;

    /**
     * 变更ID
     */
    private String changeId;

    /**
     * 风险等级枚举
     */
    public enum RiskLevel {
        LOW("低风险", 0, 25),
        MEDIUM("中等风险", 26, 50),
        HIGH("高风险", 51, 75),
        CRITICAL("严重风险", 76, 100);

        private final String description;
        private final int minScore;
        private final int maxScore;

        RiskLevel(String description, int minScore, int maxScore) {
            this.description = description;
            this.minScore = minScore;
            this.maxScore = maxScore;
        }

        public String getDescription() { return description; }
        public int getMinScore() { return minScore; }
        public int getMaxScore() { return maxScore; }

        public static RiskLevel fromScore(int score) {
            for (RiskLevel level : values()) {
                if (score >= level.minScore && score <= level.maxScore) {
                    return level;
                }
            }
            return CRITICAL;
        }
    }

    /**
     * 风险项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskItem {
        private String category;
        private String description;
        private int score;
        private RiskLevel level;
        private String mitigation;
        private List<String> affectedComponents;
    }
}