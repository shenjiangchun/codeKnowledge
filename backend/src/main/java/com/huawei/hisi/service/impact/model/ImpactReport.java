package com.huawei.hisi.service.impact.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Impact report model containing the complete impact analysis results.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpactReport {

    /**
     * Unique report identifier
     */
    private String reportId;

    /**
     * Original change request
     */
    private ChangeRequest changeRequest;

    /**
     * Analysis timestamp
     */
    private LocalDateTime analysisTime;

    /**
     * List of callers that directly call the changed method
     */
    private List<Caller> directCallers;

    /**
     * Complete call chains from entry points to the changed method
     */
    private List<CallChain> callChains;

    /**
     * Affected classes (classes that might be impacted)
     */
    private List<String> affectedClasses;

    /**
     * Affected methods (methods that might need testing)
     */
    private List<String> affectedMethods;

    /**
     * Affected URIs (HTTP endpoints that might be impacted)
     */
    private List<String> affectedUris;

    /**
     * Affected MQ endpoints (message queue consumers/producers)
     */
    private List<String> affectedMQEndpoints;

    /**
     * Maximum call chain depth discovered
     */
    private int maxDepth;

    /**
     * Total number of impacted components
     */
    private int totalImpactedCount;

    /**
     * Risk assessment summary
     */
    private RiskAssessment riskAssessment;

    /**
     * Generated test case recommendations
     */
    private List<TestCase> testCases;

    /**
     * Additional metadata
     */
    private Map<String, Object> metadata;

    /**
     * Analysis status
     */
    @Builder.Default
    private AnalysisStatus status = AnalysisStatus.COMPLETED;

    /**
     * Error message if analysis failed
     */
    private String errorMessage;

    /**
     * Analysis duration in milliseconds
     */
    private long durationMs;

    /**
     * Analysis status enumeration
     */
    public enum AnalysisStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        PARTIAL
    }
}