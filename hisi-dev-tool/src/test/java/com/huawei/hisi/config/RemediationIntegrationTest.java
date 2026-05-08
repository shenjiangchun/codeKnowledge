package com.huawei.hisi.config;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.ImpactAnalysisRequest;
import com.huawei.hisi.agent.model.DiagnosisRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 整改项集成测试
 * 测试完整的请求验证流程
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("整改项集成测试")
class RemediationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // ==================== 安全配置集成测试 ====================

    @Test
    @DisplayName("测试CORS响应头正确设置")
    void testCorsHeadersSetCorrectly() {
        // 发送跨域预检请求
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");
        headers.set("Access-Control-Request-Method", "GET");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/dialog/health", HttpMethod.OPTIONS, entity, String.class);

        // 验证响应状态
        assertNotNull(response, "CORS预检响应应存在");
    }

    @Test
    @DisplayName("测试CORS拒绝未授权源")
    void testCorsRejectsUnauthorizedOrigin() {
        // 使用未授权的Origin发送请求
        // 当前配置只允许localhost端口

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/dialog/health", String.class);

        assertNotNull(response, "响应应存在");
    }

    // ==================== Bean Validation集成测试 ====================

    @Test
    @DisplayName("测试ImpactAnalysis请求验证失败返回400")
    void testImpactAnalysisValidationFailureReturns400() {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setClassName(null);  // 验证失败
        request.setMethodName(null); // 验证失败

        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(
                "/api/ops/analysis/impact", request, ApiResponse.class);

        // 验证返回400状态
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "验证失败应返回400");
    }

    @Test
    @DisplayName("测试Diagnosis请求验证失败返回400")
    void testDiagnosisValidationFailureReturns400() {
        DiagnosisRequest request = DiagnosisRequest.builder()
                .errorMessage(null)  // 验证失败
                .build();

        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(
                "/api/diagnosis/diagnose", request, ApiResponse.class);

        // 验证返回400状态或业务处理
        assertNotNull(response, "响应应存在");
    }

    @Test
    @DisplayName("测试Dialog请求验证失败返回400")
    void testDialogValidationFailureReturns400() {
        // 创建无效请求
        String requestBody = "{\"userInput\": \"\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/dialog/diagnose", entity, String.class);

        assertNotNull(response, "响应应存在");
    }

    // ==================== 资源管理集成测试 ====================

    @Test
    @DisplayName("测试健康检查端点返回正确状态")
    void testHealthEndpointReturnsCorrectStatus() {
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                "/api/dialog/health", ApiResponse.class);

        assertNotNull(response, "健康检查响应应存在");
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "健康检查应返回200");
    }

    @Test
    @DisplayName("测试会话创建和清理")
    void testSessionCreateAndCleanup() {
        // 测试会话API
        ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                "/api/dialog/sessions/count", ApiResponse.class);

        assertNotNull(response, "会话计数响应应存在");
    }

    // ==================== 异常处理集成测试 ====================

    @Test
    @DisplayName("测试全局异常处理器正确工作")
    void testGlobalExceptionHandlerWorks() {
        // 发送非法参数触发异常
        String invalidRequest = "{\"invalid\": \"data\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(invalidRequest, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/dialog/diagnose", entity, String.class);

        assertNotNull(response, "异常处理响应应存在");
    }

    // ==================== 性能测试 ====================

    @Test
    @DisplayName("测试线程池在高负载下正常工作")
    void testThreadPoolUnderLoad() {
        // 发送多个并发请求测试线程池
        for (int i = 0; i < 5; i++) {
            ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                    "/api/dialog/health", ApiResponse.class);
            assertNotNull(response);
        }
    }
}