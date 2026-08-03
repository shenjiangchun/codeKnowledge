package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 向量生成模型（Embedding）统一配置。
 * 支持任意 OpenAI 兼容的 /embeddings 端点。
 */
@Configuration
@ConfigurationProperties(prefix = "embedding")
@Data
public class EmbeddingModelConfig {

    /** API 密钥 */
    private String apiKey;

    /** API 基础 URL（需以 /v1 或 /v4 等版本路径结尾，不含 /embeddings） */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** 模型名称 */
    private String model = "Qwen/Qwen3-VL-Embedding-8B";

    /** 向量维度（用于 Neo4j 向量索引创建和返回结果校验） */
    private int dimension = 4096;

    /** HTTP 请求超时（毫秒） */
    private int timeout = 30000;

    /** 429 限流时最大重试次数 */
    private int maxRetries = 3;

    /** 重试基础延迟（毫秒），实际延迟 = baseDelay × 2^retryCount。
     *  令牌桶启用后大多数 429 已被提前压制，这里只覆盖偶发抖动，
     *  推荐 5000 即可；若服务端返回 Retry-After 则以其为准。 */
    private long retryBaseDelayMs = 5000;

    /** 令牌桶 QPS（每秒允许的请求数，平滑节奏避免触发服务端 429）。 */
    private double qps = 5.0;

    /** 令牌桶突发容量（瞬时最多并发请求数，启动时桶满）。 */
    private int burst = 10;

    /** 获取令牌的最长等待时间（秒），超时则放弃本次请求。 */
    private long acquireTimeoutSeconds = 120;

    /** ROMA CSB 网关鉴权 Token（可选，部分内网网关需要 csb-token header） */
    private String csbToken;
}
