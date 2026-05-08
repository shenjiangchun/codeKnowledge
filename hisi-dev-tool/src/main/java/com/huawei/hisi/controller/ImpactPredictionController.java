package com.huawei.hisi.controller;

import com.huawei.hisi.service.impact.ImpactPredictionService;
import com.huawei.hisi.service.impact.model.*;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for impact prediction API.
 *
 * Provides endpoints for:
 * - POST /api/impact/analyze - Analyze code change impact
 * - POST /api/impact/testcases - Generate test case recommendations
 * - POST /api/impact/risk - Assess change risk
 * - GET /api/impact/preview - Quick impact preview
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/impact")
@RequiredArgsConstructor
public class ImpactPredictionController {

    private final ImpactPredictionService impactPredictionService;

    /**
     * Analyze a code change and generate impact report.
     *
     * @param request change request containing change details
     * @return impact report with full analysis results
     */
    @PostMapping("/analyze")
    public ResponseEntity<ImpactReport> analyzeChange(@Valid @RequestBody ChangeRequest request) {
        log.info("Received impact analysis request for: {}.{}", request.getClassName(), request.getMethodName());

        try {
            ImpactReport report = impactPredictionService.analyzeChange(request);

            if (report.getStatus() == ImpactReport.AnalysisStatus.FAILED) {
                log.error("Impact analysis failed: {}", report.getErrorMessage());
                return ResponseEntity.internalServerError().body(report);
            }

            log.info("Impact analysis completed successfully: {}", report.getReportId());
            return ResponseEntity.ok(report);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error during impact analysis", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Generate test case recommendations based on impact report.
     *
     * @param report impact report from previous analysis
     * @return list of recommended test cases
     */
    @PostMapping("/testcases")
    public ResponseEntity<List<TestCase>> generateTestCases(@RequestBody ImpactReport report) {
        log.info("Received test case generation request for report: {}", report.getReportId());

        try {
            List<TestCase> testCases = impactPredictionService.generateTestCases(report);
            log.info("Generated {} test cases", testCases.size());
            return ResponseEntity.ok(testCases);

        } catch (Exception e) {
            log.error("Failed to generate test cases", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Assess risk level for a code change.
     *
     * @param request change request
     * @return risk assessment with score and recommendations
     */
    @PostMapping("/risk")
    public ResponseEntity<RiskAssessment> assessRisk(@Valid @RequestBody ChangeRequest request) {
        log.info("Received risk assessment request for: {}.{}", request.getClassName(), request.getMethodName());

        try {
            RiskAssessment assessment = impactPredictionService.assessRisk(request);
            log.info("Risk assessment completed: level={}, score={}",
                    assessment.getOverallRiskLevel(), assessment.getOverallRiskScore());
            return ResponseEntity.ok(assessment);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to assess risk", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Quick impact preview for a method change.
     *
     * @param className full qualified class name
     * @param methodName method name
     * @return simplified impact preview
     */
    @GetMapping("/preview")
    public ResponseEntity<ImpactPredictionService.ImpactPreview> previewImpact(
            @RequestParam String className,
            @RequestParam String methodName) {
        log.info("Received impact preview request for: {}.{}", className, methodName);

        try {
            ImpactPredictionService.ImpactPreview preview = impactPredictionService.previewImpact(className, methodName);
            return ResponseEntity.ok(preview);

        } catch (Exception e) {
            log.error("Failed to generate impact preview", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Batch analyze multiple changes.
     *
     * @param requests list of change requests
     * @return list of impact reports
     */
    @PostMapping("/batch")
    public ResponseEntity<List<ImpactReport>> batchAnalyze(@RequestBody List<ChangeRequest> requests) {
        log.info("Received batch analysis request with {} changes", requests.size());

        try {
            List<ImpactReport> reports = requests.stream()
                    .map(impactPredictionService::analyzeChange)
                    .toList();

            log.info("Batch analysis completed: {} reports generated", reports.size());
            return ResponseEntity.ok(reports);

        } catch (Exception e) {
            log.error("Batch analysis failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get summary statistics for an impact report.
     *
     * @param reportId report ID
     * @return summary statistics
     */
    @GetMapping("/summary/{reportId}")
    public ResponseEntity<Map<String, Object>> getSummary(@PathVariable String reportId) {
        log.info("Received summary request for report: {}", reportId);

        // Return sample summary structure
        Map<String, Object> summary = Map.of(
                "reportId", reportId,
                "status", "completed",
                "directCallerCount", 5,
                "affectedUriCount", 2,
                "riskLevel", "MEDIUM",
                "testCaseCount", 8
        );

        return ResponseEntity.ok(summary);
    }
}