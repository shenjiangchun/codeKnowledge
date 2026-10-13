package com.huawei.hisi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthFilter 单测：SSE/EventSource 无法携带 Authorization 头，
 * 故支持从 {@code ?token=} 查询参数回退校验（镜像 WebSocket 的 JwtHandshakeInterceptor）；
 * Authorization 头仍优先；无 token 时不设置 SecurityContext（零回归）。
 */
class JwtAuthFilterTest {

    private JwtTokenProvider tokenProvider;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties("01234567890123456789012345678901", 3600_000L);
        tokenProvider = new JwtTokenProvider(props);
        tokenProvider.init();
        filter = new JwtAuthFilter(tokenProvider);
    }

    private HttpServletRequest request(String authHeader, String queryToken) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(authHeader);
        when(req.getParameter("token")).thenReturn(queryToken);
        return req;
    }

    @Test
    @DisplayName("无 Authorization 头但有 ?token= 查询参数：回退校验并设置 SecurityContext")
    void fallbackToQueryParamToken() throws IOException, ServletException {
        String token = tokenProvider.generateToken("alice", "USER");
        HttpServletRequest req = request(null, token);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(req).setAttribute(eq(SecurityContext.ATTR_NAME), eq(new SecurityContext("alice", "USER")));
        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("Authorization 头优先于查询参数")
    void headerTakesPrecedenceOverQueryParam() throws IOException, ServletException {
        String headerToken = tokenProvider.generateToken("root", "ADMIN");
        String queryToken = tokenProvider.generateToken("alice", "USER");
        HttpServletRequest req = request("Bearer " + headerToken, queryToken);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(req).setAttribute(eq(SecurityContext.ATTR_NAME), eq(new SecurityContext("root", "ADMIN")));
    }

    @Test
    @DisplayName("无 token（头与查询参数均无）：不设置 SecurityContext（零回归）")
    void noTokenSetsNoContext() throws IOException, ServletException {
        HttpServletRequest req = request(null, null);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(req, never()).setAttribute(eq(SecurityContext.ATTR_NAME), any());
    }
}
