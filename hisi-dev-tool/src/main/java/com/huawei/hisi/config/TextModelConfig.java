package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 自然语言生成模型（Text/Chat）统一配置
 * 替代原来 ZhipuConfig 中的 text-model 部分。
 * 支持任意 OpenAI 兼容的 /chat/completions 端点。
 */
@Configuration
@ConfigurationProperties(prefix = "text-model")
@Data
public class TextModelConfig {

    /** API 密钥 */
    private String apiKey;

    /** API 基础 URL（需以 /v1 或 /v4 等版本路径结尾，不含 /chat/completions） */
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /** 模型名称 */
    private String model = "glm-4-flash";

    /** 多模态/Vision 模型名称（如 glm-4v-flash），不配置则使用 model 字段 */
    private String visionModel;

    /** 温度参数 */
    private double temperature = 0.1;

    /** 最大 Token 数 */
    private int maxTokens = 200;

    /** HTTP 请求超时（毫秒） */
    private int timeout = 30000;

    /** 429 限流时最大重试次数 */
    private int maxRetries = 3;

    /** 重试基础延迟（毫秒），实际延迟 = baseDelay × 2^retryCount。
     *  令牌桶启用后大多数 429 已被提前压制，这里只覆盖偶发抖动。 */
    private long retryBaseDelayMs = 5000;

    /** 令牌桶 QPS（每秒允许的请求数）。 */
    private double qps = 3.0;

    /** 令牌桶突发容量。 */
    private int burst = 6;

    /** 获取令牌的最长等待时间（秒）。 */
    private long acquireTimeoutSeconds = 120;
}
