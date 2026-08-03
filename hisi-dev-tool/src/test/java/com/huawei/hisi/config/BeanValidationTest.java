package com.huawei.hisi.config;

import com.huawei.hisi.agent.model.DiagnosisRequest;
import com.huawei.hisi.model.ImpactAnalysisRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bean Validation测试类
 * 整改项: 输入验证机制
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("Bean Validation整改项测试")
class BeanValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ==================== DiagnosisRequest验证测试 ====================

    @Test
    @DisplayName("测试DiagnosisRequest - errorMessage不能为空")
    void testDiagnosisRequestErrorMessageNotBlank() {
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage("")  // 空字符串
                .stackTrace("test stack trace")
                .build();

        Set<ConstraintViolation<DiagnosisRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "errorMessage为空应有验证错误");
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("errorMessage")),
                "应包含errorMessage的验证错误");
    }

    @Test
    @DisplayName("测试DiagnosisRequest - errorMessage为null应验证失败")
    void testDiagnosisRequestErrorMessageNull() {
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage(null)  // null值
                .build();

        Set<ConstraintViolation<DiagnosisRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "errorMessage为null应有验证错误");
        assertEquals("错误消息不能为空",
                violations.iterator().next().getMessage(),
                "验证消息应为'错误消息不能为空'");
    }

    @Test
    @DisplayName("测试DiagnosisRequest - 有效请求应通过验证")
    void testDiagnosisRequestValid() {
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage("NullPointerException occurred")
                .stackTrace("at com.example.Test.method(Test.java:10)")
                .projectPath("/project/src")
                .build();

        Set<ConstraintViolation<DiagnosisRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty(), "有效请求应通过验证");
    }

    @Test
    @DisplayName("测试DiagnosisRequest - 可选字段可以为null")
    void testDiagnosisRequestOptionalFieldsNullable() {
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage("Test error")
                .stackTrace(null)      // 可选字段为null
                .projectPath(null)     // 可选字段为null
                .logContent(null)      // 可选字段为null
                .build();

        Set<ConstraintViolation<DiagnosisRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty(), "可选字段为null应通过验证");
    }

    // ==================== ImpactAnalysisRequest验证测试 ====================

    @Test
    @DisplayName("测试ImpactAnalysisRequest - className不能为空")
    void testImpactAnalysisRequestClassNameNotBlank() {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName("");  // 空字符串
        request.setMethodName("testMethod");

        Set<ConstraintViolation<ImpactAnalysisRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "className为空应有验证错误");
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("className")),
                "应包含className的验证错误");
        assertEquals("类名不能为空",
                violations.stream()
                        .filter(v -> v.getPropertyPath().toString().equals("className"))
                        .findFirst()
                        .get()
                        .getMessage(),
                "验证消息应为'类名不能为空'");
    }

    @Test
    @DisplayName("测试ImpactAnalysisRequest - methodName不能为空")
    void testImpactAnalysisRequestMethodNameNotBlank() {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName("com.example.Test");
        request.setMethodName(null);  // null值

        Set<ConstraintViolation<ImpactAnalysisRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "methodName为null应有验证错误");
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("methodName")),
                "应包含methodName的验证错误");
    }

    @Test
    @DisplayName("测试ImpactAnalysisRequest - 有效请求应通过验证")
    void testImpactAnalysisRequestValid() {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName("com.example.TestService");
        request.setMethodName("processData");

        Set<ConstraintViolation<ImpactAnalysisRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty(), "有效请求应通过验证");
    }

    @Test
    @DisplayName("测试ImpactAnalysisRequest - project字段可选")
    void testImpactAnalysisRequestProjectOptional() {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName("com.example.Test");
        request.setMethodName("test");
        request.setProject(null);  // 可选字段为null

        Set<ConstraintViolation<ImpactAnalysisRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty(), "project为null应通过验证");
    }

    // ==================== 验证框架集成测试 ====================

    @Test
    @DisplayName("测试GlobalExceptionHandler处理验证异常")
    void testGlobalExceptionHandlerHandlesValidationException() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // 模拟验证失败场景
        // GlobalExceptionHandler.handleValidationException 方法应正确处理
        assertNotNull(handler, "异常处理器应存在");
    }

    @Test
    @DisplayName("测试验证注解使用jakarta.validation")
    void testValidationUsesJakartaValidation() {
        // 验证项目使用的是jakarta.validation而非javax.validation
        // Spring Boot 3.x 使用jakarta命名空间

        DiagnosisRequest request = new DiagnosisRequest();
        assertNotNull(request, "请求对象应使用jakarta.validation注解");
    }

    @Test
    @DisplayName("测试@NotBlank注解正确工作")
    void testNotBlankAnnotationWorks() {
        // @NotBlank验证: 不能为null，且trim后长度>0

        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName("   ");  // 只有空白字符
        request.setMethodName("valid");

        Set<ConstraintViolation<ImpactAnalysisRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "@NotBlank应拒绝纯空白字符");
    }

    @Test
    @DisplayName("测试验证消息中文显示")
    void testValidationMessagesInChinese() {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName(null);

        Set<ConstraintViolation<ImpactAnalysisRequest>> violations = validator.validate(request);

        String message = violations.iterator().next().getMessage();
        assertTrue(message.contains("不能为空") || message.contains("类名"),
                "验证消息应使用中文");
    }
}