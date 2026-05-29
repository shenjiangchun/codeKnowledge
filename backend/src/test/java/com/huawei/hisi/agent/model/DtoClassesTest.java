package com.huawei.hisi.agent.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TD-006: DTO类单元测试
 *
 * 测试AgentAnalysisInfo和AgentVerificationInfo DTO类
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("TD-006: DTO类测试")
class DtoClassesTest {

    // ==================== AgentAnalysisInfo Tests ====================

    @DisplayName("AgentAnalysisInfo测试")
    @Nested
    class AgentAnalysisInfoTests {

        private AgentAnalysisInfo analysisInfo;

        @BeforeEach
        void setUp() {
            analysisInfo = AgentAnalysisInfo.builder().build();
        }

        @Test
        @DisplayName("测试默认值初始化")
        void testDefaultValues() {
            assertFalse(analysisInfo.isSuccess(), "默认success应为false");
            assertEquals(30, analysisInfo.getRiskScore(), "默认风险评分应为30");
            assertEquals(AgentAnalysisInfo.Severity.MEDIUM, analysisInfo.getSeverity(), "默认严重程度应为MEDIUM");
            assertEquals("待分析", analysisInfo.getImpactScope(), "默认影响范围应为'待分析'");
            assertNotNull(analysisInfo.getAffectedModules(), "affectedModules不应为空");
            assertTrue(analysisInfo.getAffectedModules().isEmpty(), "affectedModules应为空列表");
        }

        @Test
        @DisplayName("测试Builder创建完整对象")
        void testBuilderCreateFullObject() {
            AgentAnalysisInfo info = AgentAnalysisInfo.builder()
                    .success(true)
                    .exceptionType("java.lang.NullPointerException")
                    .riskScore(75)
                    .severity(AgentAnalysisInfo.Severity.HIGH)
                    .impactScope("核心业务模块受影响")
                    .affectedModules(List.of("Service", "Repository"))
                    .conclusion("高风险异常，需要立即处理")
                    .riskFactors(List.of("空引用", "未校验参数"))
                    .recommendations(List.of("添加null检查", "使用Optional"))
                    .build();

            assertTrue(info.isSuccess(), "success应为true");
            assertEquals("java.lang.NullPointerException", info.getExceptionType(), "异常类型应匹配");
            assertEquals(75, info.getRiskScore(), "风险评分应为75");
            assertEquals(AgentAnalysisInfo.Severity.HIGH, info.getSeverity(), "严重程度应为HIGH");
            assertEquals("核心业务模块受影响", info.getImpactScope(), "影响范围应匹配");
            assertEquals(2, info.getAffectedModules().size(), "应有2个受影响模块");
            assertEquals("高风险异常，需要立即处理", info.getConclusion(), "结论应匹配");
        }

        @Test
        @DisplayName("测试addAffectedModule方法")
        void testAddAffectedModule() {
            analysisInfo.addAffectedModule("com.example.Service");
            analysisInfo.addAffectedModule("com.example.Repository");

            assertEquals(2, analysisInfo.getAffectedModules().size(), "应有2个受影响模块");
            assertTrue(analysisInfo.getAffectedModules().contains("com.example.Service"), "应包含Service");
            assertTrue(analysisInfo.getAffectedModules().contains("com.example.Repository"), "应包含Repository");
        }

        @Test
        @DisplayName("测试addAffectedModule方法 - 初始化null列表")
        void testAddAffectedModuleWithNullList() {
            AgentAnalysisInfo info = AgentAnalysisInfo.builder()
                    .affectedModules(null)
                    .build();

            info.addAffectedModule("com.example.Service");

            assertNotNull(info.getAffectedModules(), "列表应被初始化");
            assertEquals(1, info.getAffectedModules().size(), "应有1个模块");
        }

        @Test
        @DisplayName("测试addRiskFactor方法")
        void testAddRiskFactor() {
            analysisInfo.addRiskFactor("空指针风险");
            analysisInfo.addRiskFactor("并发风险");

            assertEquals(2, analysisInfo.getRiskFactors().size(), "应有2个风险因素");
        }

        @Test
        @DisplayName("测试addRecommendation方法")
        void testAddRecommendation() {
            analysisInfo.addRecommendation("添加参数校验");
            analysisInfo.addRecommendation("使用防御性编程");

            assertEquals(2, analysisInfo.getRecommendations().size(), "应有2个建议");
        }

        @Test
        @DisplayName("测试Severity枚举")
        void testSeverityEnum() {
            assertEquals(4, AgentAnalysisInfo.Severity.values().length, "应有4个严重程度级别");
            assertEquals(AgentAnalysisInfo.Severity.LOW, AgentAnalysisInfo.Severity.valueOf("LOW"));
            assertEquals(AgentAnalysisInfo.Severity.MEDIUM, AgentAnalysisInfo.Severity.valueOf("MEDIUM"));
            assertEquals(AgentAnalysisInfo.Severity.HIGH, AgentAnalysisInfo.Severity.valueOf("HIGH"));
            assertEquals(AgentAnalysisInfo.Severity.CRITICAL, AgentAnalysisInfo.Severity.valueOf("CRITICAL"));
        }

