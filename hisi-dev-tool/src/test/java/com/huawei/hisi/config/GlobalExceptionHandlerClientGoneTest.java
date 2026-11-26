package com.huawei.hisi.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 浏览器断开流式连接不是服务端错误；流式接口的查询串里带着登录 token，落盘前必须遮掉。
 * 真机上每次关掉正在分析的页面，都会留下一条带完整 token 的 ERROR 日志——这里把它锁住。
 */
class GlobalExceptionHandlerClientGoneTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static HttpServletRequest request(String uri, String accept) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getHeader("Accept")).thenReturn(accept);
        when(request.getMethod()).thenReturn("GET");
        return request;
    }

    @Test
    void theLoginTokenInTheQueryStringIsMaskedBeforeLogging() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn("afterSeq=0&token=eyJhbGciOi.secret.sig&password=123&x=1");

        String safe = GlobalExceptionHandler.safeQuery(request);

        assertFalse(safe.contains("eyJhbGciOi"), "token 不能原样进日志：" + safe);
        assertFalse(safe.contains("password=123"), "密码不能原样进日志：" + safe);
        assertTrue(safe.contains("afterSeq=0"));
        assertTrue(safe.contains("token=***"));
        when(request.getQueryString()).thenReturn(null);
        assertEquals("", GlobalExceptionHandler.safeQuery(request));
    }

    @Test
    void anIOExceptionWhileStreamingMeansTheClientLeftAndIsNotAnError() {
        HttpServletRequest stream = request("/api/v2/workflow/sessions/abc/stream", "text/event-stream");

        Object result = handler.handleException(new IOException("你的主机中的软件中止了一个已建立的连接。"), stream);

        assertTrue(result instanceof ModelAndView);
        assertTrue(((ModelAndView) result).wasCleared(), "返回已清空的 ModelAndView：不再往断掉的连接上写任何东西");
        assertTrue(GlobalExceptionHandler.clientGone(
                new RuntimeException(new AsyncRequestNotUsableException("failed to flush", new IOException("broken pipe"))),
                request("/anything", null)));
        // 被包成 RuntimeException 的断连走的是 RuntimeException 的处理器：同样不能当成错误
        assertTrue(handler.handleRuntimeException(
                new RuntimeException(new AsyncRequestNotUsableException("failed to flush", new IOException("broken pipe"))),
                request("/anything", null)) instanceof ModelAndView);
    }

    @Test
    void anIOExceptionOutsideAStreamRequestIsStillReportedAsAnError() {
        Object result = handler.handleException(new IOException("disk read failed"),
                request("/api/log/report/1/export/md", "*/*"));

        assertTrue(result instanceof ResponseEntity, "普通接口上的 IO 异常是真问题，照常按 500 处理");
        assertTrue(GlobalExceptionHandler.clientGone(new IOException("x"),
                request("/api/v2/workflow/sessions/abc/stream", "text/event-stream")));
    }
}
