package com.huawei.hisi.config;

import com.huawei.hisi.mcp.McpRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 进程内 MCP 端点（/mcp，pi 经 streamable HTTP 连接）的两道门卫：
 * <ul>
 *   <li>只允许本机回环访问：后端监听 0.0.0.0 时，这个端点不允许被其他机器调用；</li>
 *   <li>带令牌的请求：按 MCP 会话 ID 记下用户令牌（见 {@link McpRequestContext}），写工具据此以用户身份调接口。</li>
 * </ul>
 */
@Component
public class McpEndpointFilter extends OncePerRequestFilter {

    /** 带用户令牌的请求所用的请求头名（与安装器写进 act 配置目录 mcp.json 的请求头一致）。 */
    public static final String TOKEN_HEADER = "X-Scope-Token";

    private final McpRequestContext requestContext;

    public McpEndpointFilter(McpRequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().equals("/mcp")) {
            chain.doFilter(request, response);
            return;
        }
        if (!isLoopback(request.getRemoteAddr())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("MCP endpoint is loopback-only");
            return;
        }
        requestContext.bind(request.getHeader("Mcp-Session-Id"), request.getHeader(TOKEN_HEADER));
        chain.doFilter(request, response);
    }

    private static boolean isLoopback(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr);
    }
}
