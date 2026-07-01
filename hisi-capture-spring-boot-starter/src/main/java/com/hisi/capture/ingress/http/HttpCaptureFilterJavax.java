package com.hisi.capture.ingress.http;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet API (javax) 版本 HTTP 采集过滤器。
 * 适用于 Spring Boot 2.x（javax.servlet）。
 * 由 CaptureWebAutoConfiguration.JavaxFilterConfig 通过 @ConditionalOnClass 注册，不使用 @Component。
 */
public class HttpCaptureFilterJavax extends OncePerRequestFilter {

    @Autowired
    private SizeLimiter sizeLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
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
