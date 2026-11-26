package com.huawei.hisi.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminOnlyInterceptor 单测：/api/workflow/** 全部端点（含 GET 流式/报告读取）要求登录，
 * 未携带有效 token 返回 401；非 workflow 路径保持原有放行/ADMIN 语义不变（零回归）。
 */
class AdminOnlyInterceptorTest {

    private AdminOnlyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AdminOnlyInterceptor();
    }

    private HttpServletRequest request(String method, String uri, SecurityContext ctx) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getAttribute(SecurityContext.ATTR_NAME)).thenReturn(ctx);
        return req;
    }

    private HttpServletResponse response() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return resp;
    }

    @Test
    @DisplayName("workflow GET 未登录：401 且拦截")
    void workflowGetUnauthenticatedReturns401() throws Exception {
        HttpServletRequest req = request("GET", "/api/workflow/sessions/s1/report", null);
        HttpServletResponse resp = response();

        boolean allowed = interceptor.preHandle(req, resp, new Object());

        assertThat(allowed).isFalse();
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("workflow GET 已登录（非管理员）：放行（读操作只要求登录）")
    void workflowGetAuthenticatedNonAdminPasses() throws Exception {
        HttpServletRequest req = request("GET", "/api/workflow/sessions/s1/report",
                new SecurityContext("alice", "USER"));
        HttpServletResponse resp = response();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    @DisplayName("workflow POST 已登录但非管理员：403")
    void workflowPostNonAdminReturns403() throws Exception {
        HttpServletRequest req = request("POST", "/api/workflow/start",
                new SecurityContext("alice", "USER"));
        HttpServletResponse resp = response();

        boolean allowed = interceptor.preHandle(req, resp, new Object());

        assertThat(allowed).isFalse();
        verify(resp).setStatus(403);
    }

    @Test
    @DisplayName("workflow POST 管理员：放行")
    void workflowPostAdminPasses() throws Exception {
        HttpServletRequest req = request("POST", "/api/workflow/start",
                new SecurityContext("root", "ADMIN"));
        HttpServletResponse resp = response();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    @DisplayName("workflow OPTIONS 预检：放行（浏览器预检不携带 token）")
    void workflowOptionsPasses() throws Exception {
        HttpServletRequest req = request("OPTIONS", "/api/workflow/start", null);
        HttpServletResponse resp = response();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    @DisplayName("非 workflow 的 GET：仍匿名放行（零回归）")
    void nonWorkflowGetStillPasses() throws Exception {
        HttpServletRequest req = request("GET", "/api/search/semantic", null);
        HttpServletResponse resp = response();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    @DisplayName("非 workflow 的 POST 未登录：仍 401（零回归）")
    void nonWorkflowPostUnauthenticatedStill401() throws Exception {
        HttpServletRequest req = request("POST", "/api/projects", null);
        HttpServletResponse resp = response();

        boolean allowed = interceptor.preHandle(req, resp, new Object());

        assertThat(allowed).isFalse();
        verify(resp).setStatus(401);
    }

    // ───────── v2 工作流路由（/api/v2/workflow/**）与 v1 采用同一套规则 ─────────

    @Test
    @DisplayName("v2 workflow GET 未登录：401 且拦截")
    void v2WorkflowGetUnauthenticatedReturns401() throws Exception {
        HttpServletRequest req = request("GET", "/api/v2/workflow/sessions/s1/stream", null);
        HttpServletResponse resp = response();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        verify(resp).setStatus(401);
    }

    @Test
    @DisplayName("v2 workflow GET 已登录（非管理员）：放行")
    void v2WorkflowGetAuthenticatedNonAdminPasses() throws Exception {
        HttpServletRequest req = request("GET", "/api/v2/workflow/sessions/s1/report",
                new SecurityContext("alice", "USER"));
        HttpServletResponse resp = response();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    @DisplayName("v2 workflow POST 已登录但非管理员：403")
    void v2WorkflowPostNonAdminReturns403() throws Exception {
        HttpServletRequest req = request("POST", "/api/v2/workflow/start",
                new SecurityContext("alice", "USER"));
        HttpServletResponse resp = response();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        verify(resp).setStatus(403);
    }
}
