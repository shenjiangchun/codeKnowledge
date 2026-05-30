package com.huawei.hisi.config;

import com.huawei.hisi.apm.handler.ApmWebSocketHandler;
import com.huawei.hisi.handler.TerminalWebSocketHandler;
import com.huawei.hisi.agent.event.AgentEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

/**
 * WebSocket 配置类
 * 用于终端实时通信和 Agent 诊断事件推送
 *
 * 安全说明：
 * - 使用配置化的 allowedOrigins，不使用 "*" 通配符
 * - 与 CorsConfig 保持一致的配置源
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final AgentEventPublisher agentEventPublisher;
    private final ApmWebSocketHandler apmWebSocketHandler;

    // 默认允许的源（开发环境）- 与 CorsConfig 保持一致
    private static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:5173,http://localhost:5174,http://localhost:5175,http://localhost:3000";

    @Value("${cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
    private String allowedOriginsConfig;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 解析允许的源列表
        String[] allowedOrigins = parseAllowedOrigins();

        // 注册终端 WebSocket 处理器
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal")
                .setAllowedOrigins(allowedOrigins)
                .addInterceptors(new HttpSessionHandshakeInterceptor());

        // 注册 Agent 诊断事件 WebSocket 处理器
        registry.addHandler(agentEventPublisher, "/ws/diagnosis")
                .setAllowedOrigins(allowedOrigins)
                .addInterceptors(new HttpSessionHandshakeInterceptor());

        // 注册 APM WebSocket 处理器
        registry.addHandler(apmWebSocketHandler, "/ws/apm")
                .setAllowedOrigins(allowedOrigins)
                .addInterceptors(new HttpSessionHandshakeInterceptor());

        log.info("WebSocket handlers registered with allowed origins: {}", Arrays.toString(allowedOrigins));
    }

    /**
     * 解析允许的源配置
     * 与 CorsConfig 保持一致的解析逻辑
     */
    private String[] parseAllowedOrigins() {
        String originsConfig = (allowedOriginsConfig != null && !allowedOriginsConfig.isEmpty())
                ? allowedOriginsConfig
                : DEFAULT_ALLOWED_ORIGINS;

        return Arrays.stream(originsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}