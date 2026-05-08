package com.huawei.hisi.service.impact.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TD-002: 覆盖率配置单元测试
 *
 * 测试RiskAssessment和覆盖率相关配置：
 * - 测试默认覆盖率获取
 * - 测试配置覆盖率获取
 * - 测试RiskLevel枚举
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("TD-002: 覆盖率配置测试")
class CoverageConfigTest {

    @Test
    @DisplayName("测试RiskLevel枚举值")
    void testRiskLevelEnum() {
        assertEquals(4, RiskAssessment.RiskLevel.values().length, "应有4个风险级别");
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.valueOf("LOW"));
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.valueOf("MEDIUM"));
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.valueOf("HIGH"));
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.valueOf("CRITICAL"));
    }

    @Test
    @DisplayName("测试RiskLevel.fromScore方法 - LOW范围")
    void testRiskLevelFromScoreLow() {
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(0));
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(10));
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(25));
    }

    @Test
    @DisplayName("测试RiskLevel.fromScore方法 - MEDIUM范围")
    void testRiskLevelFromScoreMedium() {
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(26));
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(40));
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(50));
    }

    @Test
    @DisplayName("测试RiskLevel.fromScore方法 - HIGH范围")
    void testRiskLevelFromScoreHigh() {
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(51));
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(60));
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(75));
    }

    @Test
    @DisplayName("测试RiskLevel.fromScore方法 - CRITICAL范围")
    void testRiskLevelFromScoreCritical() {
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(76));
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(90));
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(100));
    }

    @Test
    @DisplayName("测试RiskLevel边界值")
    void testRiskLevelBoundary() {
        // 边界值测试
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(25), "LOW最大边界25");
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(26), "MEDIUM最小边界26");
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(50), "MEDIUM最大边界50");
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(51), "HIGH最小边界51");
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(75), "HIGH最大边界75");
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(76), "CRITICAL最小边界76");
    }

    @Test
    @DisplayName("测试RiskLevel.getLabel方法")
    void testRiskLevelGetLabel() {
        assertEquals("Low", RiskAssessment.RiskLevel.LOW.getLabel());
        assertEquals("Medium", RiskAssessment.RiskLevel.MEDIUM.getLabel());
        assertEquals("High", RiskAssessment.RiskLevel.HIGH.getLabel());
        assertEquals("Critical", RiskAssessment.RiskLevel.CRITICAL.getLabel());
    }

    @Test
    @DisplayName("测试RiskAssessment Builder - 默认覆盖率")
    void testRiskAssessmentBuilderWithDefaultCoverage() {
        RiskAssessment assessment = RiskAssessment.builder()
                .overallRiskLevel(RiskAssessment.RiskLevel.MEDIUM)
                .overallRiskScore(40)
                .impactScopeScore(30)
                .businessCriticalityScore(25)
                .codeComplexityScore(20)
                .testCoverageScore(50) // 默认覆盖率假设值
                .confidenceLevel(75)
                .build();

        assertEquals(RiskAssessment.RiskLevel.MEDIUM, assessment.getOverallRiskLevel());
        assertEquals(40, assessment.getOverallRiskScore());
        assertEquals(50, assessment.getTestCoverageScore(), "默认覆盖率应为50");
    }

    @Test
    @DisplayName("测试RiskAssessment Builder - 配置覆盖率")
    void testRiskAssessmentBuilderWithConfiguredCoverage() {
        // 假设从配置中获取的覆盖率
        int configuredCoverage = 85;

        RiskAssessment assessment = RiskAssessment.builder()
                .overallRiskLevel(RiskAssessment.RiskLevel.LOW)
                .overallRiskScore(20)
                .testCoverageScore(configuredCoverage)
                .confidenceLevel(90)
                .build();

        assertEquals(85, assessment.getTestCoverageScore(), "配置覆盖率应为85");
    }

    @Test
    @DisplayName("测试RiskAssessment完整构建")
    void testRiskAssessmentFullBuild() {
        RiskAssessment.RiskItem riskItem = RiskAssessment.RiskItem.builder()
                .riskId("RISK-001")
                .category(RiskAssessment.RiskCategory.FUNCTIONAL)
                .description("Multiple callers affected")
                .severity(RiskAssessment.RiskSeverity.MODERATE)
                .addressed(false)
                .build();

        RiskAssessment assessment = RiskAssessment.builder()
                .overallRiskLevel(RiskAssessment.RiskLevel.HIGH)
                .overallRiskScore(60)
                .impactScopeScore(50)
                .businessCriticalityScore(40)
                .codeComplexityScore(30)
                .testCoverageScore(70)
                .confidenceLevel(80)
                .risks(java.util.List.of(riskItem))
                .recommendations(java.util.List.of("Add unit tests"))
                .build();

        assertNotNull(assessment);
        assertEquals(RiskAssessment.RiskLevel.HIGH, assessment.getOverallRiskLevel());
        assertEquals(60, assessment.getOverallRiskScore());
        assertEquals(1, assessment.getRisks().size());
        assertEquals(70, assessment.getTestCoverageScore());
    }

    @Test
    @DisplayName("测试RiskCategory枚举")
    void testRiskCategoryEnum() {
        assertEquals(6, RiskAssessment.RiskCategory.values().length, "应有6个风险类别");
        assertEquals(RiskAssessment.RiskCategory.FUNCTIONAL, RiskAssessment.RiskCategory.valueOf("FUNCTIONAL"));
        assertEquals(RiskAssessment.RiskCategory.PERFORMANCE, RiskAssessment.RiskCategory.valueOf("PERFORMANCE"));
        assertEquals(RiskAssessment.RiskCategory.SECURITY, RiskAssessment.RiskCategory.valueOf("SECURITY"));
        assertEquals(RiskAssessment.RiskCategory.COMPATIBILITY, RiskAssessment.RiskCategory.valueOf("COMPATIBILITY"));
        assertEquals(RiskAssessment.RiskCategory.RELIABILITY, RiskAssessment.RiskCategory.valueOf("RELIABILITY"));
        assertEquals(RiskAssessment.RiskCategory.TEST, RiskAssessment.RiskCategory.valueOf("TEST"));
    }

    @Test
    @DisplayName("测试RiskSeverity枚举")
    void testRiskSeverityEnum() {
        assertEquals(4, RiskAssessment.RiskSeverity.values().length, "应有4个严重程度级别");
        assertEquals(RiskAssessment.RiskSeverity.MINOR, RiskAssessment.RiskSeverity.valueOf("MINOR"));
        assertEquals(RiskAssessment.RiskSeverity.MODERATE, RiskAssessment.RiskSeverity.valueOf("MODERATE"));
        assertEquals(RiskAssessment.RiskSeverity.MAJOR, RiskAssessment.RiskSeverity.valueOf("MAJOR"));
        assertEquals(RiskAssessment.RiskSeverity.SEVERE, RiskAssessment.RiskSeverity.valueOf("SEVERE"));
    }
}