package com.huawei.hisi.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置：Long → String 序列化 + 保留 JavaTimeModule
 * 解决雪花算法 ID 超出 JS Number 安全范围（2^53）导致前端精度丢失的问题
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(long.class, ToStringSerializer.instance);
            // 必须同时注册 JavaTimeModule，否则 builder.modules() 会覆盖 Spring Boot 自动注册的模块
            builder.modules(module, new JavaTimeModule());
        };
    }
}
