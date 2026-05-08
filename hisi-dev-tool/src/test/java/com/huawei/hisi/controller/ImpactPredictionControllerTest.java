package com.huawei.hisi.controller;

import com.huawei.hisi.service.impact.ImpactPredictionService;
import com.huawei.hisi.service.impact.model.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ImpactPredictionController.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class ImpactPredictionControllerTest {

    @Mock
    private ImpactPredictionService impactPredictionService;

    @InjectMocks
    private ImpactPredictionController controller;

    private ChangeRequest testRequest;
    private ImpactReport testReport;

    @BeforeEach
    void setUp() {
        testRequest = ChangeRequest.builder()
                .className("com.huawei.hisi.service.TestService")
                .methodName("testMethod")
                .changeType(ChangeRequest.ChangeType.MODIFY)
                .build();

        testReport = ImpactReport.builder()
                .reportId("IMP-TEST-001")
                .changeRequest(testRequest)
                .status(ImpactReport.AnalysisStatus.COMPLETED)
                .directCallers(List.of())
                .callChains(List.of())
                .affectedClasses(List.of())
                .affectedMethods(List.of())
                .affectedUris(List.of())
                .maxDepth(0)
                .totalImpactedCount(0)
                .durationMs(100)
                .build();
    }

    @Test
    @DisplayName("Test analyzeChange endpoint - success")
    void testAnalyzeChangeSuccess() {
        when(impactPredictionService.analyzeChange(any(ChangeRequest.class))).thenReturn(testReport);

        ResponseEntity<ImpactReport> response = controller.analyzeChange(testRequest);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("IMP-TEST-001", response.getBody().getReportId());
        assertEquals(ImpactReport.AnalysisStatus.COMPLETED, response.getBody().getStatus());

        verify(impactPredictionService).analyzeChange(testRequest);
    }

    @Test
    @DisplayName("Test analyzeChange endpoint - failed analysis")
    void testAnalyzeChangeFailed() {
        ImpactReport failedReport = ImpactReport.builder()
                .reportId("IMP-FAILED")
                .changeRequest(testRequest)
                .status(ImpactReport.AnalysisStatus.FAILED)
                .errorMessage("Analysis error")
                .build();

        when(impactPredictionService.analyzeChange(any(ChangeRequest.class))).thenReturn(failedReport);

        ResponseEntity<ImpactReport> response = controller.analyzeChange(testRequest);

        assertNotNull(response);
        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(ImpactReport.AnalysisStatus.FAILED, response.getBody().getStatus());
    }

    @Test
    @DisplayName("Test generateTestCases endpoint")
    void testGenerateTestCases() {
        List<TestCase> testCases = List.of(
                TestCase.builder()
                        .testCaseId("TC-001")
                        .name("Unit Test")
                        .testType(TestCase.TestType.UNIT)
                        .priority(TestCase.Priority.P0)
                        .build()
        );

        when(impactPredictionService.generateTestCases(any(ImpactReport.class))).thenReturn(testCases);

        ResponseEntity<List<TestCase>> response = controller.generateTestCases(testReport);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("TC-001", response.getBody().get(0).getTestCaseId());

        verify(impactPredictionService).generateTestCases(testReport);
    }

    @Test
    @DisplayName("Test assessRisk endpoint")
    void testAssessRisk() {
        RiskAssessment assessment = RiskAssessment.builder()
                .overallRiskLevel(RiskAssessment.RiskLevel.MEDIUM)
                .overallRiskScore(45)
                .impactScopeScore(40)
                .businessCriticalityScore(50)
                .codeComplexityScore(30)
                .testCoverageScore(50)
                .build();

        when(impactPredictionService.assessRisk(any(ChangeRequest.class))).thenReturn(assessment);

        ResponseEntity<RiskAssessment> response = controller.assessRisk(testRequest);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, response.getBody().getOverallRiskLevel());
        assertEquals(45, response.getBody().getOverallRiskScore());

        verify(impactPredictionService).assessRisk(testRequest);
    }

    @Test
    @DisplayName("Test previewImpact endpoint")
    void testPreviewImpact() {
        ImpactPredictionService.ImpactPreview preview = ImpactPredictionService.ImpactPreview.builder()
                .directCallerCount(5)
                .affectedUriCount(2)
                .estimatedRiskLevel("MEDIUM")
                .recommendation("Test recommendation")
                .build();

        when(impactPredictionService.previewImpact(anyString(), anyString())).thenReturn(preview);

        ResponseEntity<ImpactPredictionService.ImpactPreview> response = controller.previewImpact(
                "com.huawei.hisi.service.TestService", "testMethod");

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(5, response.getBody().getDirectCallerCount());
        assertEquals("MEDIUM", response.getBody().getEstimatedRiskLevel());

        verify(impactPredictionService).previewImpact("com.huawei.hisi.service.TestService", "testMethod");
    }

    @Test
    @DisplayName("Test batchAnalyze endpoint")
    void testBatchAnalyze() {
        List<ChangeRequest> requests = List.of(testRequest, testRequest);

        when(impactPredictionService.analyzeChange(any(ChangeRequest.class))).thenReturn(testReport);

        ResponseEntity<List<ImpactReport>> response = controller.batchAnalyze(requests);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());

        verify(impactPredictionService, times(2)).analyzeChange(any(ChangeRequest.class));
    }

    @Test
    @DisplayName("Test getSummary endpoint")
    void testGetSummary() {
        ResponseEntity<java.util.Map<String, Object>> response = controller.getSummary("IMP-TEST-001");

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("IMP-TEST-001", response.getBody().get("reportId"));
    }
}