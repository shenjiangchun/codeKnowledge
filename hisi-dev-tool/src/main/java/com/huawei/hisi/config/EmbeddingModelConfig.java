package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 向量生成模型（Embedding）统一配置
 * 替代原来按平台分散的 ZhipuConfig/SiliconFlowConfig/IFlytekConfig 中的 embedding 部分。
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

    /** 重试基础延迟（毫秒），实际延迟 = baseDelay × 2^retryCount */
    private long retryBaseDelayMs = 60000;
}
