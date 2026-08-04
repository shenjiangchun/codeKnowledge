package com.huawei.hisi.service.impact.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskAssessment 单元测试
 *
 * 测试风险评估模型
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("RiskAssessment 单元测试")
class RiskAssessmentTest {

    @Test
    @DisplayName("测试构建 RiskAssessment - 基本属性")
    void testBuildBasicRiskAssessment() {
        RiskAssessment assessment = RiskAssessment.builder()
                .overallRiskLevel(RiskAssessment.RiskLevel.HIGH)
                .overallRiskScore(65)
                .impactScopeScore(70)
                .businessCriticalityScore(60)
                .codeComplexityScore(50)
                .testCoverageScore(80)
                .confidenceLevel(85)
                .build();

        assertEquals(RiskAssessment.RiskLevel.HIGH, assessment.getOverallRiskLevel());
        assertEquals(65, assessment.getOverallRiskScore());
        assertEquals(70, assessment.getImpactScopeScore());
        assertEquals(60, assessment.getBusinessCriticalityScore());
        assertEquals(50, assessment.getCodeComplexityScore());
        assertEquals(80, assessment.getTestCoverageScore());
        assertEquals(85, assessment.getConfidenceLevel());
    }

    @Test
    @DisplayName("测试 RiskLevel 枚举值")
    void testRiskLevelEnum() {
        assertEquals(4, RiskAssessment.RiskLevel.values().length);
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.valueOf("LOW"));
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.valueOf("MEDIUM"));
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.valueOf("HIGH"));
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.valueOf("CRITICAL"));
    }

    @Test
    @DisplayName("测试 RiskLevel.getLabel 方法")
    void testRiskLevelGetLabel() {
        assertEquals("Low", RiskAssessment.RiskLevel.LOW.getLabel());
        assertEquals("Medium", RiskAssessment.RiskLevel.MEDIUM.getLabel());
        assertEquals("High", RiskAssessment.RiskLevel.HIGH.getLabel());
        assertEquals("Critical", RiskAssessment.RiskLevel.CRITICAL.getLabel());
    }

    @Test
    @DisplayName("测试 RiskLevel.fromScore - LOW")
    void testRiskLevelFromScoreLow() {
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(0));
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(10));
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(25));
    }

    @Test
    @DisplayName("测试 RiskLevel.fromScore - MEDIUM")
    void testRiskLevelFromScoreMedium() {
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(26));
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(40));
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(50));
    }

    @Test
    @DisplayName("测试 RiskLevel.fromScore - HIGH")
    void testRiskLevelFromScoreHigh() {
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(51));
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(65));
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(75));
    }

    @Test
    @DisplayName("测试 RiskLevel.fromScore - CRITICAL")
    void testRiskLevelFromScoreCritical() {
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(76));
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(90));
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(100));
    }

    @Test
    @DisplayName("测试 RiskLevel.fromScore - 边界值")
    void testRiskLevelFromScoreBoundary() {
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(-1));
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(150));
    }

    @Test
    @DisplayName("测试构建 RiskAssessment - 包含风险项")
    void testBuildWithRiskItems() {
        RiskAssessment.RiskItem riskItem = RiskAssessment.RiskItem.builder()
                .riskId("risk-001")
                .category(RiskAssessment.RiskCategory.FUNCTIONAL)
                .description("可能导致空指针异常")
                .severity(RiskAssessment.RiskSeverity.MAJOR)
                .build();

        RiskAssessment assessment = RiskAssessment.builder()
                .risks(List.of(riskItem))
                .build();

        assertNotNull(assessment.getRisks());
        assertEquals(1, assessment.getRisks().size());
        assertEquals("risk-001", assessment.getRisks().get(0).getRiskId());
    }

    @Test
    @DisplayName("测试构建 RiskAssessment - 包含建议")
    void testBuildWithRecommendations() {
        RiskAssessment assessment = RiskAssessment.builder()
                .recommendations(List.of(
                        "增加单元测试覆盖",
                        "进行代码审查",
                        "执行回归测试"
                ))
                .build();

        assertNotNull(assessment.getRecommendations());
        assertEquals(3, assessment.getRecommendations().size());
    }

    @Test
    @DisplayName("测试构建 RiskAssessment - 包含评分明细")
    void testBuildWithScoreBreakdown() {
        RiskAssessment assessment = RiskAssessment.builder()
                .scoreBreakdown(Map.of(
                        "impactScore", 70,
                        "complexityScore", 50,
                        "coverageScore", 80
                ))
                .build();

        assertNotNull(assessment.getScoreBreakdown());
        assertEquals(70, assessment.getScoreBreakdown().get("impactScore"));
    }

    @Test
    @DisplayName("测试 RiskCategory 枚举")
    void testRiskCategoryEnum() {
        assertEquals(6, RiskAssessment.RiskCategory.values().length);
        assertEquals(RiskAssessment.RiskCategory.FUNCTIONAL, RiskAssessment.RiskCategory.valueOf("FUNCTIONAL"));
        assertEquals(RiskAssessment.RiskCategory.PERFORMANCE, RiskAssessment.RiskCategory.valueOf("PERFORMANCE"));
        assertEquals(RiskAssessment.RiskCategory.SECURITY, RiskAssessment.RiskCategory.valueOf("SECURITY"));
        assertEquals(RiskAssessment.RiskCategory.COMPATIBILITY, RiskAssessment.RiskCategory.valueOf("COMPATIBILITY"));
        assertEquals(RiskAssessment.RiskCategory.RELIABILITY, RiskAssessment.RiskCategory.valueOf("RELIABILITY"));
        assertEquals(RiskAssessment.RiskCategory.TEST, RiskAssessment.RiskCategory.valueOf("TEST"));
    }

    @Test
    @DisplayName("测试 RiskSeverity 枚举")
    void testRiskSeverityEnum() {
        assertEquals(4, RiskAssessment.RiskSeverity.values().length);
        assertEquals(RiskAssessment.RiskSeverity.MINOR, RiskAssessment.RiskSeverity.valueOf("MINOR"));
        assertEquals(RiskAssessment.RiskSeverity.MODERATE, RiskAssessment.RiskSeverity.valueOf("MODERATE"));
        assertEquals(RiskAssessment.RiskSeverity.MAJOR, RiskAssessment.RiskSeverity.valueOf("MAJOR"));
        assertEquals(RiskAssessment.RiskSeverity.SEVERE, RiskAssessment.RiskSeverity.valueOf("SEVERE"));
    }

    @Test
    @DisplayName("测试 RiskItem 构建")
    void testRiskItemBuild() {
        RiskAssessment.RiskItem item = RiskAssessment.RiskItem.builder()
                .riskId("RISK-001")
                .category(RiskAssessment.RiskCategory.SECURITY)
                .description("SQL注入风险")
                .severity(RiskAssessment.RiskSeverity.SEVERE)
                .affectedComponents(List.of("UserService", "OrderService"))
                .mitigations(List.of("使用参数化查询", "输入验证"))
                .addressed(false)
                .build();

        assertEquals("RISK-001", item.getRiskId());
        assertEquals(RiskAssessment.RiskCategory.SECURITY, item.getCategory());
        assertEquals(RiskAssessment.RiskSeverity.SEVERE, item.getSeverity());
        assertEquals(2, item.getAffectedComponents().size());
        assertEquals(2, item.getMitigations().size());
        assertFalse(item.isAddressed());
    }

    @Test
    @DisplayName("测试空构造和 setter")
    void testEmptyConstructorAndSetter() {
        RiskAssessment assessment = new RiskAssessment();
        assessment.setOverallRiskLevel(RiskAssessment.RiskLevel.MEDIUM);
        assessment.setOverallRiskScore(40);
        assessment.setConfidenceLevel(75);
        assessment.setAssessmentTime(LocalDateTime.now());

        assertEquals(RiskAssessment.RiskLevel.MEDIUM, assessment.getOverallRiskLevel());
        assertEquals(40, assessment.getOverallRiskScore());
        assertEquals(75, assessment.getConfidenceLevel());
    }

    @Test
    @DisplayName("测试 RiskItem setter")
    void testRiskItemSetter() {
        RiskAssessment.RiskItem item = new RiskAssessment.RiskItem();
        item.setRiskId("test-risk");
        item.setCategory(RiskAssessment.RiskCategory.PERFORMANCE);
        item.setSeverity(RiskAssessment.RiskSeverity.MODERATE);
        item.setAddressed(true);

        assertEquals("test-risk", item.getRiskId());
        assertEquals(RiskAssessment.RiskCategory.PERFORMANCE, item.getCategory());
        assertTrue(item.isAddressed());
    }

    @Test
    @DisplayName("测试评估时间")
    void testAssessmentTime() {
        LocalDateTime now = LocalDateTime.now();
        RiskAssessment assessment = RiskAssessment.builder()
                .assessmentTime(now)
                .build();

        assertEquals(now, assessment.getAssessmentTime());
    }

    @Test
    @DisplayName("测试全参数构造")
    void testAllArgsConstructor() {
        RiskAssessment assessment = new RiskAssessment(
                RiskAssessment.RiskLevel.HIGH,
                65,
                70,
                60,
                50,
                80,
                List.of(),
                List.of(),
                Map.of(),
                85,
                LocalDateTime.now()
        );

        assertEquals(RiskAssessment.RiskLevel.HIGH, assessment.getOverallRiskLevel());
        assertEquals(65, assessment.getOverallRiskScore());
        assertEquals(85, assessment.getConfidenceLevel());
    }

    @Test
    @DisplayName("测试已处理的风险项")
    void testAddressedRiskItem() {
        RiskAssessment.RiskItem item = RiskAssessment.RiskItem.builder()
                .riskId("risk-001")
                .addressed(true)
                .build();

        assertTrue(item.isAddressed());
    }
}