package com.huawei.hisi.config;

import com.huawei.hisi.model.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("GlobalExceptionHandler 单元测试")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("处理 IllegalArgumentException - 应返回 400")
    void handleIllegalArgumentException_shouldReturn400() {
        IllegalArgumentException ex = new IllegalArgumentException("参数不能为空");

        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgumentException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertEquals("参数不能为空", response.getBody().getMessage());
    }

    @Test
    @DisplayName("处理 RuntimeException - 应返回 500")
    void handleRuntimeException_shouldReturn500() {
        RuntimeException ex = new RuntimeException("内部错误");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getQueryString()).thenReturn(null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleRuntimeException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("内部错误"));
    }

    @Test
    @DisplayName("处理 Exception - 应返回 500")
    void handleException_shouldReturn500() {
        Exception ex = new Exception("未知错误");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getRequestURI()).thenReturn("/api/test");

        ResponseEntity<ApiResponse<Void>> response = (ResponseEntity<ApiResponse<Void>>) handler.handleException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("未知错误"));
    }

    @Test
    @DisplayName("处理 Exception - SSE端点应返回纯文本")
    void handleException_sseEndpoint_shouldReturnPlainText() {
        Exception ex = new Exception("SSE错误");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn("text/event-stream");
        when(request.getRequestURI()).thenReturn("/api/claude/stream");

        ResponseEntity<String> response = (ResponseEntity<String>) handler.handleException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("SSE错误"));
    }

    @Test
    @DisplayName("处理 MethodArgumentNotValidException - 应返回 400 并包含字段错误")
    void handleValidationException_shouldReturn400WithFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("object", "fieldName", "不能为空");

        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(Collections.singletonList(fieldError));

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("fieldName"));
        assertTrue(response.getBody().getMessage().contains("不能为空"));
    }
}