package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 智谱AI配置类
 * 用于代码描述生成和向量生成
 */
@Configuration
@ConfigurationProperties(prefix = "zhipu")
@Data
public class ZhipuConfig {

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * API基础URL
     */
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /**
     * 文本生成模型（代码描述）
     */
    private String textModel = "glm-4-flash";

    /**
     * 向量生成模型
     */
    private String embeddingModel = "embedding-3";

    /**
     * 向量维度
     */
    private int embeddingDimension = 1024;

    /**
     * 温度参数
     */
    private double temperature = 0.1;

    /**
     * 最大Token数
     */
    private int maxTokens = 200;

    /**
     * 超时时间（毫秒）
     */
    private int timeout = 30000;

    /**
     * 是否启用
     */
    private boolean enabled = true;

    /**
     * 是否使用智谱AI进行向量生成（替代本地模型）
     */
    private boolean useForEmbedding = true;

    /**
     * 最大重试次数（429 限流时）
     */
    private int maxRetries = 3;

    /**
     * 重试基础延迟（毫秒），实际延迟 = baseDelay * 2^retryCount
     */
    private long retryBaseDelayMs = 1000;
}
