package com.huawei.hisi.service.risk.impl;

import com.huawei.hisi.service.impact.model.ImpactReport;
import com.huawei.hisi.service.risk.RiskScoringEngine;
import com.huawei.hisi.service.risk.model.RiskScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 风险评分引擎实现
 * 量化代码变更的风险评估
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskScoringEngineImpl implements RiskScoringEngine {

    // 权重配置
    private static final double WEIGHT_IMPACT_SCOPE = 0.25;
    private static final double WEIGHT_BUSINESS_CRITICALITY = 0.20;
    private static final double WEIGHT_CODE_COMPLEXITY = 0.20;
    private static final double WEIGHT_TEST_COVERAGE = 0.20;
    private static final double WEIGHT_CHANGE_FREQUENCY = 0.10;
    private static final double WEIGHT_DEPENDENCY = 0.05;

    // 业务关键类/方法模式
    private static final List<String> CRITICAL_PATTERNS = Arrays.asList(
            "Controller", "Service", "Manager", "Handler", "Processor",
            "pay", "order", "transaction", "auth", "login", "security"
    );

    @Override
    public RiskScore calculateRiskScore(RiskScoringRequest request) {
        Map<String, Integer> scores = new LinkedHashMap<>();

        // 计算各维度分数
        int impactScopeScore = calculateImpactScopeScore(request.getCallerCount(), request.getCalleeCount());
        int businessCriticalityScore = calculateBusinessCriticalityScore(
                request.getClassName(), request.getMethodName(), request.getBusinessTags());
        int complexityScore = calculateComplexityScore(
                request.getCyclomaticComplexity(), request.getLinesOfCode());
        int testCoverageScore = calculateTestCoverageScore(request.getTestCoverage());
        int changeFrequencyScore = calculateChangeFrequencyScore(request.getRecentChangeCount());
        int dependencyRiskScore = calculateDependencyRiskScore(request.getDependencies());

        scores.put("impactScope", impactScopeScore);
        scores.put("businessCriticality", businessCriticalityScore);
        scores.put("codeComplexity", complexityScore);
        scores.put("testCoverage", testCoverageScore);
        scores.put("changeFrequency", changeFrequencyScore);
        scores.put("dependencyRisk", dependencyRiskScore);

        // 计算加权总分
        int overallScore = calculateWeightedScore(scores);

        // 确定风险等级
        RiskScore.RiskLevel riskLevel = determineOverallRisk(scores);

        // 生成风险项
        List<RiskScore.RiskItem> riskItems = generateRiskItems(scores);

        // 生成缓解建议
        List<String> recommendations = generateMitigationRecommendations(
                RiskScore.builder()
                        .overallRiskScore(overallScore)
                        .overallRiskLevel(riskLevel)
                        .scoreBreakdown(scores)
                        .riskItems(riskItems)
                        .build()
        );

        RiskScore riskScore = RiskScore.builder()
                .id(UUID.randomUUID().toString())
                .changeId(request.getChangeId())
                .overallRiskLevel(riskLevel)
                .overallRiskScore(overallScore)
                .impactScopeScore(impactScopeScore)
                .businessCriticalityScore(businessCriticalityScore)
                .codeComplexityScore(complexityScore)
                .testCoverageScore(testCoverageScore)
                .changeFrequencyScore(changeFrequencyScore)
                .dependencyRiskScore(dependencyRiskScore)
                .scoreBreakdown(scores)
                .riskItems(riskItems)
                .recommendations(recommendations)
                .confidenceLevel(85)
                .assessmentTime(LocalDateTime.now())
                .build();

        log.info("Calculated risk score: {} ({}) for changeId={}",
                overallScore, riskLevel, request.getChangeId());

        return riskScore;
    }

    @Override
    public RiskScore calculateFromImpactReport(ImpactReport impactReport) {
        if (impactReport == null) {
            return RiskScore.builder()
                    .overallRiskLevel(RiskScore.RiskLevel.LOW)
                    .overallRiskScore(0)
                    .build();
        }

        RiskScoringRequest request = new RiskScoringRequest();
        // Use reportId as the identifier
        request.setChangeId(impactReport.getReportId());
        request.setCallerCount(impactReport.getDirectCallers() != null ?
                impactReport.getDirectCallers().size() : 0);
        // Use callChains for indirect callers count
        request.setCalleeCount(impactReport.getCallChains() != null ?
                impactReport.getCallChains().size() : 0);

        // Add additional info from ChangeRequest if available
        if (impactReport.getChangeRequest() != null) {
            request.setClassName(impactReport.getChangeRequest().getClassName());
            request.setMethodName(impactReport.getChangeRequest().getMethodName());
        }

        return calculateRiskScore(request);
    }

    @Override
    public int calculateImpactScopeScore(int callerCount, int calleeCount) {
        // 影响范围越大，风险越高
        int totalImpact = callerCount + calleeCount;
        if (totalImpact <= 1) return 10;
        if (totalImpact <= 3) return 25;
        if (totalImpact <= 5) return 40;
        if (totalImpact <= 10) return 60;
        if (totalImpact <= 20) return 80;
        return 95;
    }

    @Override
    public int calculateBusinessCriticalityScore(String className, String methodName, List<String> businessTags) {
        int score = 30; // 基础分

        // 检查类名是否匹配关键模式
        if (className != null) {
            for (String pattern : CRITICAL_PATTERNS) {
                if (className.contains(pattern)) {
                    score += 15;
                    break;
                }
            }
        }

        // 检查方法名是否匹配关键模式
        if (methodName != null) {
            String lowerMethodName = methodName.toLowerCase();
            for (String pattern : CRITICAL_PATTERNS) {
                if (lowerMethodName.contains(pattern.toLowerCase())) {
                    score += 10;
                    break;
                }
            }
        }

        // 检查业务标签
        if (businessTags != null && !businessTags.isEmpty()) {
            score += Math.min(20, businessTags.size() * 5);
        }

        return Math.min(100, score);
    }

    @Override
    public int calculateComplexityScore(int cyclomaticComplexity, int linesOfCode) {
        // 圈复杂度影响
        int complexityScore = 0;
        if (cyclomaticComplexity > 20) complexityScore = 40;
        else if (cyclomaticComplexity > 15) complexityScore = 30;
        else if (cyclomaticComplexity > 10) complexityScore = 20;
        else if (cyclomaticComplexity > 5) complexityScore = 10;

        // 代码行数影响
        int locScore = 0;
        if (linesOfCode > 200) locScore = 30;
        else if (linesOfCode > 100) locScore = 20;
        else if (linesOfCode > 50) locScore = 10;

        return Math.min(100, complexityScore + locScore + 30);
    }

    @Override
    public int calculateTestCoverageScore(double coveragePercentage) {
        // 测试覆盖率越低，风险越高
        if (coveragePercentage >= 80) return 15;
        if (coveragePercentage >= 60) return 30;
        if (coveragePercentage >= 40) return 50;
        if (coveragePercentage >= 20) return 70;
        return 90;
    }

    @Override
    public RiskScore.RiskLevel determineOverallRisk(Map<String, Integer> scores) {
        int overallScore = calculateWeightedScore(scores);
        return RiskScore.RiskLevel.fromScore(overallScore);
    }

    @Override
    public List<String> generateMitigationRecommendations(RiskScore riskScore) {
        List<String> recommendations = new ArrayList<>();

        if (riskScore.getTestCoverageScore() > 50) {
            recommendations.add("增加单元测试覆盖率，目标达到80%以上");
        }

        if (riskScore.getCodeComplexityScore() > 60) {
            recommendations.add("考虑重构高复杂度代码，降低圈复杂度");
        }

        if (riskScore.getImpactScopeScore() > 50) {
            recommendations.add("评估变更对调用方的影响，进行回归测试");
        }

        if (riskScore.getBusinessCriticalityScore() > 60) {
            recommendations.add("这是业务关键代码，建议进行代码审查和集成测试");
        }

        if (riskScore.getDependencyRiskScore() > 50) {
            recommendations.add("检查依赖稳定性，考虑添加依赖版本锁定");
        }

        // 根据风险等级添加建议
        switch (riskScore.getOverallRiskLevel()) {
            case CRITICAL:
                recommendations.add("严重风险：建议暂停变更，进行详细评估和架构审查");
                break;
            case HIGH:
                recommendations.add("高风险：建议进行完整的回归测试和代码审查");
                break;
            case MEDIUM:
                recommendations.add("中等风险：建议进行重点测试覆盖");
                break;
            case LOW:
                recommendations.add("低风险：可以按正常流程进行变更");
                break;
        }

        return recommendations;
    }

    /**
     * 计算变更频率分数
     */
    private int calculateChangeFrequencyScore(int recentChangeCount) {
        // 近期变更越频繁，风险越高
        if (recentChangeCount >= 10) return 80;
        if (recentChangeCount >= 5) return 60;
        if (recentChangeCount >= 3) return 40;
        if (recentChangeCount >= 1) return 20;
        return 10;
    }

    /**
     * 计算依赖风险分数
     */
    private int calculateDependencyRiskScore(List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return 10;
        }
        // 依赖越多，风险越高
        int count = dependencies.size();
        if (count >= 10) return 80;
        if (count >= 5) return 50;
        if (count >= 3) return 30;
        return 20;
    }

    /**
     * 计算加权总分
     */
    private int calculateWeightedScore(Map<String, Integer> scores) {
        double weightedScore = 0;

        weightedScore += scores.getOrDefault("impactScope", 0) * WEIGHT_IMPACT_SCOPE;
        weightedScore += scores.getOrDefault("businessCriticality", 0) * WEIGHT_BUSINESS_CRITICALITY;
        weightedScore += scores.getOrDefault("codeComplexity", 0) * WEIGHT_CODE_COMPLEXITY;
        weightedScore += scores.getOrDefault("testCoverage", 0) * WEIGHT_TEST_COVERAGE;
        weightedScore += scores.getOrDefault("changeFrequency", 0) * WEIGHT_CHANGE_FREQUENCY;
        weightedScore += scores.getOrDefault("dependencyRisk", 0) * WEIGHT_DEPENDENCY;

        return (int) Math.round(weightedScore);
    }

    /**
     * 生成风险项列表
     */
    private List<RiskScore.RiskItem> generateRiskItems(Map<String, Integer> scores) {
        List<RiskScore.RiskItem> items = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            int score = entry.getValue();
            RiskScore.RiskLevel level = RiskScore.RiskLevel.fromScore(score);

            if (score >= 50) { // 只记录中等及以上风险
                items.add(RiskScore.RiskItem.builder()
                        .category(entry.getKey())
                        .description(getRiskDescription(entry.getKey(), score))
                        .score(score)
                        .level(level)
                        .mitigation(getMitigation(entry.getKey()))
                        .build());
            }
        }

        return items;
    }

    private String getRiskDescription(String category, int score) {
        return switch (category) {
            case "impactScope" -> "影响范围较大，可能影响多个调用方";
            case "businessCriticality" -> "涉及业务关键代码";
            case "codeComplexity" -> "代码复杂度较高";
            case "testCoverage" -> "测试覆盖率不足";
            case "changeFrequency" -> "近期变更频繁";
            case "dependencyRisk" -> "依赖较多";
            default -> "存在潜在风险";
        };
    }

    private String getMitigation(String category) {
        return switch (category) {
            case "impactScope" -> "进行影响范围评估和回归测试";
            case "businessCriticality" -> "进行代码审查和业务验证";
            case "codeComplexity" -> "考虑重构降低复杂度";
            case "testCoverage" -> "增加单元测试";
            case "changeFrequency" -> "评估变更稳定性";
            case "dependencyRisk" -> "检查依赖版本兼容性";
            default -> "进行风险评估";
        };
    }
}