package com.huawei.hisi.archdiagnosis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构诊断评分逻辑单测：扣分幅度、等级边界、每维度封顶。
 */
class ArchDiagnosisScorerTest {

    @Test
    @DisplayName("severity → 扣分幅度")
    void pointsBySeverity() {
        assertThat(ArchDiagnosisScorer.points("HIGH")).isEqualTo(10);
        assertThat(ArchDiagnosisScorer.points("MEDIUM")).isEqualTo(5);
        assertThat(ArchDiagnosisScorer.points("LOW")).isEqualTo(2);
        assertThat(ArchDiagnosisScorer.points("UNKNOWN")).isEqualTo(2);
    }

    @Test
    @DisplayName("等级边界 A≥90 / B≥75 / C≥60 / D<60")
    void gradeBoundaries() {
        assertThat(ArchDiagnosisScorer.grade(100)).isEqualTo("A");
        assertThat(ArchDiagnosisScorer.grade(90)).isEqualTo("A");
        assertThat(ArchDiagnosisScorer.grade(89)).isEqualTo("B");
        assertThat(ArchDiagnosisScorer.grade(75)).isEqualTo("B");
        assertThat(ArchDiagnosisScorer.grade(74)).isEqualTo("C");
        assertThat(ArchDiagnosisScorer.grade(60)).isEqualTo("C");
        assertThat(ArchDiagnosisScorer.grade(59)).isEqualTo("D");
        assertThat(ArchDiagnosisScorer.grade(0)).isEqualTo("D");
    }

    @Test
    @DisplayName("每维度封顶 25 分")
    void dimensionCap() {
        Map<String, Integer> dims = new LinkedHashMap<>();
        dims.put("module", 50); // 5 个 HIGH = 50，封顶 25
        assertThat(ArchDiagnosisScorer.healthScore(dims)).isEqualTo(75);
    }

    @Test
    @DisplayName("多维度叠加扣分")
    void multiDimension() {
        Map<String, Integer> dims = new LinkedHashMap<>();
        dims.put("project", 10); // 1 HIGH
        dims.put("module", 15);  // 3 MEDIUM
        dims.put("class", 4);    // 2 LOW
        // 10 + 15 + 4 = 29 → 100 - 29 = 71
        assertThat(ArchDiagnosisScorer.healthScore(dims)).isEqualTo(71);
    }

    @Test
    @DisplayName("空数据返回 100 分")
    void emptyDataReturnsFullScore() {
        assertThat(ArchDiagnosisScorer.healthScore(Map.of())).isEqualTo(100);
    }

    @Test
    @DisplayName("总分下限 10（避免大项目各维度封顶后必然 0 分）")
    void scoreFloorAtMin() {
        Map<String, Integer> dims = new LinkedHashMap<>();
        dims.put("project", 100);
        dims.put("module", 100);
        dims.put("class", 100);
        dims.put("method", 100);
        // 每维度封顶 25，总扣 100 → 扣满后不低于 10
        assertThat(ArchDiagnosisScorer.healthScore(dims)).isEqualTo(10);
    }
}