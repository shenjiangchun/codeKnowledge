package com.huawei.hisi.archdiagnosis;

import java.util.Map;

/**
 * 架构诊断健康评分——固定扣分制 + 每维度封顶。
 *
 * <p>评分规则（batch-2 D5 定案）：
 * <ul>
 *   <li>初始 100 分，按严重度扣：HIGH -10 / MEDIUM -5 / LOW -2；</li>
 *   <li>每维度封顶扣除 25 分；</li>
 *   <li>等级映射 A≥90 / B≥75 / C≥60 / D&lt;60。</li>
 * </ul>
 * 纯静态工具，便于单测。
 */
public final class ArchDiagnosisScorer {

    public static final int POINTS_HIGH = 10;
    public static final int POINTS_MEDIUM = 5;
    public static final int POINTS_LOW = 2;

    /** 每维度封顶扣除分值。 */
    public static final int DIMENSION_CAP = 25;

    /** 总分下限：避免大项目四维度都触顶时总分必然归零。 */
    public static final int MIN_SCORE = 10;

    private ArchDiagnosisScorer() {
    }

    /** 统一 severity → 扣分幅度。 */
    public static int points(String severity) {
        return switch (severity) {
            case "HIGH" -> POINTS_HIGH;
            case "MEDIUM" -> POINTS_MEDIUM;
            default -> POINTS_LOW;
        };
    }

    /** 健康总分 → ABCD 等级。 */
    public static String grade(int score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 75) {
            return "B";
        }
        if (score >= 60) {
            return "C";
        }
        return "D";
    }

    /**
     * 健康总分：每维度先封顶 25，再叠加扣分，下限 {@link #MIN_SCORE}。
     *
     * @param pointsByDimension 维度（project/module/class/method）→ 该维度累计扣分幅度
     */
    public static int healthScore(Map<String, Integer> pointsByDimension) {
        int deduct = pointsByDimension.values().stream()
                .mapToInt(v -> Math.min(v, DIMENSION_CAP))
                .sum();
        return Math.max(MIN_SCORE, 100 - deduct);
    }
}