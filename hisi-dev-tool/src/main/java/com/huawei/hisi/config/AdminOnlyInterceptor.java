package com.huawei.hisi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class AdminOnlyInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String method = request.getMethod();

        // GET 请求放行（/api/users/** 除外，该路径下 GET 也需管理员）
        if ("GET".equals(method) && !request.getRequestURI().startsWith("/api/users")) {
            return true;
        }

        // OPTIONS 预检请求放行
        if ("OPTIONS".equals(method)) {
            return true;
        }

        // POST/PUT/DELETE 及 /api/users/** 的 GET — 检查 ADMIN 权限
        SecurityContext ctx = (SecurityContext) request.getAttribute(SecurityContext.ATTR_NAME);

        if (ctx == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, 401, "请先登录后再执行此操作");
            return false;
        }

        if (!ctx.isAdmin()) {
            writeError(response, HttpStatus.FORBIDDEN, 403, "仅管理员可执行此操作");
            return false;
        }

        return true;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, int code, String message)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ApiResponse<>(code, message, null)));
    }
}
