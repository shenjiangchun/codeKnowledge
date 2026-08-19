package com.huawei.hisi.knowledgegraph.aggregation;

import java.util.*;

/**
 * 风险评分计算器 — 分段映射归一化 + 加权求和
 *
 * 公式: riskScore = complexityNorm × 0.35 + churnNorm × 0.35 + inDegreeNorm × 0.20 + cyclePenalty × 0.10
 *
 * 圈复杂度使用分段映射（重尾分布不适合 Min-Max），
 * churn 和入度使用百分位排名（对异常值不敏感）。
 */
public class RiskScoreCalculator {

    private static final double W_COMPLEXITY = 0.35;
    private static final double W_CHURN = 0.35;
    private static final double W_IN_DEGREE = 0.20;
    private static final double W_CYCLE = 0.10;

    /**
     * 圈复杂度分段映射到 [0, 1]
     * 0-10 → 0.0-0.25, 11-20 → 0.25-0.5, 21-50 → 0.5-0.85, 50+ → 0.85-1.0
     */
    public static double normalizeComplexity(int complexity) {
        if (complexity <= 10) {
            return (complexity / 10.0) * 0.25;
        } else if (complexity <= 20) {
            return 0.25 + (complexity - 10) / 10.0 * 0.25;
        } else if (complexity <= 50) {
            return 0.50 + (complexity - 20) / 30.0 * 0.35;
        } else {
            return Math.min(1.0, 0.85 + (complexity - 50) / 100.0 * 0.15);
        }
    }

    /**
     * 百分位排名归一化到 [0, 1]
     */
    public static Map<String, Double> percentileRank(Map<String, Integer> rawValues) {
        if (rawValues.isEmpty()) return Collections.emptyMap();

        List<Integer> sorted = rawValues.values().stream().sorted().toList();
        int n = sorted.size();

        Map<String, Double> result = new LinkedHashMap<>();
        for (var entry : rawValues.entrySet()) {
            int countLess = 0;
            for (int val : sorted) {
                if (val < entry.getValue()) countLess++;
            }
            result.put(entry.getKey(), n > 1 ? (double) countLess / (n - 1) : 0.5);
        }
        return result;
    }

    /**
     * 计算综合风险分
     *
     * @param complexity    圈复杂度（原始值，内部会分段映射）
     * @param churnNorm     变更频率归一化值 [0, 1]
     * @param inDegreeNorm  入度归一化值 [0, 1]
     * @param inCycle       是否在循环依赖中
     * @return 风险分 [0, 1]
     */
    public static double calculate(int complexity, double churnNorm, double inDegreeNorm, boolean inCycle) {
        return normalizeComplexity(complexity) * W_COMPLEXITY
            + churnNorm * W_CHURN
            + inDegreeNorm * W_IN_DEGREE
            + (inCycle ? 1.0 : 0.0) * W_CYCLE;
    }
}
