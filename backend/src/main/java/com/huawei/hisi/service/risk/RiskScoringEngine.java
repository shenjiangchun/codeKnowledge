package com.huawei.hisi.service.risk;

import com.huawei.hisi.service.impact.model.ImpactReport;
import com.huawei.hisi.service.risk.model.RiskScore;

import java.util.List;
import java.util.Map;

/**
 * 风险评分引擎接口
 * 用于量化代码变更的风险评估
 */
public interface RiskScoringEngine {

    /**
     * 计算整体风险评分
     *
     * @param request 风险评估请求
     * @return 风险评分结果
     */
    RiskScore calculateRiskScore(RiskScoringRequest request);

    /**
     * 根据影响报告计算风险评分
     *
     * @param impactReport 影响分析报告
     * @return 风险评分结果
     */
    RiskScore calculateFromImpactReport(ImpactReport impactReport);

    /**
     * 计算影响范围分数
     *
     * @param callerCount 调用方数量
     * @param calleeCount 被调用方数量
     * @return 影响范围分数 (0-100)
     */
    int calculateImpactScopeScore(int callerCount, int calleeCount);

    /**
     * 计算业务重要性分数
     *
     * @param className    类名
     * @param methodName   方法名
     * @param businessTags 业务标签
     * @return 业务重要性分数 (0-100)
     */
    int calculateBusinessCriticalityScore(String className, String methodName, List<String> businessTags);

    /**
     * 计算代码复杂度分数
     *
     * @param cyclomaticComplexity 圈复杂度
     * @param linesOfCode          代码行数
     * @return 复杂度分数 (0-100)
     */
    int calculateComplexityScore(int cyclomaticComplexity, int linesOfCode);

    /**
     * 计算测试覆盖率分数
     *
     * @param coveragePercentage 覆盖率百分比
     * @return 测试覆盖率分数 (0-100, 越低风险越高)
     */
    int calculateTestCoverageScore(double coveragePercentage);

    /**
     * 确定整体风险等级
     *
     * @param scores 各维度分数
     * @return 风险等级
     */
    RiskScore.RiskLevel determineOverallRisk(Map<String, Integer> scores);

    /**
     * 生成缓解建议
     *
     * @param riskScore 风险评分
     * @return 缓解建议列表
     */
    List<String> generateMitigationRecommendations(RiskScore riskScore);

    /**
     * 风险评估请求
     */
    class RiskScoringRequest {
        private String changeId;
        private String className;
        private String methodName;
        private int callerCount;
        private int calleeCount;
        private int cyclomaticComplexity;
        private int linesOfCode;
        private double testCoverage;
        private List<String> businessTags;
        private int recentChangeCount;
        private List<String> dependencies;

        public RiskScoringRequest() {}

        // Getters and Setters
        public String getChangeId() { return changeId; }
        public void setChangeId(String changeId) { this.changeId = changeId; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public int getCallerCount() { return callerCount; }
        public void setCallerCount(int callerCount) { this.callerCount = callerCount; }
        public int getCalleeCount() { return calleeCount; }
        public void setCalleeCount(int calleeCount) { this.calleeCount = calleeCount; }
        public int getCyclomaticComplexity() { return cyclomaticComplexity; }
        public void setCyclomaticComplexity(int cyclomaticComplexity) { this.cyclomaticComplexity = cyclomaticComplexity; }
        public int getLinesOfCode() { return linesOfCode; }
        public void setLinesOfCode(int linesOfCode) { this.linesOfCode = linesOfCode; }
        public double getTestCoverage() { return testCoverage; }
        public void setTestCoverage(double testCoverage) { this.testCoverage = testCoverage; }
        public List<String> getBusinessTags() { return businessTags; }
        public void setBusinessTags(List<String> businessTags) { this.businessTags = businessTags; }
        public int getRecentChangeCount() { return recentChangeCount; }
        public void setRecentChangeCount(int recentChangeCount) { this.recentChangeCount = recentChangeCount; }
        public List<String> getDependencies() { return dependencies; }
        public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
    }
}