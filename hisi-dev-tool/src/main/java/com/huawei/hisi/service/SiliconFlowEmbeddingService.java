package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.SiliconFlowConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * SiliconFlow Embedding 服务（已废弃）
 * 功能已迁移至 UnifiedEmbeddingService。
 * 仅在 siliconflow.enabled=true 时加载（默认 false），保留向后兼容。
 *
 * @deprecated 使用 {@link UnifiedEmbeddingService} 替代
 */
@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "siliconflow.enabled", havingValue = "true")
public class SiliconFlowEmbeddingService {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 60_000;

    private final SiliconFlowConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文本不能为空");
        }

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String url = config.getBaseUrl() + "/embeddings";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getEmbeddingModel());
                requestBody.put("input", text);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

                log.debug("[SiliconFlow] 调用向量生成API: model={}, textLength={}, attempt={}/{}",
                        config.getEmbeddingModel(), text.length(), attempt + 1, MAX_RETRIES + 1);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.POST, entity, String.class
                );

                return extractEmbedding(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                    log.warn("[SiliconFlow] 向量生成限流(429)，第{}次重试，等待{}ms", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[SiliconFlow] 向量生成失败: {}", e.getMessage());
                throw new RuntimeException("SiliconFlow向量生成失败: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("[SiliconFlow] 向量生成失败: {}", e.getMessage());
                throw new RuntimeException("SiliconFlow向量生成失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("SiliconFlow向量生成失败: 超过最大重试次数");
    }

    public boolean isAvailable() {
        return config.isEnabled() && config.getApiKey() != null && !config.getApiKey().isEmpty();
    }

    public int getEmbeddingDimension() {
        return config.getEmbeddingDimension();
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
                    if (result.length != config.getEmbeddingDimension()) {
                        log.warn("[SiliconFlow] 向量维度不匹配: 期望={}, 实际={}",
                                config.getEmbeddingDimension(), result.length);
                    }
                    normalize(result);
                    return result;
                }
            }
            throw new RuntimeException("无法从响应中提取向量: " + response);
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
}
