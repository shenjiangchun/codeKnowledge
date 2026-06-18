package com.huawei.hisi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS 跨域配置 + 角色拦截器注册
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private final AdminOnlyInterceptor adminOnlyInterceptor;

    // 默认允许所有来源（内网环境）
    private static final String DEFAULT_ALLOWED_ORIGINS = "*";

    @Value("${cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
    private String allowedOriginsConfig;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminOnlyInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/auth/**",
                        "/api/search/**",
                        "/api/vector-search/**");
    }

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

        // 使用允许的源模式而不是精确匹配，支持内网访问
        for (String origin : origins) {
            config.addAllowedOriginPattern(origin);
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