        @Test
        @DisplayName("测试全参数构造器")
        void testAllArgsConstructor() {
            AgentAnalysisInfo info = new AgentAnalysisInfo(
                true,
                "java.lang.IOException",
                80,
                AgentAnalysisInfo.Severity.HIGH,
                "IO操作失败",
                new ArrayList<>(),
                "IO异常分析",
                new ArrayList<>(),
                new ArrayList<>()
            );

            assertTrue(info.isSuccess(), "success应为true");
            assertEquals("java.lang.IOException", info.getExceptionType(), "异常类型应匹配");
            assertEquals(80, info.getRiskScore(), "风险评分应为80");
        }

        @Test
        @DisplayName("测试Setter和Getter")
        void testSettersAndGetters() {
            analysisInfo.setSuccess(true);
            analysisInfo.setExceptionType("java.lang.SQLException");
            analysisInfo.setRiskScore(90);
            analysisInfo.setSeverity(AgentAnalysisInfo.Severity.CRITICAL);
            analysisInfo.setImpactScope("数据库层");
            analysisInfo.setConclusion("数据库连接失败");

            assertTrue(analysisInfo.isSuccess(), "success应为true");
            assertEquals("java.lang.SQLException", analysisInfo.getExceptionType(), "异常类型应匹配");
            assertEquals(90, analysisInfo.getRiskScore(), "风险评分应为90");
            assertEquals(AgentAnalysisInfo.Severity.CRITICAL, analysisInfo.getSeverity(), "严重程度应为CRITICAL");
        }
    }

    // ==================== AgentVerificationInfo Tests ====================

    @DisplayName("AgentVerificationInfo测试")
    @Nested
    class AgentVerificationInfoTests {

        private AgentVerificationInfo verificationInfo;

        @BeforeEach
        void setUp() {
            verificationInfo = AgentVerificationInfo.builder().build();
        }

        @Test
        @DisplayName("测试默认值初始化")
        void testDefaultValues() {
            assertFalse(verificationInfo.isSuccess(), "默认success应为false");
            assertEquals(0, verificationInfo.getValidationScore(), "默认验证分数应为0");
            assertEquals(AgentVerificationInfo.ConfidenceLevel.MEDIUM, verificationInfo.getConfidenceLevel(), "默认置信度级别应为MEDIUM");
            assertFalse(verificationInfo.isValid(), "默认isValid应为false");
            assertFalse(verificationInfo.isStackTraceValid(), "默认stackTraceValid应为false");
            assertFalse(verificationInfo.isAnalysisValid(), "默认analysisValid应为false");
            assertFalse(verificationInfo.isConclusionConsistent(), "默认conclusionConsistent应为false");
            assertFalse(verificationInfo.isCrossValidationPassed(), "默认crossValidationPassed应为false");
            assertEquals(0.0, verificationInfo.getAgreementRate(), "默认agreementRate应为0.0");
            assertNotNull(verificationInfo.getValidatedCode(), "validatedCode不应为空");
            assertNotNull(verificationInfo.getWarnings(), "warnings不应为空");
        }

        @Test
        @DisplayName("测试Builder创建完整对象")
        void testBuilderCreateFullObject() {
            AgentVerificationInfo info = AgentVerificationInfo.builder()
                    .success(true)
                    .validationScore(85)
                    .confidenceLevel(AgentVerificationInfo.ConfidenceLevel.HIGH)
                    .isValid(true)
                    .stackTraceValid(true)
                    .analysisValid(true)
                    .conclusionConsistent(true)
                    .crossValidationPassed(true)
                    .agreementRate(0.9)
                    .validatedCode(List.of("Service", "Repository"))
                    .warnings(new ArrayList<>())
                    .issues(new ArrayList<>())
                    .strengths(List.of("结论一致", "高置信度"))
                    .recommendations(List.of("继续监控"))
                    .conclusion("验证通过，结果可信")
                    .build();

            assertTrue(info.isSuccess(), "success应为true");
            assertEquals(85, info.getValidationScore(), "验证分数应为85");
            assertEquals(AgentVerificationInfo.ConfidenceLevel.HIGH, info.getConfidenceLevel(), "置信度级别应为HIGH");
            assertTrue(info.isValid(), "isValid应为true");
            assertTrue(info.isStackTraceValid(), "stackTraceValid应为true");
            assertTrue(info.isAnalysisValid(), "analysisValid应为true");
            assertEquals(0.9, info.getAgreementRate(), "agreementRate应为0.9");
        }

        @Test
        @DisplayName("测试addValidatedCode方法")
        void testAddValidatedCode() {
            verificationInfo.addValidatedCode("com.example.Service.method1");
            verificationInfo.addValidatedCode("com.example.Repository.method2");

            assertEquals(2, verificationInfo.getValidatedCode().size(), "应有2个已验证代码");
        }

