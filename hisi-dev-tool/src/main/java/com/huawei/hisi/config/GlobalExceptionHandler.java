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
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;
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
                safeQuery(request),
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
    public Object handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
        if (clientGone(ex, request)) {
            // 流式接口上客户端断开有时被包成 RuntimeException 抛上来：同样不是错误，也不要再写响应
            log.debug("[ClientDisconnected] {} {} | {}", request.getMethod(), request.getRequestURI(), rootMessage(ex));
            ModelAndView handled = new ModelAndView();
            handled.clear();
            return handled;
        }
        log.error("[RuntimeException] {} {}?{}",
                request.getMethod(), request.getRequestURI(), safeQuery(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务内部错误: " + ex.getMessage()));
    }

    /**
     * 接口不存在：返回 404，而不是落进兜底的 500「服务异常」。
     *
     * <p>Spring 找不到对应处理器时，请求会落到静态资源处理器并抛 {@link NoResourceFoundException}
     * （或 {@link NoHandlerFoundException}）。这是客户端调错了地址，不是服务端故障：
     * 以前它被兜底 handler 包成 500，排障时会把「接口没实现」误判成「服务挂了」，还在错误日志里刷 ERROR。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoResource(Exception ex, HttpServletRequest request) {
        log.warn("[NotFound] {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "接口不存在: " + request.getRequestURI()));
    }

    /**
     * 处理所有未捕获异常
     *
     * @param ex 异常
     * @return 500 Internal Server Error 响应
     */
    @ExceptionHandler(Exception.class)
    public Object handleException(Exception ex, HttpServletRequest request) {
        if (clientGone(ex, request)) {
            // 客户端断开（关页面、刷新、断网）不是服务端错误：流式接口写不回去是正常现象，响应也已经没法再写，
            // 返回「已清空」的 ModelAndView 表示「已处理、无需再输出」，避免再往一条断掉的连接上写而抛出新的异常
            log.debug("[ClientDisconnected] {} {} | {}", request.getMethod(), request.getRequestURI(), rootMessage(ex));
            ModelAndView handled = new ModelAndView();
            handled.clear();
            return handled;
        }

        log.error("[UnhandledException] {} {}?{} | exType={} | message={}",
                request.getMethod(),
                request.getRequestURI(),
                safeQuery(request),
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

    private static final java.util.regex.Pattern SENSITIVE_QUERY = java.util.regex.Pattern.compile(
            "(?i)([?&](?:token|access_token|password|passwd|secret|api_?key|authorization)=)[^&]*");

    /** 写日志用的查询串：流式接口把登录 token 放在查询参数里（EventSource 设不了请求头），不能原样落盘。 */
    static String safeQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher matcher = SENSITIVE_QUERY.matcher("?" + query);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + "***"));
        }
        matcher.appendTail(out);
        return out.substring(1);
    }

    private static final Set<String> STREAM_URI_MARKERS = Set.of("/stream");

    /** 流式（SSE）请求：浏览器一断开，服务端再写就会抛 IO 异常，这不是服务端的错。 */
    static boolean clientGone(Throwable ex, HttpServletRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException) {
                return true;
            }
        }
        if (!(ex instanceof java.io.IOException)) {
            return false;
        }
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();
        return (accept != null && accept.contains("text/event-stream"))
                || (uri != null && STREAM_URI_MARKERS.stream().anyMatch(uri::contains));
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
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