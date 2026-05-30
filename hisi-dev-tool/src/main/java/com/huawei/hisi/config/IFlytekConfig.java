package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 科大讯飞 Embedding 配置类
 */
@Configuration
@ConfigurationProperties(prefix = "iflytek")
@Data
public class IFlytekConfig {

    private String apiKey;
    private String baseUrl = "https://maas-api.cn-huabei-1.xf-yun.com/v2";
    private String embeddingModel = "xop3qwen8bembedding";
    private int embeddingDimension = 768;
    private int timeout = 30000;
    private boolean enabled = true;
}
