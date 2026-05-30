package com.huawei.hisi.config;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.model.SearchErrorCode;
import com.huawei.hisi.neo4j.model.SearchException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理 Controller 层抛出的异常，返回标准化的 API 响应
 *
 * <p>支持的异常类型：
 * <ul>
 *   <li>MethodArgumentNotValidException - 参数校验失败 (400)</li>
 *   <li>IllegalArgumentException - 非法参数 (400)</li>
 *   <li>SearchException - 搜索异常 (根据错误码返回)</li>
 *   <li>RuntimeException - 运行时异常 (500)</li>
 *   <li>Exception - 其他未处理异常 (500)</li>
 * </ul>
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常
     *
     * @param ex 参数校验异常
     * @return 400 Bad Request 响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("参数校验失败: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, "参数校验失败: " + errors));
    }

    /**
     * 处理非法参数异常
     *
     * @param ex 非法参数异常
     * @return 400 Bad Request 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, ex.getMessage()));
    }

    /**
     * 处理缺失请求参数异常 (Spring 抛出的 MissingServletRequestParameterException)
     * 详细记录请求 URI、方法、查询串、参数名与类型，便于定位前端漏传字段的具体接口。
     *
     * @param ex      缺失参数异常
     * @param request 当前 HTTP 请求
     * @return 400 Bad Request 响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.error("[MissingParam] {} {}?{} | param='{}' type={} | message={}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                ex.getParameterName(),
                ex.getParameterType(),
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, "缺少必需参数: " + ex.getParameterName()));
    }

    /**
     * 处理搜索异常
     *
     * @param ex 搜索异常
     * @return 对应错误码的 HTTP 响应
     */
    @ExceptionHandler(SearchException.class)
    public ResponseEntity<ApiResponse<Void>> handleSearchException(SearchException ex) {
        SearchErrorCode errorCode = ex.getErrorCode();
        log.warn("搜索异常: code={}, message={}", errorCode.name(), ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getUserMessage()));
    }

    /**
     * 处理运行时异常
     *
     * @param ex 运行时异常
     * @return 500 Internal Server Error 响应
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
        log.error("[RuntimeException] {} {}?{}",
                request.getMethod(), request.getRequestURI(), request.getQueryString(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务内部错误: " + ex.getMessage()));
    }

    /**
     * 处理所有未捕获异常
     *
     * @param ex 异常
     * @return 500 Internal Server Error 响应
     */
    @ExceptionHandler(Exception.class)
    public Object handleException(Exception ex, HttpServletRequest request) {
        log.error("[UnhandledException] {} {}?{} | exType={} | message={}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                ex.getClass().getName(),
                ex.getMessage(),
                ex);

        // 如果是 SSE 端点，返回纯文本错误消息而不是 ApiResponse
        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();
        boolean isSSERequest = (accept != null && accept.contains("text/event-stream")) ||
                               (contentType != null && contentType.contains("text/event-stream"));

        if (isSSERequest || request.getRequestURI().contains("/stream") || request.getRequestURI().contains("/chat")) {
            // SSE 端点直接返回纯文本错误
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("服务异常: " + ex.getMessage());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务异常: " + ex.getMessage()));
    }

    /**
     * 处理 HttpMessageNotWritableException
     * 这个异常通常发生在 SSE 端点尝试返回 ApiResponse 时
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotWritableException.class)
    public Object handleHttpMessageNotWritableException(
            org.springframework.http.converter.HttpMessageNotWritableException ex,
            HttpServletRequest request) {
        log.error("HTTP 消息转换异常，通常发生在 SSE 端点: {}", ex.getMessage());

        // 对于 SSE 端点，返回纯文本
        if (request.getRequestURI().contains("/stream") || request.getRequestURI().contains("/chat")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("响应序列化失败: " + ex.getMessage());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "响应序列化失败: " + ex.getMessage()));
    }
}