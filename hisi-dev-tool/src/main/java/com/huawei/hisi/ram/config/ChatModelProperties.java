package com.huawei.hisi.ram.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "chat")
@Data
public class ChatModelProperties {
    private Map<String, ModelSpec> models;

    @Data
    public static class ModelSpec {
        private String provider;
        private int maxContext;
        private Map<String, Integer> scenarioMaxTokens;
        private String endpoint;
        private String apiKey;
    }
}
