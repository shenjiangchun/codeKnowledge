package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SiliconFlow Embedding 配置类
 */
@Configuration
@ConfigurationProperties(prefix = "siliconflow")
@Data
public class SiliconFlowConfig {

    private String apiKey;
    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String embeddingModel = "Qwen/Qwen3-VL-Embedding-8B";
    private int embeddingDimension = 4096;
    private int timeout = 30000;
    private boolean enabled = true;
}
