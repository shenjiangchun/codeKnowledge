package com.huawei.hisi.service.impact;

import com.huawei.hisi.service.impact.model.ChangeRequest;
import com.huawei.hisi.service.impact.model.ImpactReport;
import com.huawei.hisi.service.impact.model.RiskAssessment;
import com.huawei.hisi.service.impact.model.TestCase;

import java.util.List;

/**
 * Impact prediction service interface for analyzing code change impacts.
 * Provides static call chain tracing, risk assessment, and test case generation.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface ImpactPredictionService {

    /**
     * Analyze a code change and generate impact report.
     *
     * This method performs:
     * 1. Change parsing - extract change details
     * 2. Static call chain tracing - find all callers
     * 3. Impact scope analysis - identify affected components
     * 4. Risk assessment - evaluate change risk
     * 5. Report generation - produce comprehensive impact report
     *
     * @param request the change request containing change details
     * @return impact report with full analysis results
     * @throws IllegalArgumentException if request is invalid
     */
    ImpactReport analyzeChange(ChangeRequest request);

    /**
     * Generate test case recommendations based on impact report.
     *
     * Generates test cases for:
     * - Direct callers of the changed method
     * - Entry points affected by the change
     * - Boundary conditions and edge cases
     * - Integration test scenarios
     *
     * @param report the impact report from analyzeChange
     * @return list of recommended test cases
     */
    List<TestCase> generateTestCases(ImpactReport report);

    /**
     * Assess risk level for a code change.
     *
     * Calculates risk based on:
     * - Impact scope (number of affected components)
     * - Business criticality (importance of affected functions)
     * - Code complexity (complexity of the change)
     * - Test coverage (existing test coverage)
     *
     * @param request the change request
     * @return risk assessment with score and recommendations
     */
    RiskAssessment assessRisk(ChangeRequest request);

    /**
     * Quick impact preview for a method change.
     *
     * Provides a lightweight analysis without full call chain tracing.
     * Useful for quick checks before detailed analysis.
     *
     * @param className full qualified class name
     * @param methodName method name
     * @return simplified impact preview
     */
    ImpactPreview previewImpact(String className, String methodName);

    /**
     * Impact preview model for quick analysis.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class ImpactPreview {
        /**
         * Number of direct callers
         */
        private int directCallerCount;

        /**
         * Number of affected URIs
         */
        private int affectedUriCount;

        /**
         * Estimated risk level
         */
        private String estimatedRiskLevel;

        /**
         * Quick recommendation
         */
        private String recommendation;
    }
}