package com.huawei.hisi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Slf4j
@Component
public class LocalhostOnlyInterceptor implements HandlerInterceptor {

    private static final Set<String> LOCALHOST_ADDRESSES = Set.of(
            "127.0.0.1",
            "0:0:0:0:0:0:0:1",
            "::1"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (isLocalhost(request)) {
            return true;
        }

        log.warn("Non-localhost access blocked for write endpoint: {} {} from {}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                objectMapper.writeValueAsString(new ApiResponse<>(403, "仅限本地访问：生成和修改操作不允许远程执行", null))
        );
        return false;
    }

    private boolean isLocalhost(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (LOCALHOST_ADDRESSES.contains(remoteAddr)) {
            return true;
        }
        // Forwarded headers (reverse proxy scenario)
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String clientIp = forwardedFor.split(",")[0].trim();
            return LOCALHOST_ADDRESSES.contains(clientIp);
        }
        return false;
    }

    record ApiResponse<T>(int code, String message, T data) {}
}