        @Test
        @DisplayName("测试addValidatedCode方法 - 初始化null列表")
        void testAddValidatedCodeWithNullList() {
            AgentVerificationInfo info = AgentVerificationInfo.builder()
                    .validatedCode(null)
                    .build();

            info.addValidatedCode("com.example.Service");

            assertNotNull(info.getValidatedCode(), "列表应被初始化");
            assertEquals(1, info.getValidatedCode().size(), "应有1个代码项");
        }

        @Test
        @DisplayName("测试addWarning方法")
        void testAddWarning() {
            verificationInfo.addWarning("StackTrace结果置信度较低");
            verificationInfo.addWarning("部分结论不一致");

            assertEquals(2, verificationInfo.getWarnings().size(), "应有2个警告");
        }

        @Test
        @DisplayName("测试addIssue方法")
        void testAddIssue() {
            verificationInfo.addIssue("缺少上下文信息");
            verificationInfo.addIssue("异常类型推断不确定");

            assertEquals(2, verificationInfo.getIssues().size(), "应有2个问题");
        }

        @Test
        @DisplayName("测试addStrength方法")
        void testAddStrength() {
            verificationInfo.addStrength("多个Agent结论一致");
            verificationInfo.addStrength("高置信度结果");

            assertEquals(2, verificationInfo.getStrengths().size(), "应有2个强项");
        }

        @Test
        @DisplayName("测试addRecommendation方法")
        void testAddRecommendation() {
            verificationInfo.addRecommendation("增加日志记录");
            verificationInfo.addRecommendation("添加更多测试用例");

            assertEquals(2, verificationInfo.getRecommendations().size(), "应有2个建议");
        }

        @Test
        @DisplayName("测试ConfidenceLevel枚举")
        void testConfidenceLevelEnum() {
            assertEquals(5, AgentVerificationInfo.ConfidenceLevel.values().length, "应有5个置信度级别");
            assertEquals(AgentVerificationInfo.ConfidenceLevel.VERY_LOW, AgentVerificationInfo.ConfidenceLevel.valueOf("VERY_LOW"));
            assertEquals(AgentVerificationInfo.ConfidenceLevel.LOW, AgentVerificationInfo.ConfidenceLevel.valueOf("LOW"));
            assertEquals(AgentVerificationInfo.ConfidenceLevel.MEDIUM, AgentVerificationInfo.ConfidenceLevel.valueOf("MEDIUM"));
            assertEquals(AgentVerificationInfo.ConfidenceLevel.HIGH, AgentVerificationInfo.ConfidenceLevel.valueOf("HIGH"));
            assertEquals(AgentVerificationInfo.ConfidenceLevel.VERY_HIGH, AgentVerificationInfo.ConfidenceLevel.valueOf("VERY_HIGH"));
        }

        @Test
        @DisplayName("测试全参数构造器")
        void testAllArgsConstructor() {
            AgentVerificationInfo info = new AgentVerificationInfo(
                true,
                90,
                AgentVerificationInfo.ConfidenceLevel.VERY_HIGH,
                true,
                true,
                true,
                true,
                true,
                1.0,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                "验证完全通过"
            );

            assertTrue(info.isSuccess(), "success应为true");
            assertEquals(90, info.getValidationScore(), "验证分数应为90");
            assertEquals(AgentVerificationInfo.ConfidenceLevel.VERY_HIGH, info.getConfidenceLevel(), "置信度级别应为VERY_HIGH");
        }

        @Test
        @DisplayName("测试Setter和Getter")
        void testSettersAndGetters() {
            verificationInfo.setSuccess(true);
            verificationInfo.setValidationScore(95);
            verificationInfo.setConfidenceLevel(AgentVerificationInfo.ConfidenceLevel.VERY_HIGH);
            verificationInfo.setValid(true);
            verificationInfo.setAgreementRate(0.95);
            verificationInfo.setConclusion("所有验证通过");

            assertTrue(verificationInfo.isSuccess(), "success应为true");
            assertEquals(95, verificationInfo.getValidationScore(), "验证分数应为95");
            assertEquals(AgentVerificationInfo.ConfidenceLevel.VERY_HIGH, verificationInfo.getConfidenceLevel(), "置信度级别应为VERY_HIGH");
            assertEquals(0.95, verificationInfo.getAgreementRate(), "agreementRate应为0.95");
        }

        @Test
        @DisplayName("测试验证分数范围")
        void testValidationScoreRange() {
            // 边界值测试
            verificationInfo.setValidationScore(0);
            assertEquals(0, verificationInfo.getValidationScore(), "最小分数应为0");

            verificationInfo.setValidationScore(100);
            assertEquals(100, verificationInfo.getValidationScore(), "最大分数应为100");

            verificationInfo.setValidationScore(50);
            assertEquals(50, verificationInfo.getValidationScore(), "中间分数应为50");
        }
    }
}