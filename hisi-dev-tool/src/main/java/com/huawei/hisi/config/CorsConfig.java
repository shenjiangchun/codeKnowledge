package com.huawei.hisi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * CORS 跨域配置
 * M1.4 - 支持前端跨域访问
 */
@Configuration
public class CorsConfig {

    // 默认允许的源（开发环境）
    private static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:5173,http://localhost:5174,http://localhost:5175,http://localhost:3000";

    @Value("${cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
    private String allowedOriginsConfig;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 从环境变量读取允许的源，支持逗号分隔的多个源
        // 使用 null 检查确保在非Spring环境下也能工作
        String originsConfig = (allowedOriginsConfig != null && !allowedOriginsConfig.isEmpty())
                ? allowedOriginsConfig
                : DEFAULT_ALLOWED_ORIGINS;

        String[] origins = Arrays.stream(originsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        for (String origin : origins) {
            config.addAllowedOrigin(origin);
        }

        // 允许所有请求头
        config.addAllowedHeader("*");

        // 允许所有 HTTP 方法
        config.addAllowedMethod("*");

        // 允许携带凭证（Cookie、Authorization 等）
        config.setAllowCredentials(true);

        // 预检请求缓存时间（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}