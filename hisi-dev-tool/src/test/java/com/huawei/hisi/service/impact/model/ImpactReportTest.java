package com.huawei.hisi.service.impact.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImpactReport 单元测试
 *
 * 测试影响分析报告模型
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("ImpactReport 单元测试")
class ImpactReportTest {

    @Test
    @DisplayName("测试构建 ImpactReport - 基本属性")
    void testBuildBasicImpactReport() {
        ImpactReport report = ImpactReport.builder()
                .reportId("report-001")
                .analysisTime(LocalDateTime.now())
                .maxDepth(5)
                .totalImpactedCount(10)
                .durationMs(1500)
                .build();

        assertEquals("report-001", report.getReportId());
        assertNotNull(report.getAnalysisTime());
        assertEquals(5, report.getMaxDepth());
        assertEquals(10, report.getTotalImpactedCount());
        assertEquals(1500, report.getDurationMs());
    }

    @Test
    @DisplayName("测试默认分析状态")
    void testDefaultStatus() {
        ImpactReport report = ImpactReport.builder().build();
        assertEquals(ImpactReport.AnalysisStatus.COMPLETED, report.getStatus());
    }

    @Test
    @DisplayName("测试构建 ImpactReport - 包含调用者列表")
    void testBuildWithDirectCallers() {
        Caller caller = Caller.builder()
                .className("UserService")
                .methodName("getUser")
                .build();

        ImpactReport report = ImpactReport.builder()
                .directCallers(List.of(caller))
                .build();

        assertNotNull(report.getDirectCallers());
        assertEquals(1, report.getDirectCallers().size());
        assertEquals("UserService", report.getDirectCallers().get(0).getClassName());
    }

    @Test
    @DisplayName("测试构建 ImpactReport - 包含调用链")
    void testBuildWithCallChains() {
        CallChain.EntryPoint entryPoint = CallChain.EntryPoint.builder()
                .className("Controller")
                .methodName("handle")
                .build();
        CallChain chain = CallChain.builder()
                .entryPoint(entryPoint)
                .depth(3)
                .build();

        ImpactReport report = ImpactReport.builder()
                .callChains(List.of(chain))
                .build();

        assertNotNull(report.getCallChains());
        assertEquals(1, report.getCallChains().size());
    }

    @Test
    @DisplayName("测试构建 ImpactReport - 包含受影响项")
    void testBuildWithAffectedItems() {
        ImpactReport report = ImpactReport.builder()
                .affectedClasses(List.of("UserService", "OrderService"))
                .affectedMethods(List.of("getUser", "createOrder"))
                .affectedUris(List.of("/api/users", "/api/orders"))
                .affectedMQEndpoints(List.of("userQueue", "orderQueue"))
                .build();

        assertEquals(2, report.getAffectedClasses().size());
        assertEquals(2, report.getAffectedMethods().size());
        assertEquals(2, report.getAffectedUris().size());
        assertEquals(2, report.getAffectedMQEndpoints().size());
    }

    @Test
    @DisplayName("测试构建 ImpactReport - 包含风险评估")
    void testBuildWithRiskAssessment() {
        RiskAssessment assessment = RiskAssessment.builder()
                .overallRiskLevel(RiskAssessment.RiskLevel.HIGH)
                .overallRiskScore(65)
                .build();

        ImpactReport report = ImpactReport.builder()
                .riskAssessment(assessment)
                .build();

        assertNotNull(report.getRiskAssessment());
        assertEquals(RiskAssessment.RiskLevel.HIGH, report.getRiskAssessment().getOverallRiskLevel());
    }

    @Test
    @DisplayName("测试构建 ImpactReport - 包含测试用例")
    void testBuildWithTestCases() {
        TestCase testCase = TestCase.builder()
                .testCaseId("tc-001")
                .name("testGetUser")
                .build();

        ImpactReport report = ImpactReport.builder()
                .testCases(List.of(testCase))
                .build();

        assertNotNull(report.getTestCases());
        assertEquals(1, report.getTestCases().size());
    }

    @Test
    @DisplayName("测试分析状态枚举")
    void testAnalysisStatusEnum() {
        assertEquals(5, ImpactReport.AnalysisStatus.values().length);
        assertEquals(ImpactReport.AnalysisStatus.PENDING, ImpactReport.AnalysisStatus.valueOf("PENDING"));
        assertEquals(ImpactReport.AnalysisStatus.IN_PROGRESS, ImpactReport.AnalysisStatus.valueOf("IN_PROGRESS"));
        assertEquals(ImpactReport.AnalysisStatus.COMPLETED, ImpactReport.AnalysisStatus.valueOf("COMPLETED"));
        assertEquals(ImpactReport.AnalysisStatus.FAILED, ImpactReport.AnalysisStatus.valueOf("FAILED"));
        assertEquals(ImpactReport.AnalysisStatus.PARTIAL, ImpactReport.AnalysisStatus.valueOf("PARTIAL"));
    }

    @Test
    @DisplayName("测试设置不同分析状态")
    void testDifferentStatus() {
        ImpactReport report = ImpactReport.builder()
                .status(ImpactReport.AnalysisStatus.FAILED)
                .errorMessage("Analysis failed due to timeout")
                .build();

        assertEquals(ImpactReport.AnalysisStatus.FAILED, report.getStatus());
        assertEquals("Analysis failed due to timeout", report.getErrorMessage());
    }

    @Test
    @DisplayName("测试空构造和 setter")
    void testEmptyConstructorAndSetter() {
        ImpactReport report = new ImpactReport();
        report.setReportId("test-id");
        report.setMaxDepth(10);
        report.setTotalImpactedCount(5);
        report.setStatus(ImpactReport.AnalysisStatus.IN_PROGRESS);

        assertEquals("test-id", report.getReportId());
        assertEquals(10, report.getMaxDepth());
        assertEquals(5, report.getTotalImpactedCount());
        assertEquals(ImpactReport.AnalysisStatus.IN_PROGRESS, report.getStatus());
    }

    @Test
    @DisplayName("测试包含变更请求")
    void testWithChangeRequest() {
        ChangeRequest request = ChangeRequest.builder()
                .className("com.example.UserService")
                .methodName("getUser")
                .build();

        ImpactReport report = ImpactReport.builder()
                .changeRequest(request)
                .build();

        assertNotNull(report.getChangeRequest());
        assertEquals("com.example.UserService", report.getChangeRequest().getClassName());
    }

    @Test
    @DisplayName("测试元数据")
    void testMetadata() {
        ImpactReport report = ImpactReport.builder()
                .metadata(java.util.Map.of(
                        "version", "1.0",
                        "analyzer", "StaticAnalysisEngine"
                ))
                .build();

        assertNotNull(report.getMetadata());
        assertEquals("1.0", report.getMetadata().get("version"));
    }

    @Test
    @DisplayName("测试全参数构造")
    void testAllArgsConstructor() {
        ImpactReport report = new ImpactReport(
                "report-id",
                null,
                LocalDateTime.now(),
                List.of(),
                List.of(),
                List.of("ClassA"),
                List.of("methodA"),
                List.of("/api/test"),
                List.of(),
                3,
                5,
                null,
                List.of(),
                null,
                ImpactReport.AnalysisStatus.COMPLETED,
                null,
                1000L
        );

        assertEquals("report-id", report.getReportId());
        assertEquals(3, report.getMaxDepth());
        assertEquals(1000L, report.getDurationMs());
    }
}