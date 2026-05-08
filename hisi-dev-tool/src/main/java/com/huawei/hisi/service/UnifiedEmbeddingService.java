package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.EmbeddingModelConfig;
import com.huawei.hisi.config.ProxyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * 统一向量生成服务
 * 调用 OpenAI 兼容的 /embeddings 端点，配置来自 EmbeddingModelConfig。
 * 内置 429 限流指数退避重试 + L2 归一化 + 维度校验。
 *
 * 取代原来的 SiliconFlowEmbeddingService / IFlytekEmbeddingService / ZhipuService(embedding 部分)
 */
@Slf4j
@Service
public class UnifiedEmbeddingService {

    private final EmbeddingModelConfig config;
    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UnifiedEmbeddingService(EmbeddingModelConfig config, ProxyConfig proxyConfig) {
        this.config = config;
        this.proxyConfig = proxyConfig;
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

                log.debug("[Embedding] 调用向量生成API: url={}, model={}, textLen={}, attempt={}/{}",
                        url, config.getModel(), text.length(), attempt + 1, maxRetries + 1);

                RestTemplate rt = proxyConfig.getCurrentRestTemplate();
                ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);

                return extractEmbedding(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long delay = baseDelay * (1L << attempt);
                    log.warn("[Embedding] 限流(429)，第{}次重试，等待{}ms", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[Embedding] 向量生成失败: {}", e.getMessage());
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

    // ==================== 内部方法 ====================

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
