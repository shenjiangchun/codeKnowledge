package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.EmbeddingModelConfig;
import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.utils.TokenBucketRateLimiter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 统一向量生成服务
 * 调用 OpenAI 兼容的 /embeddings 端点，配置来自 EmbeddingModelConfig。
 *
 * <p>并发控制：使用令牌桶 ({@link TokenBucketRateLimiter}) 主动限流，
 * 配合 429 / Retry-After / 偶发 IO 异常的指数退避重试，L2 归一化 + 维度校验。
 *
 * 取代原来的 SiliconFlowEmbeddingService / IFlytekEmbeddingService / ZhipuService(embedding 部分)
 */
@Slf4j
@Service
public class UnifiedEmbeddingService {

    private final EmbeddingModelConfig config;
    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TokenBucketRateLimiter rateLimiter;

    public UnifiedEmbeddingService(EmbeddingModelConfig config, ProxyConfig proxyConfig) {
        this.config = config;
        this.proxyConfig = proxyConfig;
    }

    @PostConstruct
    public void init() {
        this.rateLimiter = new TokenBucketRateLimiter(
                "embedding", config.getQps(), config.getBurst());
    }

    @PreDestroy
    public void destroy() {
        if (rateLimiter != null) {
            rateLimiter.shutdown();
        }
    }

    /**
     * 生成文本的嵌入向量
     *
     * @param text 输入文本
     * @return 归一化后的浮点向量
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文本不能为空");
        }

        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 1) 先拿令牌
            try {
                if (!rateLimiter.tryAcquire(config.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                    throw new RuntimeException("[Embedding] 获取令牌超时（"
                            + config.getAcquireTimeoutSeconds() + "s），上游限流过严或负载过高");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[Embedding] 等待令牌被中断", ie);
            }

            try {
                String url = config.getBaseUrl() + "/embeddings";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getModel());
                requestBody.put("input", text);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(
                        objectMapper.writeValueAsString(requestBody), headers);

                log.debug("[Embedding] 调用向量生成API: url={}, model={}, textLen={}, attempt={}/{}, permits={}",
                        url, config.getModel(), text.length(), attempt + 1, maxRetries + 1,
                        rateLimiter.availablePermits());

                RestTemplate rt = proxyConfig.getCurrentRestTemplate();
                ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);

                return extractEmbedding(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long delay = computeRetryDelay(e, baseDelay, attempt);
                    log.warn("[Embedding] 限流(429)，第{}次重试，等待{}ms (Retry-After 优先)", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[Embedding] 向量生成失败: status={}, body={}",
                        e.getStatusCode(), truncate(e.getResponseBodyAsString(), 200));
                throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
            } catch (HttpServerErrorException e) {
                if (attempt < maxRetries) {
                    long delay = computeRetryDelay(e, baseDelay, attempt);
                    log.warn("[Embedding] 服务端错误({})，第{}次重试，等待{}ms",
                            e.getStatusCode().value(), attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[Embedding] 向量生成失败(5xx 重试耗尽): {}", e.getMessage());
                throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
            } catch (ResourceAccessException e) {
                // 包含 SocketTimeoutException / ConnectException 等 IO 类问题
                if (attempt < maxRetries) {
                    long delay = baseDelay * (1L << attempt);
                    log.warn("[Embedding] IO 异常({})，第{}次重试，等待{}ms",
                            e.getMostSpecificCause().getClass().getSimpleName(), attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[Embedding] 向量生成失败(IO 重试耗尽): {}", e.getMessage());
                throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("[Embedding] 向量生成失败: {}", e.getMessage());
                throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("向量生成失败: 超过最大重试次数 (" + maxRetries + ")");
    }

    /**
     * 检查服务是否可用（API Key 已配置）
     */
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    /**
     * 获取配置的向量维度
     */
    public int getEmbeddingDimension() {
        return config.getDimension();
    }

    /**
     * 当前可用令牌（监控/调试）
     */
    public int availablePermits() {
        return rateLimiter == null ? 0 : rateLimiter.availablePermits();
    }

    // ==================== 内部方法 ====================

    /**
     * 计算重试延迟：优先采用 Retry-After 响应头（秒或 HTTP-date 简单解析），否则指数退避。
     */
    private long computeRetryDelay(HttpClientErrorException e, long baseDelay, int attempt) {
        Long retryAfterMs = parseRetryAfter(e.getResponseHeaders());
        if (retryAfterMs != null && retryAfterMs > 0) {
            return retryAfterMs;
        }
        return baseDelay * (1L << attempt);
    }

    private long computeRetryDelay(HttpServerErrorException e, long baseDelay, int attempt) {
        Long retryAfterMs = parseRetryAfter(e.getResponseHeaders());
        if (retryAfterMs != null && retryAfterMs > 0) {
            return retryAfterMs;
        }
        return baseDelay * (1L << attempt);
    }

    private Long parseRetryAfter(HttpHeaders headers) {
        if (headers == null) return null;
        String v = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null || v.isBlank()) return null;
        try {
            long seconds = Long.parseLong(v.trim());
            return seconds * 1000L;
        } catch (NumberFormatException ignored) {
            // 忽略 HTTP-date 形式，回退指数退避
            return null;
        }
    }

    private float[] extractEmbedding(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && !data.isEmpty()) {
                JsonNode embedding = data.get(0).get("embedding");
                if (embedding != null && embedding.isArray()) {
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = (float) embedding.get(i).asDouble();
                    }
                    if (result.length != config.getDimension()) {
                        log.warn("[Embedding] 维度不匹配: 期望={}, 实际={}", config.getDimension(), result.length);
                    }
                    normalize(result);
                    return result;
                }
            }
            throw new RuntimeException("无法从响应中提取向量: " + truncate(response, 200));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析向量响应失败: " + e.getMessage(), e);
        }
    }

    private void normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
