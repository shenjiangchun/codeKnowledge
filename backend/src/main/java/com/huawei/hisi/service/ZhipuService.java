package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.ZhipuConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 智谱AI服务（已废弃）
 * 功能已迁移至 UnifiedTextService + UnifiedEmbeddingService。
 * 仅在 zhipu.enabled=true 时加载（默认 false），保留向后兼容。
 *
 * @deprecated 使用 {@link UnifiedTextService} 和 {@link UnifiedEmbeddingService} 替代
 */
@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zhipu.enabled", havingValue = "true")
public class ZhipuService {

    private final ZhipuConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成代码描述
     *
     * @param className  类名
     * @param methodName 方法名
     * @param signature   方法签名
     * @param comment     方法注释
     * @return 生成的中文描述
     */
    public String generateDescription(String className, String methodName, String signature, String comment) {
        if (!config.isEnabled()) {
            throw new RuntimeException("智谱AI服务已禁用");
        }

        String prompt = buildCodeDescriptionPrompt(className, methodName, signature, comment);
        return generateText(prompt);
    }

    /**
     * 生成文本
     *
     * @param prompt 提示词
     * @return 生成的文本
     */
    public String generateText(String prompt) {
        if (!config.isEnabled()) {
            throw new RuntimeException("智谱AI服务已禁用");
        }

        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String url = config.getBaseUrl() + "/chat/completions";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getTextModel());
                requestBody.put("temperature", config.getTemperature());
                requestBody.put("max_tokens", config.getMaxTokens());

                ArrayNode messages = requestBody.putArray("messages");
                ObjectNode userMessage = messages.addObject();
                userMessage.put("role", "user");
                userMessage.put("content", prompt);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

                log.debug("[智谱AI] 调用文本生成API: model={}, attempt={}/{}", config.getTextModel(), attempt + 1, maxRetries + 1);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.POST, entity, String.class
                );

                return extractTextContent(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long delay = baseDelay * (1L << attempt);
                    log.warn("[智谱AI] 文本生成限流(429)，第{}次重试，等待{}ms", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[智谱AI] 文本生成失败: {}", e.getMessage());
                throw new RuntimeException("智谱AI文本生成失败: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("[智谱AI] 文本生成失败: {}", e.getMessage());
                throw new RuntimeException("智谱AI文本生成失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("智谱AI文本生成失败: 超过最大重试次数");
    }

    /**
     * 生成向量（单个文本）
     *
     * @param text 输入文本
     * @return 向量数组
     */
    public float[] generateEmbedding(String text) {
        if (!config.isEnabled()) {
            throw new RuntimeException("智谱AI服务已禁用");
        }

        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文本不能为空");
        }

        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String url = config.getBaseUrl() + "/embeddings";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getEmbeddingModel());
                requestBody.put("input", text);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

                log.debug("[智谱AI] 调用向量生成API: model={}, textLength={}, attempt={}/{}",
                        config.getEmbeddingModel(), text.length(), attempt + 1, maxRetries + 1);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.POST, entity, String.class
                );

                return extractEmbedding(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long delay = baseDelay * (1L << attempt);
                    log.warn("[智谱AI] 向量生成限流(429)，第{}次重试，等待{}ms", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[智谱AI] 向量生成失败: {}", e.getMessage());
                throw new RuntimeException("智谱AI向量生成失败: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("[智谱AI] 向量生成失败: {}", e.getMessage());
                throw new RuntimeException("智谱AI向量生成失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("智谱AI向量生成失败: 超过最大重试次数");
    }

    /**
     * 批量生成向量
     *
     * @param texts 输入文本列表
     * @return 向量列表
     */
    public List<float[]> batchGenerateEmbeddings(List<String> texts) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(generateEmbedding(text));
        }
        return results;
    }

    /**
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        return config.isEnabled() && config.getApiKey() != null && !config.getApiKey().isEmpty();
    }

    /**
     * 检查向量生成服务是否可用
     */
    public boolean isEmbeddingAvailable() {
        return config.isEnabled() && config.isUseForEmbedding() &&
               config.getApiKey() != null && !config.getApiKey().isEmpty();
    }

    /**
     * 获取向量维度
     */
    public int getEmbeddingDimension() {
        return config.getEmbeddingDimension();
    }

    /**
     * 构建代码描述生成的提示词
     */
    private String buildCodeDescriptionPrompt(String className, String methodName, String signature, String comment) {
        String commentStr = (comment == null || comment.isEmpty()) ? "无" : comment;
        return String.format("""
            你是专业的Java代码语义解析专家，请用简洁精准的中文描述下面Java方法的核心功能、业务意图，不超过50字，不要输出代码。

            类名：%s
            方法名：%s
            签名：%s
            注释：%s

            请直接输出描述，不要有任何额外内容。
            """, className, methodName, signature, commentStr);
    }

    /**
     * 从响应中提取文本内容
     */
    private String extractTextContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);

            // OpenAI兼容格式
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null && message.has("content")) {
                    String content = message.get("content").asText().trim();
                    // 限制长度
                    if (content.length() > 100) {
                        content = content.substring(0, 100);
                    }
                    return content;
                }
            }

            throw new RuntimeException("无法从响应中提取内容: " + response);

        } catch (Exception e) {
            throw new RuntimeException("解析响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从响应中提取向量
     */
    private float[] extractEmbedding(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);

            // OpenAI兼容格式
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && data.size() > 0) {
                JsonNode firstItem = data.get(0);
                JsonNode embedding = firstItem.get("embedding");
                if (embedding != null && embedding.isArray()) {
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = (float) embedding.get(i).asDouble();
                    }

                    // 验证维度
                    if (result.length != config.getEmbeddingDimension()) {
                        log.warn("[智谱AI] 向量维度不匹配: 期望={}, 实际={}",
                                config.getEmbeddingDimension(), result.length);
                    }

                    // 归一化
                    normalize(result);

                    return result;
                }
            }

            throw new RuntimeException("无法从响应中提取向量: " + response);

        } catch (Exception e) {
            throw new RuntimeException("解析向量响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向量归一化
     */
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

    /**
     * 计算余弦相似度
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("向量必须非空且长度相等");
        }

        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom == 0 ? 0 : dot / denom;
    }
}
