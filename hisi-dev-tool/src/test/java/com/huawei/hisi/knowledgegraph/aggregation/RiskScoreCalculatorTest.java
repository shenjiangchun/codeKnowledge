package com.huawei.hisi.knowledgegraph.aggregation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoreCalculatorTest {

    @Test
    @DisplayName("圈复杂度 0-10 映射到 0.0-0.25")
    void normalizeComplexity_low() {
        assertThat(RiskScoreCalculator.normalizeComplexity(0)).isEqualTo(0.0);
        assertThat(RiskScoreCalculator.normalizeComplexity(5)).isCloseTo(0.125, within(0.01));
        assertThat(RiskScoreCalculator.normalizeComplexity(10)).isCloseTo(0.25, within(0.01));
    }

    @Test
    @DisplayName("圈复杂度 11-20 映射到 0.25-0.5")
    void normalizeComplexity_medium() {
        assertThat(RiskScoreCalculator.normalizeComplexity(11)).isCloseTo(0.275, within(0.01));
        assertThat(RiskScoreCalculator.normalizeComplexity(15)).isCloseTo(0.375, within(0.01));
        assertThat(RiskScoreCalculator.normalizeComplexity(20)).isCloseTo(0.5, within(0.01));
    }

    @Test
    @DisplayName("圈复杂度 21-50 映射到 0.5-0.85")
    void normalizeComplexity_high() {
        assertThat(RiskScoreCalculator.normalizeComplexity(21)).isCloseTo(0.512, within(0.01));
        assertThat(RiskScoreCalculator.normalizeComplexity(35)).isCloseTo(0.675, within(0.01));
        assertThat(RiskScoreCalculator.normalizeComplexity(50)).isCloseTo(0.85, within(0.01));
    }

    @Test
    @DisplayName("圈复杂度 50+ 映射到 0.85-1.0")
    void normalizeComplexity_extreme() {
        assertThat(RiskScoreCalculator.normalizeComplexity(51)).isGreaterThan(0.85);
        assertThat(RiskScoreCalculator.normalizeComplexity(100)).isCloseTo(0.925, within(0.01));
        assertThat(RiskScoreCalculator.normalizeComplexity(200)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("百分位排名：均匀分布")
    void percentileRank_uniform() {
        Map<String, Integer> raw = new HashMap<>();
        raw.put("A", 10);
        raw.put("B", 20);
        raw.put("C", 30);
        raw.put("D", 40);

        var result = RiskScoreCalculator.percentileRank(raw);

        assertThat(result.get("D")).isEqualTo(1.0);  // 最大值
        assertThat(result.get("A")).isEqualTo(0.0);   // 最小值
        assertThat(result.get("B")).isCloseTo(1.0 / 3, within(0.01));
        assertThat(result.get("C")).isCloseTo(2.0 / 3, within(0.01));
    }

    @Test
    @DisplayName("百分位排名：空输入返回空映射")
    void percentileRank_empty() {
        assertThat(RiskScoreCalculator.percentileRank(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("百分位排名：单元素")
    void percentileRank_single() {
        var result = RiskScoreCalculator.percentileRank(Map.of("A", 42));
        assertThat(result.get("A")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("综合风险分计算：低复杂度 + 无churn + 无入度")
    void calculate_lowRisk() {
        double score = RiskScoreCalculator.calculate(5, 0.0, 0.0, false);

        // complexity=5 → 0.125 × 0.35 = 0.04375
        assertThat(score).isCloseTo(0.044, within(0.01));
    }

    @Test
    @DisplayName("综合风险分计算：高复杂度 + 高churn + 高入度 + 循环依赖")
    void calculate_highRisk() {
        double score = RiskScoreCalculator.calculate(45, 1.0, 1.0, true);

        // complexity=45 → ~0.792 × 0.35 + 1.0 × 0.35 + 1.0 × 0.20 + 1.0 × 0.10
        assertThat(score).isCloseTo(0.927, within(0.01));
    }

    @Test
    @DisplayName("综合风险分：最大风险 = 1.0")
    void calculate_maxRisk() {
        double score = RiskScoreCalculator.calculate(200, 1.0, 1.0, true);
        assertThat(score).isCloseTo(1.0, within(0.001));
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
