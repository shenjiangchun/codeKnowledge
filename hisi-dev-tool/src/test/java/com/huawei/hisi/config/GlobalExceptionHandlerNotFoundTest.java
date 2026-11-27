package com.huawei.hisi.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.huawei.hisi.model.ApiResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归：接口不存在时返回 404，而不是 500「服务异常」。
 *
 * <p>前端调了后端从未实现的接口（如 /api/ram/status/phase2/v2/start）时，Spring 找不到处理器，
 * 请求落到静态资源处理器抛 {@link NoResourceFoundException}；以前它被兜底 handler 包成 500，
 * 排障时会把「接口没实现」误判成「服务挂了」。
 */
@DisplayName("GlobalExceptionHandler：接口不存在返回 404")
class GlobalExceptionHandlerNotFoundTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(request.getContentType()).thenReturn("application/json");
        return request;
    }

    @Test
    @DisplayName("NoResourceFoundException（/v2/... 这类不存在的路由）→ 404，消息指明接口不存在")
    void noResourceIsReportedAs404Not500() {
        HttpServletRequest req = request("POST", "/api/ram/status/phase2/v2/start");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.POST, "/api/ram/status/phase2/v2/start"), req);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals(404, body.getCode());
        assertEquals("接口不存在: /api/ram/status/phase2/v2/start", body.getMessage());
    }

    @Test
    @DisplayName("NoHandlerFoundException → 404")
    void noHandlerIsReportedAs404() {
        HttpServletRequest req = request("GET", "/api/nope");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleNoResource(
                new NoHandlerFoundException("GET", "/api/nope", null), req);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(404, resp.getBody().getCode());
    }

    @Test
    @DisplayName("兜底 handler 不变：真正的未知异常仍是 500（不能把服务端故障也降级成 404）")
    void genuineServerErrorsStay500() {
        HttpServletRequest req = request("GET", "/api/anything");

        Object resp = handler.handleException(new IllegalStateException("boom"), req);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ((ResponseEntity<?>) resp).getStatusCode());
    }
}
