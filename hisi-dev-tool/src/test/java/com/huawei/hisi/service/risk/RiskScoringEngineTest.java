package com.huawei.hisi.service.risk;

import com.huawei.hisi.service.risk.impl.RiskScoringEngineImpl;
import com.huawei.hisi.service.risk.model.RiskScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskScoringEngine 单元测试
 */
@DisplayName("RiskScoringEngine 单元测试")
class RiskScoringEngineTest {

    private RiskScoringEngineImpl riskScoringEngine;

    @BeforeEach
    void setUp() {
        riskScoringEngine = new RiskScoringEngineImpl();
    }

    @Test
    @DisplayName("测试计算风险评分 - 低风险")
    void testCalculateRiskScore_LowRisk() {
        RiskScoringEngine.RiskScoringRequest request = createLowRiskRequest();

        RiskScore result = riskScoringEngine.calculateRiskScore(request);

        assertNotNull(result);
        assertEquals(RiskScore.RiskLevel.LOW, result.getOverallRiskLevel());
        assertTrue(result.getOverallRiskScore() <= 25);
    }

    @Test
    @DisplayName("测试计算风险评分 - 高风险")
    void testCalculateRiskScore_HighRisk() {
        RiskScoringEngine.RiskScoringRequest request = createHighRiskRequest();

        RiskScore result = riskScoringEngine.calculateRiskScore(request);

        assertNotNull(result);
        assertTrue(result.getOverallRiskLevel() == RiskScore.RiskLevel.HIGH ||
                result.getOverallRiskLevel() == RiskScore.RiskLevel.CRITICAL);
        assertTrue(result.getOverallRiskScore() >= 51);
    }

    @Test
    @DisplayName("测试计算影响范围分数")
    void testCalculateImpactScopeScore() {
        // 低影响
        int lowScore = riskScoringEngine.calculateImpactScopeScore(1, 0);
        assertTrue(lowScore <= 25);

        // 高影响
        int highScore = riskScoringEngine.calculateImpactScopeScore(15, 10);
        assertTrue(highScore >= 60);
    }

    @Test
    @DisplayName("测试计算业务重要性分数 - 关键类")
    void testCalculateBusinessCriticalityScore_CriticalClass() {
        int score = riskScoringEngine.calculateBusinessCriticalityScore(
                "PaymentService", "processPayment", Arrays.asList("payment", "transaction"));

        assertTrue(score >= 60, "PaymentService should have high business criticality");
    }

    @Test
    @DisplayName("测试计算业务重要性分数 - 普通类")
    void testCalculateBusinessCriticalityScore_NormalClass() {
        int score = riskScoringEngine.calculateBusinessCriticalityScore(
                "StringUtils", "trim", null);

        assertTrue(score < 60, "StringUtils should have lower business criticality");
    }

    @Test
    @DisplayName("测试计算代码复杂度分数")
    void testCalculateComplexityScore() {
        // 低复杂度
        int lowScore = riskScoringEngine.calculateComplexityScore(3, 30);
        assertTrue(lowScore < 50);

        // 高复杂度
        int highScore = riskScoringEngine.calculateComplexityScore(25, 250);
        assertTrue(highScore >= 60);
    }

    @Test
    @DisplayName("测试计算测试覆盖率分数")
    void testCalculateTestCoverageScore() {
        // 高覆盖率 = 低风险
        int lowRisk = riskScoringEngine.calculateTestCoverageScore(85.0);
        assertTrue(lowRisk <= 30);

        // 低覆盖率 = 高风险
        int highRisk = riskScoringEngine.calculateTestCoverageScore(15.0);
        assertTrue(highRisk >= 70);
    }

    @Test
    @DisplayName("测试确定整体风险等级")
    void testDetermineOverallRisk() {
        Map<String, Integer> lowScores = Map.of(
                "impactScope", 20,
                "businessCriticality", 30,
                "codeComplexity", 25,
                "testCoverage", 20,
                "changeFrequency", 10,
                "dependencyRisk", 15
        );

        RiskScore.RiskLevel level = riskScoringEngine.determineOverallRisk(lowScores);
        assertEquals(RiskScore.RiskLevel.LOW, level);

        Map<String, Integer> highScores = Map.of(
                "impactScope", 80,
                "businessCriticality", 70,
                "codeComplexity", 75,
                "testCoverage", 80,
                "changeFrequency", 60,
                "dependencyRisk", 50
        );

        level = riskScoringEngine.determineOverallRisk(highScores);
        assertTrue(level == RiskScore.RiskLevel.HIGH || level == RiskScore.RiskLevel.CRITICAL);
    }

    @Test
    @DisplayName("测试生成缓解建议")
    void testGenerateMitigationRecommendations() {
        RiskScore highRiskScore = RiskScore.builder()
                .overallRiskLevel(RiskScore.RiskLevel.HIGH)
                .overallRiskScore(70)
                .testCoverageScore(80)
                .codeComplexityScore(70)
                .impactScopeScore(60)
                .build();

        List<String> recommendations = riskScoringEngine.generateMitigationRecommendations(highRiskScore);

        assertNotNull(recommendations);
        assertFalse(recommendations.isEmpty());
    }

    @Test
    @DisplayName("测试风险评分完整性")
    void testRiskScoreCompleteness() {
        RiskScoringEngine.RiskScoringRequest request = new RiskScoringEngine.RiskScoringRequest();
        request.setChangeId("change-001");
        request.setCallerCount(5);
        request.setCalleeCount(3);
        request.setCyclomaticComplexity(8);
        request.setLinesOfCode(100);
        request.setTestCoverage(65.0);

        RiskScore result = riskScoringEngine.calculateRiskScore(request);

        assertNotNull(result.getId());
        assertNotNull(result.getAssessmentTime());
        assertNotNull(result.getScoreBreakdown());
        assertTrue(result.getConfidenceLevel() > 0);
    }

    private RiskScoringEngine.RiskScoringRequest createLowRiskRequest() {
        RiskScoringEngine.RiskScoringRequest request = new RiskScoringEngine.RiskScoringRequest();
        request.setChangeId("low-risk-change");
        request.setCallerCount(1);
        request.setCalleeCount(0);
        request.setCyclomaticComplexity(3);
        request.setLinesOfCode(30);
        request.setTestCoverage(85.0);
        request.setRecentChangeCount(0);
        request.setDependencies(Arrays.asList());
        return request;
    }

    private RiskScoringEngine.RiskScoringRequest createHighRiskRequest() {
        RiskScoringEngine.RiskScoringRequest request = new RiskScoringEngine.RiskScoringRequest();
        request.setChangeId("high-risk-change");
        request.setClassName("PaymentService");
        request.setMethodName("processTransaction");
        request.setCallerCount(15);
        request.setCalleeCount(10);
        request.setCyclomaticComplexity(20);
        request.setLinesOfCode(300);
        request.setTestCoverage(20.0);
        request.setBusinessTags(Arrays.asList("payment", "transaction", "critical"));
        request.setRecentChangeCount(8);
        request.setDependencies(Arrays.asList("dep1", "dep2", "dep3", "dep4", "dep5"));
        return request;
    }
}