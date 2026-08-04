package com.huawei.hisi.service.impact.impl;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.service.impact.CoverageService;
import com.huawei.hisi.service.impact.ImpactPredictionService;
import com.huawei.hisi.service.impact.StaticAnalysisEngine;
import com.huawei.hisi.service.impact.model.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementation of ImpactPredictionService for analyzing code change impacts.
 *
 * v1.0 Features:
 * - Static call chain tracing
 * - Direct caller identification
 * - Basic risk assessment
 * - Test case recommendations
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactPredictionServiceImpl implements ImpactPredictionService {

    private final GlobalAnalysisCache globalCache;
    private final StaticAnalysisEngine staticAnalysisEngine;
    private final CoverageService coverageService;

    /**
     * Default maximum tracing depth
     */
    private static final int DEFAULT_MAX_DEPTH = 10;

    /**
     * Risk scoring weights
     */
    private static final double IMPACT_SCOPE_WEIGHT = 0.3;
    private static final double BUSINESS_CRITICALITY_WEIGHT = 0.25;
    private static final double CODE_COMPLEXITY_WEIGHT = 0.2;
    private static final double TEST_COVERAGE_WEIGHT = 0.25;

    @Override
    public ImpactReport analyzeChange(ChangeRequest request) {
        log.info("Starting impact analysis for: {}.{}", request.getClassName(), request.getMethodName());

        long startTime = System.currentTimeMillis();
        String reportId = generateReportId();

        try {
            // 1. Build target method signature
            String targetMethodSignature = buildMethodSignature(request.getClassName(), request.getMethodName());

            // 2. Find direct callers
            List<Caller> directCallers = staticAnalysisEngine.findDirectCallers(targetMethodSignature);
            log.info("Found {} direct callers", directCallers.size());

            // 3. Trace call chains to entry points
            List<CallChain> callChains = staticAnalysisEngine.traceAllCallChains(targetMethodSignature, DEFAULT_MAX_DEPTH);
            log.info("Found {} call chains", callChains.size());

            // 4. Extract affected components
            List<String> affectedClasses = extractAffectedClasses(directCallers, callChains);
            List<String> affectedMethods = extractAffectedMethods(directCallers);
            List<String> affectedUris = extractAffectedUris(callChains);
            List<String> affectedMQEndpoints = extractAffectedMQEndpoints(callChains);

            // 5. Calculate max depth
            int maxDepth = callChains.stream()
                    .mapToInt(CallChain::getDepth)
                    .max()
                    .orElse(0);

            // 6. Perform risk assessment
            RiskAssessment riskAssessment = assessRisk(request);

            // 7. Build impact report
            ImpactReport report = ImpactReport.builder()
                    .reportId(reportId)
                    .changeRequest(request)
                    .analysisTime(LocalDateTime.now())
                    .directCallers(directCallers)
                    .callChains(callChains)
                    .affectedClasses(affectedClasses)
                    .affectedMethods(affectedMethods)
                    .affectedUris(affectedUris)
                    .affectedMQEndpoints(affectedMQEndpoints)
                    .maxDepth(maxDepth)
                    .totalImpactedCount(affectedClasses.size() + affectedUris.size() + affectedMQEndpoints.size())
                    .riskAssessment(riskAssessment)
                    .status(ImpactReport.AnalysisStatus.COMPLETED)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();

            log.info("Impact analysis completed in {} ms", report.getDurationMs());
            return report;

        } catch (Exception e) {
            log.error("Impact analysis failed", e);
            return ImpactReport.builder()
                    .reportId(reportId)
                    .changeRequest(request)
                    .analysisTime(LocalDateTime.now())
                    .status(ImpactReport.AnalysisStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public List<TestCase> generateTestCases(ImpactReport report) {
        log.info("Generating test cases for report: {}", report.getReportId());

        List<TestCase> testCases = new ArrayList<>();

        // 1. Generate unit tests for the changed method
        TestCase unitTest = generateUnitTest(report.getChangeRequest());
        testCases.add(unitTest);

        // 2. Generate integration tests for direct callers
        for (Caller caller : report.getDirectCallers()) {
            TestCase integrationTest = generateIntegrationTest(caller, report.getChangeRequest());
            testCases.add(integrationTest);
        }

        // 3. Generate API tests for affected URIs
        for (String uri : report.getAffectedUris()) {
            TestCase apiTest = generateApiTest(uri, report);
            testCases.add(apiTest);
        }

        // 4. Generate MQ tests for affected MQ endpoints
        for (String mqEndpoint : report.getAffectedMQEndpoints()) {
            TestCase mqTest = generateMQTest(mqEndpoint, report);
            testCases.add(mqTest);
        }

        // 5. Sort by priority
        testCases.sort(Comparator.comparing(tc -> tc.getPriority().ordinal()));

        log.info("Generated {} test cases", testCases.size());
        return testCases;
    }

    @Override
    public RiskAssessment assessRisk(ChangeRequest request) {
        log.info("Assessing risk for: {}.{}", request.getClassName(), request.getMethodName());

        String targetMethodSignature = buildMethodSignature(request.getClassName(), request.getMethodName());

        // 1. Calculate impact scope score
        List<Caller> directCallers = staticAnalysisEngine.findDirectCallers(targetMethodSignature);
        int impactScopeScore = calculateImpactScopeScore(directCallers.size());

        // 2. Calculate business criticality score
        List<StaticAnalysisEngine.EntryPointInfo> entryPoints = staticAnalysisEngine.findEntryPoints(targetMethodSignature);
        int businessCriticalityScore = calculateBusinessCriticalityScore(entryPoints);

        // 3. Calculate code complexity score
        int codeComplexityScore = calculateCodeComplexityScore(request);

        // 4. Calculate test coverage score (using CoverageService)
        int testCoverageScore = coverageService.getCoverageScore(
                request.getClassName(), request.getMethodName());

        // 5. Calculate overall risk score
        int overallRiskScore = (int) (
                impactScopeScore * IMPACT_SCOPE_WEIGHT +
                businessCriticalityScore * BUSINESS_CRITICALITY_WEIGHT +
                codeComplexityScore * CODE_COMPLEXITY_WEIGHT +
                testCoverageScore * TEST_COVERAGE_WEIGHT
        );

        // 6. Determine risk level
        RiskAssessment.RiskLevel overallRiskLevel = RiskAssessment.RiskLevel.fromScore(overallRiskScore);

        // 7. Identify specific risks
        List<RiskAssessment.RiskItem> risks = identifyRisks(request, directCallers, entryPoints);

        // 8. Generate recommendations
        List<String> recommendations = generateRecommendations(overallRiskLevel, directCallers, entryPoints);

        // 9. Build score breakdown
        Map<String, Integer> scoreBreakdown = new HashMap<>();
        scoreBreakdown.put("impactScope", impactScopeScore);
        scoreBreakdown.put("businessCriticality", businessCriticalityScore);
        scoreBreakdown.put("codeComplexity", codeComplexityScore);
        scoreBreakdown.put("testCoverage", testCoverageScore);

        return RiskAssessment.builder()
                .overallRiskLevel(overallRiskLevel)
                .overallRiskScore(overallRiskScore)
                .impactScopeScore(impactScopeScore)
                .businessCriticalityScore(businessCriticalityScore)
                .codeComplexityScore(codeComplexityScore)
                .testCoverageScore(testCoverageScore)
                .risks(risks)
                .recommendations(recommendations)
                .scoreBreakdown(scoreBreakdown)
                .confidenceLevel(75)
                .assessmentTime(LocalDateTime.now())
                .build();
    }

    @Override
    public ImpactPreview previewImpact(String className, String methodName) {
        log.info("Previewing impact for: {}.{}", className, methodName);

        String targetMethodSignature = buildMethodSignature(className, methodName);

        // Quick analysis without full call chain tracing
        List<Caller> directCallers = staticAnalysisEngine.findDirectCallers(targetMethodSignature);
        List<StaticAnalysisEngine.EntryPointInfo> entryPoints = staticAnalysisEngine.findEntryPoints(targetMethodSignature);

        int directCallerCount = directCallers.size();
        int affectedUriCount = (int) entryPoints.stream()
                .filter(ep -> ep.getType() == CallChain.EntryPointType.HTTP_ENDPOINT)
                .count();

        String estimatedRiskLevel = directCallerCount > 10 || affectedUriCount > 5 ? "HIGH" :
                                    directCallerCount > 5 || affectedUriCount > 2 ? "MEDIUM" : "LOW";

        String recommendation = generateQuickRecommendation(directCallerCount, affectedUriCount);

        return ImpactPreview.builder()
                .directCallerCount(directCallerCount)
                .affectedUriCount(affectedUriCount)
                .estimatedRiskLevel(estimatedRiskLevel)
                .recommendation(recommendation)
                .build();
    }

    // ============================================================
    // Private Helper Methods
    // ============================================================

    private String generateReportId() {
        return "IMP-" + LocalDateTime.now().toString()
                .replace("-", "")
                .replace(":", "")
                .replace(".", "")
                .substring(0, 17) + "-" +
                UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    private String buildMethodSignature(String className, String methodName) {
        return className + "." + methodName;
    }

    private List<String> extractAffectedClasses(List<Caller> directCallers, List<CallChain> callChains) {
        Set<String> classes = new HashSet<>();

        // From direct callers
        for (Caller caller : directCallers) {
            classes.add(caller.getClassName());
        }

        // From call chains
        for (CallChain chain : callChains) {
            for (CallChain.ChainNode node : chain.getNodes()) {
                classes.add(node.getClassName());
            }
            if (chain.getEntryPoint() != null) {
                classes.add(chain.getEntryPoint().getClassName());
            }
        }

        return new ArrayList<>(classes);
    }

    private List<String> extractAffectedMethods(List<Caller> directCallers) {
        return directCallers.stream()
                .map(Caller::getMethodSignature)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> extractAffectedUris(List<CallChain> callChains) {
        return callChains.stream()
                .filter(chain -> chain.getEntryPoint() != null)
                .filter(chain -> chain.getEntryPoint().getType() == CallChain.EntryPointType.HTTP_ENDPOINT)
                .map(chain -> chain.getEntryPoint().getUri())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> extractAffectedMQEndpoints(List<CallChain> callChains) {
        return callChains.stream()
                .filter(chain -> chain.getEntryPoint() != null)
                .filter(chain -> chain.getEntryPoint().getType() == CallChain.EntryPointType.MQ_CONSUMER)
                .map(chain -> chain.getEntryPoint().getMqEndpoint())
                .distinct()
                .collect(Collectors.toList());
    }

    private int calculateImpactScopeScore(int callerCount) {
        // Scale: 0-100 based on number of callers
        if (callerCount == 0) return 10;
        if (callerCount <= 3) return 25;
        if (callerCount <= 5) return 40;
        if (callerCount <= 10) return 60;
        if (callerCount <= 20) return 80;
        return 100;
    }

    private int calculateBusinessCriticalityScore(List<StaticAnalysisEngine.EntryPointInfo> entryPoints) {
        // Scale based on entry point types
        if (entryPoints.isEmpty()) return 20;

        int score = 0;
        for (StaticAnalysisEngine.EntryPointInfo ep : entryPoints) {
            switch (ep.getType()) {
                case HTTP_ENDPOINT:
                    score += 30; // HTTP endpoints are more critical
                    break;
                case MQ_CONSUMER:
                    score += 25;
                    break;
                case SCHEDULED_TASK:
                    score += 20;
                    break;
                default:
                    score += 10;
            }
        }
        return Math.min(score, 100);
    }

    private int calculateCodeComplexityScore(ChangeRequest request) {
        // Scale based on change type and size
        int baseScore = 30;

        switch (request.getChangeType()) {
            case DELETE:
                baseScore += 40; // Deleting is risky
                break;
            case REFACTOR:
                baseScore += 30; // Refactoring is moderately risky
                break;
            case MODIFY:
                baseScore += 20; // Modification has some risk
                break;
            case ADD:
                baseScore += 10; // Adding is least risky
                break;
        }

        // Consider change size
        if (request.getOriginalCode() != null && request.getNewCode() != null) {
            int linesChanged = Math.abs(request.getNewCode().length() - request.getOriginalCode().length()) / 50;
            baseScore += Math.min(linesChanged * 5, 20);
        }

        return Math.min(baseScore, 100);
    }

    private List<RiskAssessment.RiskItem> identifyRisks(ChangeRequest request,
                                                        List<Caller> directCallers,
                                                        List<StaticAnalysisEngine.EntryPointInfo> entryPoints) {
        List<RiskAssessment.RiskItem> risks = new ArrayList<>();

        // Risk: Multiple callers affected
        if (directCallers.size() > 5) {
            risks.add(RiskAssessment.RiskItem.builder()
                    .riskId("RISK-001")
                    .category(RiskAssessment.RiskCategory.FUNCTIONAL)
                    .description("Multiple callers (" + directCallers.size() + ") affected by this change")
                    .severity(RiskAssessment.RiskSeverity.MODERATE)
                    .affectedComponents(directCallers.stream().map(Caller::getClassName).collect(Collectors.toList()))
                    .mitigations(List.of("Add comprehensive unit tests for all callers", "Consider impact on each caller"))
                    .build());
        }

        // Risk: API endpoints affected
        if (entryPoints.stream().anyMatch(ep -> ep.getType() == CallChain.EntryPointType.HTTP_ENDPOINT)) {
            risks.add(RiskAssessment.RiskItem.builder()
                    .riskId("RISK-002")
                    .category(RiskAssessment.RiskCategory.COMPATIBILITY)
                    .description("API endpoints affected - potential breaking change")
                    .severity(RiskAssessment.RiskSeverity.MAJOR)
                    .affectedComponents(entryPoints.stream()
                            .filter(ep -> ep.getType() == CallChain.EntryPointType.HTTP_ENDPOINT)
                            .map(StaticAnalysisEngine.EntryPointInfo::getUri)
                            .collect(Collectors.toList()))
                    .mitigations(List.of("Verify API contract unchanged", "Add integration tests for affected endpoints"))
                    .build());
        }

        // Risk: MQ endpoints affected
        if (entryPoints.stream().anyMatch(ep -> ep.getType() == CallChain.EntryPointType.MQ_CONSUMER)) {
            risks.add(RiskAssessment.RiskItem.builder()
                    .riskId("RISK-003")
                    .category(RiskAssessment.RiskCategory.RELIABILITY)
                    .description("MQ consumers affected - message processing may fail")
                    .severity(RiskAssessment.RiskSeverity.MODERATE)
                    .affectedComponents(entryPoints.stream()
                            .filter(ep -> ep.getType() == CallChain.EntryPointType.MQ_CONSUMER)
                            .map(StaticAnalysisEngine.EntryPointInfo::getMqEndpoint)
                            .collect(Collectors.toList()))
                    .mitigations(List.of("Test message processing scenarios", "Verify error handling"))
                    .build());
        }

        // Risk: Delete operation
        if (request.getChangeType() == ChangeRequest.ChangeType.DELETE) {
            risks.add(RiskAssessment.RiskItem.builder()
                    .riskId("RISK-004")
                    .category(RiskAssessment.RiskCategory.COMPATIBILITY)
                    .description("Method/class deletion - high risk of breaking existing code")
                    .severity(RiskAssessment.RiskSeverity.SEVERE)
                    .affectedComponents(List.of(request.getClassName()))
                    .mitigations(List.of("Verify no references to deleted code", "Check all callers still work"))
                    .build());
        }

        return risks;
    }

    private List<String> generateRecommendations(RiskAssessment.RiskLevel riskLevel,
                                                 List<Caller> directCallers,
                                                 List<StaticAnalysisEngine.EntryPointInfo> entryPoints) {
        List<String> recommendations = new ArrayList<>();

        switch (riskLevel) {
            case CRITICAL:
                recommendations.add("CRITICAL: This change requires thorough review and approval");
                recommendations.add("Run full regression testing before deployment");
                recommendations.add("Consider staged rollout with monitoring");
                break;
            case HIGH:
                recommendations.add("HIGH: Comprehensive testing required");
                recommendations.add("Add unit tests for all " + directCallers.size() + " callers");
                recommendations.add("Integration tests recommended for affected endpoints");
                break;
            case MEDIUM:
                recommendations.add("MEDIUM: Standard testing procedures recommended");
                recommendations.add("Add unit tests for primary callers");
                break;
            case LOW:
                recommendations.add("LOW: Minimal testing required");
                recommendations.add("Basic unit tests sufficient");
                break;
        }

        if (!entryPoints.isEmpty()) {
            recommendations.add("Test all " + entryPoints.size() + " affected entry points");
        }

        return recommendations;
    }

    private String generateQuickRecommendation(int callerCount, int uriCount) {
        if (callerCount > 10 || uriCount > 5) {
            return "High impact detected. Recommend full analysis before proceeding.";
        } else if (callerCount > 5 || uriCount > 2) {
            return "Moderate impact. Recommend running detailed analysis.";
        } else {
            return "Low impact. Proceed with standard testing.";
        }
    }

    // ============================================================
    // Test Case Generation Methods
    // ============================================================

    private TestCase generateUnitTest(ChangeRequest request) {
        return TestCase.builder()
                .testCaseId(generateTestCaseId())
                .name("Unit test for " + request.getMethodName())
                .description("Verify " + request.getMethodName() + " behavior after change")
                .testType(TestCase.TestType.UNIT)
                .priority(TestCase.Priority.P0)
                .targetMethod(request.getMethodName())
                .targetClass(request.getClassName())
                .scenario("Test method functionality with various inputs")
                .expectedOutput("Method executes correctly and returns expected results")
                .preconditions(List.of("Mock dependencies if needed", "Set up test fixtures"))
                .steps(List.of(
                        TestCase.TestStep.builder().order(1).action("Setup").description("Initialize test context").build(),
                        TestCase.TestStep.builder().order(2).action("Execute").description("Call method with test inputs").build(),
                        TestCase.TestStep.builder().order(3).action("Verify").description("Assert expected results").build()
                ))
                .assertions(List.of("Return value matches expected", "No exceptions thrown", "Side effects verified"))
                .coverageScore(85)
                .existingTest(false)
                .build();
    }

    private TestCase generateIntegrationTest(Caller caller, ChangeRequest request) {
        return TestCase.builder()
                .testCaseId(generateTestCaseId())
                .name("Integration test for " + caller.getClassName() + "." + caller.getMethodName())
                .description("Verify caller method works correctly with changed method")
                .testType(TestCase.TestType.INTEGRATION)
                .priority(TestCase.Priority.P1)
                .targetMethod(caller.getMethodName())
                .targetClass(caller.getClassName())
                .scenario("Test caller method integration with changed " + request.getMethodName())
                .expectedOutput("Caller method executes correctly and integration works")
                .preconditions(List.of("Set up integration context", "Configure required services"))
                .steps(List.of(
                        TestCase.TestStep.builder().order(1).action("Setup").description("Initialize integration test context").build(),
                        TestCase.TestStep.builder().order(2).action("Execute").description("Call caller method").build(),
                        TestCase.TestStep.builder().order(3).action("Verify").description("Assert integration behavior").build()
                ))
                .assertions(List.of("Integration flow completes", "Changed method called correctly"))
                .mocks(List.of(
                        TestCase.MockRequirement.builder()
                                .targetClass(request.getClassName())
                                .targetMethod(request.getMethodName())
                                .mockBehavior("Return expected values")
                                .required(false)
                                .build()
                ))
                .coverageScore(70)
                .existingTest(false)
                .build();
    }

    private TestCase generateApiTest(String uri, ImpactReport report) {
        return TestCase.builder()
                .testCaseId(generateTestCaseId())
                .name("API test for " + uri)
                .description("Verify API endpoint " + uri + " works correctly after change")
                .testType(TestCase.TestType.API)
                .priority(TestCase.Priority.P0)
                .scenario("Test API endpoint behavior")
                .expectedOutput("API returns expected response")
                .preconditions(List.of("API server running", "Test data prepared"))
                .steps(List.of(
                        TestCase.TestStep.builder().order(1).action("Prepare").description("Set up API test context").build(),
                        TestCase.TestStep.builder().order(2).action("Request").description("Send HTTP request to " + uri).build(),
                        TestCase.TestStep.builder().order(3).action("Verify").description("Assert response status and content").build()
                ))
                .assertions(List.of("HTTP status code correct", "Response body matches expected", "Response time acceptable"))
                .coverageScore(80)
                .existingTest(false)
                .build();
    }

    private TestCase generateMQTest(String mqEndpoint, ImpactReport report) {
        return TestCase.builder()
                .testCaseId(generateTestCaseId())
                .name("MQ test for " + mqEndpoint)
                .description("Verify MQ endpoint " + mqEndpoint + " processes messages correctly")
                .testType(TestCase.TestType.INTEGRATION)
                .priority(TestCase.Priority.P1)
                .scenario("Test message processing behavior")
                .expectedOutput("Message processed correctly")
                .preconditions(List.of("MQ broker running", "Test messages prepared"))
                .steps(List.of(
                        TestCase.TestStep.builder().order(1).action("Prepare").description("Set up MQ test context").build(),
                        TestCase.TestStep.builder().order(2).action("Send").description("Send test message to " + mqEndpoint).build(),
                        TestCase.TestStep.builder().order(3).action("Verify").description("Assert message processed correctly").build()
                ))
                .assertions(List.of("Message consumed", "Processing completes without error", "Result matches expected"))
                .coverageScore(60)
                .existingTest(false)
                .build();
    }

    private String generateTestCaseId() {
        return "TC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}