package com.hisi.capture.ingress.http;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Servlet API (jakarta) 版本 HTTP 采集过滤器。
 * 适用于 Spring Boot 3.x（jakarta.servlet）。
 *
 * 直接实现 jakarta.servlet.Filter，避免依赖 Spring Web 5.3 的 OncePerRequestFilter（其基类仍绑定 javax.servlet）。
 * once-per-request 语义由内部 request attribute 标记保证。
 *
 * 由 CaptureWebAutoConfiguration.JakartaFilterConfig 通过 @ConditionalOnClass 注册，不使用 @Component。
 */
public class HttpCaptureFilterJakarta implements Filter {

    private static final String FILTERED_ATTR =
            "com.hisi.capture.ingress.http.HttpCaptureFilterJakarta.FILTERED";

    @Autowired
    private SizeLimiter sizeLimiter;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (req.getAttribute(FILTERED_ATTR) != null) {
            chain.doFilter(request, response);
            return;
        }
        req.setAttribute(FILTERED_ATTR, Boolean.TRUE);

        if (!shouldCapture(req)) {
            chain.doFilter(req, resp);
            return;
        }

        String entryTag = UUID.randomUUID().toString();
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("uri", req.getRequestURI());
        params.put("method", req.getMethod());
        params.put("headers", sanitizeHeaders(req));
        params.put("body", sizeLimiter.limitBody(req.getInputStream()));

        EntryContext entry = new EntryContext(
            entryTag, EntryType.HTTP, req.getRequestURI(), params, System.currentTimeMillis());

        CaptureContext ctx = new CaptureContext();
        ctx.setEntry(entry);
        CaptureContextHolder.set(ctx);

        try {
            chain.doFilter(req, resp);
        } finally {
            CaptureContextHolder.clear();
        }
    }

    private boolean shouldCapture(HttpServletRequest req) {
        return true;
    }

    private Map<String, String> sanitizeHeaders(HttpServletRequest req) {
        Map<String, String> headers = new HashMap<String, String>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = req.getHeader(name);
            if (name.toLowerCase().matches("authorization|cookie|x-token|x-auth")) {
                value = "***REDACTED***";
            }
            headers.put(name, value);
        }
        return headers;
    }
}
