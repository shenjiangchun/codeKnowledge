package com.huawei.hisi.service.impact.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Risk assessment model containing risk evaluation for a code change.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {

    /**
     * Overall risk level
     */
    private RiskLevel overallRiskLevel;

    /**
     * Overall risk score (0-100)
     */
    private int overallRiskScore;

    /**
     * Impact scope score (based on number of affected components)
     */
    private int impactScopeScore;

    /**
     * Business criticality score (based on business impact)
     */
    private int businessCriticalityScore;

    /**
     * Code complexity score (based on change complexity)
     */
    private int codeComplexityScore;

    /**
     * Test coverage score (based on existing test coverage)
     */
    private int testCoverageScore;

    /**
     * List of identified risks
     */
    private List<RiskItem> risks;

    /**
     * Risk mitigation recommendations
     */
    private List<String> recommendations;

    /**
     * Detailed scoring breakdown
     */
    private Map<String, Integer> scoreBreakdown;

    /**
     * Confidence level of the assessment (0-100)
     */
    private int confidenceLevel;

    /**
     * Assessment timestamp
     */
    private java.time.LocalDateTime assessmentTime;

    /**
     * Risk level enumeration
     */
    public enum RiskLevel {
        /**
         * Low risk: minimal impact, easy to rollback
         */
        LOW("Low", 0, 25),

        /**
         * Medium risk: moderate impact, requires testing
         */
        MEDIUM("Medium", 26, 50),

        /**
         * High risk: significant impact, requires thorough testing
         */
        HIGH("High", 51, 75),

        /**
         * Critical risk: major impact, requires approval and extensive testing
         */
        CRITICAL("Critical", 76, 100);

        private final String label;
        private final int minScore;
        private final int maxScore;

        RiskLevel(String label, int minScore, int maxScore) {
            this.label = label;
            this.minScore = minScore;
            this.maxScore = maxScore;
        }

        public String getLabel() {
            return label;
        }

        public static RiskLevel fromScore(int score) {
            for (RiskLevel level : values()) {
                if (score >= level.minScore && score <= level.maxScore) {
                    return level;
                }
            }
            return LOW;
        }
    }

    /**
     * Risk item model representing a specific identified risk
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskItem {
        /**
         * Risk identifier
         */
        private String riskId;

        /**
         * Risk category
         */
        private RiskCategory category;

        /**
         * Risk description
         */
        private String description;

        /**
         * Risk severity
         */
        private RiskSeverity severity;

        /**
         * Affected components
         */
        private List<String> affectedComponents;

        /**
         * Mitigation suggestions
         */
        private List<String> mitigations;

        /**
         * Whether the risk has been addressed
         */
        private boolean addressed;
    }

    /**
     * Risk category enumeration
     */
    public enum RiskCategory {
        /**
         * Functional risk: logic errors, data corruption
         */
        FUNCTIONAL,

        /**
         * Performance risk: slowdowns, resource leaks
         */
        PERFORMANCE,

        /**
         * Security risk: vulnerabilities, data exposure
         */
        SECURITY,

        /**
         * Compatibility risk: breaking changes, API incompatibility
         */
        COMPATIBILITY,

        /**
         * Reliability risk: crashes, error handling issues
         */
        RELIABILITY,

        /**
         * Test risk: insufficient testing, coverage gaps
         */
        TEST
    }

    /**
     * Risk severity enumeration
     */
    public enum RiskSeverity {
        MINOR,
        MODERATE,
        MAJOR,
        SEVERE
    }
}