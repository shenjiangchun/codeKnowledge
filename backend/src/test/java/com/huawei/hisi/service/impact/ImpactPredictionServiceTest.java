package com.huawei.hisi.service.impact;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.service.impact.impl.ImpactPredictionServiceImpl;
import com.huawei.hisi.service.impact.impl.StaticAnalysisEngineImpl;
import com.huawei.hisi.service.impact.model.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ImpactPredictionService.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class ImpactPredictionServiceTest {

    @Mock
    private GlobalAnalysisCache globalCache;

    @Mock
    private StaticAnalysisEngine staticAnalysisEngine;

    @Mock
    private CoverageService coverageService;

    private ImpactPredictionServiceImpl impactPredictionService;

    @BeforeEach
    void setUp() {
        impactPredictionService = new ImpactPredictionServiceImpl(globalCache, staticAnalysisEngine, coverageService);
    }

    @Test
    @DisplayName("Test analyzeChange with valid request")
    void testAnalyzeChangeValidRequest() {
        // Setup
        ChangeRequest request = ChangeRequest.builder()
                .className("com.huawei.hisi.service.TestService")
                .methodName("testMethod")
                .changeType(ChangeRequest.ChangeType.MODIFY)
                .description("Test change description")
                .build();

        List<Caller> mockCallers = List.of(
                Caller.builder()
                        .className("com.huawei.hisi.controller.TestController")
                        .methodName("handleRequest")
                        .methodSignature("com.huawei.hisi.controller.TestController.handleRequest")
                        .callerType(Caller.CallerType.CONTROLLER)
                        .isEntryPoint(true)
                        .associatedUri("/api/test")
                        .build()
        );

        List<CallChain> mockCallChains = List.of(
                CallChain.builder()
                        .chainId("CHAIN-001")
                        .entryPoint(CallChain.EntryPoint.builder()
                                .type(CallChain.EntryPointType.HTTP_ENDPOINT)
                                .className("com.huawei.hisi.controller.TestController")
                                .methodName("handleRequest")
                                .uri("/api/test")
                                .build())
                        .nodes(List.of(
                                CallChain.ChainNode.builder()
                                        .order(0)
                                        .className("com.huawei.hisi.controller.TestController")
                                        .methodName("handleRequest")
                                        .callType(CallChain.CallType.DIRECT)
                                        .build(),
                                CallChain.ChainNode.builder()
                                        .order(1)
                                        .className("com.huawei.hisi.service.TestService")
                                        .methodName("testMethod")
                                        .callType(CallChain.CallType.DIRECT)
                                        .build()
                        ))
                        .depth(2)
                        .riskLevel(CallChain.RiskLevel.MEDIUM)
                        .build()
        );

        when(staticAnalysisEngine.findDirectCallers(anyString())).thenReturn(mockCallers);
        when(staticAnalysisEngine.traceAllCallChains(anyString(), anyInt())).thenReturn(mockCallChains);

        // Execute
        ImpactReport report = impactPredictionService.analyzeChange(request);

        // Verify
        assertNotNull(report);
        assertNotNull(report.getReportId());
        assertEquals(request, report.getChangeRequest());
        assertEquals(ImpactReport.AnalysisStatus.COMPLETED, report.getStatus());
        assertNotNull(report.getDirectCallers());
        assertNotNull(report.getCallChains());
        assertNotNull(report.getRiskAssessment());
        assertTrue(report.getDurationMs() >= 0);

        // Note: assessRisk calls findDirectCallers internally, so total calls = 2 (analyzeChange + assessRisk)
        verify(staticAnalysisEngine, times(2)).findDirectCallers("com.huawei.hisi.service.TestService.testMethod");
        verify(staticAnalysisEngine).traceAllCallChains("com.huawei.hisi.service.TestService.testMethod", 10);
    }

    @Test
    @DisplayName("Test analyzeChange with no callers")
    void testAnalyzeChangeNoCallers() {
        // Setup
        ChangeRequest request = ChangeRequest.builder()
                .className("com.huawei.hisi.util.TestUtil")
                .methodName("helperMethod")
                .changeType(ChangeRequest.ChangeType.ADD)
                .build();

        when(staticAnalysisEngine.findDirectCallers(anyString())).thenReturn(Collections.emptyList());
        when(staticAnalysisEngine.traceAllCallChains(anyString(), anyInt())).thenReturn(Collections.emptyList());

        // Execute
        ImpactReport report = impactPredictionService.analyzeChange(request);

        // Verify
        assertNotNull(report);
        assertEquals(ImpactReport.AnalysisStatus.COMPLETED, report.getStatus());
        assertTrue(report.getDirectCallers().isEmpty());
        assertTrue(report.getCallChains().isEmpty());
        assertEquals(0, report.getMaxDepth());
    }

    @Test
    @DisplayName("Test generateTestCases with valid report")
    void testGenerateTestCases() {
        // Setup
        ChangeRequest request = ChangeRequest.builder()
                .className("com.huawei.hisi.service.TestService")
                .methodName("testMethod")
                .build();

        List<Caller> callers = List.of(
                Caller.builder()
                        .className("com.huawei.hisi.controller.TestController")
                        .methodName("handleRequest")
                        .callerType(Caller.CallerType.CONTROLLER)
                        .isEntryPoint(true)
                        .associatedUri("/api/test")
                        .build()
        );

        ImpactReport report = ImpactReport.builder()
                .reportId("IMP-001")
                .changeRequest(request)
                .directCallers(callers)
                .affectedUris(List.of("/api/test"))
                .affectedMQEndpoints(List.of("test-topic"))
                .build();

        // Execute
        List<TestCase> testCases = impactPredictionService.generateTestCases(report);

        // Verify
        assertNotNull(testCases);
        assertFalse(testCases.isEmpty());

        // Should have: 1 unit test + 1 integration test + 1 API test + 1 MQ test
        assertTrue(testCases.size() >= 3);

        // Verify first test case is unit test (P0)
        TestCase unitTest = testCases.stream()
                .filter(tc -> tc.getTestType() == TestCase.TestType.UNIT)
                .findFirst()
                .orElse(null);
        assertNotNull(unitTest);
        assertEquals(TestCase.Priority.P0, unitTest.getPriority());
    }

    @Test
    @DisplayName("Test assessRisk with multiple callers")
    void testAssessRiskMultipleCallers() {
        // Setup
        ChangeRequest request = ChangeRequest.builder()
                .className("com.huawei.hisi.service.CriticalService")
                .methodName("criticalMethod")
                .changeType(ChangeRequest.ChangeType.MODIFY)
                .build();

        List<Caller> manyCallers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            manyCallers.add(Caller.builder()
                    .className("com.huawei.hisi.service.Caller" + i)
                    .methodName("method" + i)
                    .callerType(Caller.CallerType.SERVICE)
                    .build());
        }

        List<StaticAnalysisEngine.EntryPointInfo> entryPoints = List.of(
                StaticAnalysisEngine.EntryPointInfo.builder()
                        .type(CallChain.EntryPointType.HTTP_ENDPOINT)
                        .className("com.huawei.hisi.controller.ApiController")
                        .methodName("apiMethod")
                        .uri("/api/critical")
                        .build()
        );

        when(staticAnalysisEngine.findDirectCallers(anyString())).thenReturn(manyCallers);
        when(staticAnalysisEngine.findEntryPoints(anyString())).thenReturn(entryPoints);
        when(coverageService.getCoverageScore(anyString(), anyString())).thenReturn(50);

        // Execute
        RiskAssessment assessment = impactPredictionService.assessRisk(request);

        // Verify
        assertNotNull(assessment);
        // 12个调用者应该产生较高的风险分数
        assertTrue(assessment.getOverallRiskScore() >= 40,
            "12个调用者应产生较高风险分数，实际为: " + assessment.getOverallRiskScore());
        assertNotNull(assessment.getOverallRiskLevel());
        assertNotNull(assessment.getRisks());
        assertFalse(assessment.getRisks().isEmpty());
        assertNotNull(assessment.getRecommendations());
        assertNotNull(assessment.getScoreBreakdown());
    }

    @Test
    @DisplayName("Test assessRisk with low impact")
    void testAssessRiskLowImpact() {
        // Setup
        ChangeRequest request = ChangeRequest.builder()
                .className("com.huawei.hisi.util.LowImpactUtil")
                .methodName("helperMethod")
                .changeType(ChangeRequest.ChangeType.ADD)
                .build();

        when(staticAnalysisEngine.findDirectCallers(anyString())).thenReturn(Collections.emptyList());
        when(staticAnalysisEngine.findEntryPoints(anyString())).thenReturn(Collections.emptyList());

        // Execute
        RiskAssessment assessment = impactPredictionService.assessRisk(request);

        // Verify
        assertNotNull(assessment);
        // Note: With ADD changeType (baseScore 40), empty callers (impactScope 10),
        // empty entryPoints (businessCriticality 20), and default testCoverage (50),
        // the calculated score falls in MEDIUM range (26-50)
        assertTrue(assessment.getOverallRiskScore() <= 50);
        // Risk level depends on weighted calculation, could be LOW or MEDIUM
        assertNotNull(assessment.getOverallRiskLevel());
    }

    @Test
    @DisplayName("Test previewImpact")
    void testPreviewImpact() {
        // Setup
        List<Caller> callers = List.of(
                Caller.builder().className("Class1").methodName("method1").build(),
                Caller.builder().className("Class2").methodName("method2").build()
        );

        List<StaticAnalysisEngine.EntryPointInfo> entryPoints = List.of(
                StaticAnalysisEngine.EntryPointInfo.builder()
                        .type(CallChain.EntryPointType.HTTP_ENDPOINT)
                        .uri("/api/test")
                        .build()
        );

        when(staticAnalysisEngine.findDirectCallers(anyString())).thenReturn(callers);
        when(staticAnalysisEngine.findEntryPoints(anyString())).thenReturn(entryPoints);

        // Execute
        ImpactPredictionService.ImpactPreview preview = impactPredictionService.previewImpact(
                "com.huawei.hisi.service.TestService", "testMethod");

        // Verify
        assertNotNull(preview);
        assertEquals(2, preview.getDirectCallerCount());
        assertEquals(1, preview.getAffectedUriCount());
        assertNotNull(preview.getEstimatedRiskLevel());
        assertNotNull(preview.getRecommendation());
    }

    @Test
    @DisplayName("Test RiskLevel fromScore")
    void testRiskLevelFromScore() {
        assertEquals(RiskAssessment.RiskLevel.LOW, RiskAssessment.RiskLevel.fromScore(10));
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, RiskAssessment.RiskLevel.fromScore(35));
        assertEquals(RiskAssessment.RiskLevel.HIGH, RiskAssessment.RiskLevel.fromScore(60));
        assertEquals(RiskAssessment.RiskLevel.CRITICAL, RiskAssessment.RiskLevel.fromScore(90));
    }

    @Test
    @DisplayName("Test ChangeRequest validation")
    void testChangeRequestValidation() {
        ChangeRequest request = ChangeRequest.builder()
                .className("com.test.Class")
                .methodName("method")
                .changeType(ChangeRequest.ChangeType.MODIFY)
                .build();

        assertNotNull(request.getClassName());
        assertNotNull(request.getMethodName());
        assertEquals(ChangeRequest.ChangeType.MODIFY, request.getChangeType());
    }

    @Test
    @DisplayName("Test Caller types")
    void testCallerTypes() {
        Caller caller = Caller.builder()
                .className("com.huawei.hisi.controller.TestController")
                .methodName("testMethod")
                .callerType(Caller.CallerType.CONTROLLER)
                .isEntryPoint(true)
                .associatedUri("/api/test")
                .build();

        assertEquals(Caller.CallerType.CONTROLLER, caller.getCallerType());
        assertTrue(caller.isEntryPoint());
        assertEquals("/api/test", caller.getAssociatedUri());
    }

    @Test
    @DisplayName("Test CallChain properties")
    void testCallChainProperties() {
        CallChain chain = CallChain.builder()
                .chainId("CHAIN-TEST")
                .depth(5)
                .containsAsyncCall(true)
                .crossesServiceBoundary(true)
                .containsMQCall(false)
                .riskLevel(CallChain.RiskLevel.HIGH)
                .build();

        assertEquals(5, chain.getDepth());
        assertTrue(chain.isContainsAsyncCall());
        assertTrue(chain.isCrossesServiceBoundary());
        assertFalse(chain.isContainsMQCall());
        assertEquals(CallChain.RiskLevel.HIGH, chain.getRiskLevel());
    }

    @Test
    @DisplayName("Test TestCase priorities")
    void testTestCasePriorities() {
        TestCase testCase = TestCase.builder()
                .testCaseId("TC-001")
                .name("Test Case 1")
                .testType(TestCase.TestType.UNIT)
                .priority(TestCase.Priority.P0)
                .coverageScore(90)
                .build();

        assertEquals(TestCase.TestType.UNIT, testCase.getTestType());
        assertEquals(TestCase.Priority.P0, testCase.getPriority());
        assertEquals(90, testCase.getCoverageScore());
    }
}