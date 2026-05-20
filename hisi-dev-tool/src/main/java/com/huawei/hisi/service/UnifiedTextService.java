package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.config.TextModelConfig;
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
 * 统一自然语言生成服务
 * 调用 OpenAI 兼容的 /chat/completions 端点，配置来自 TextModelConfig。
 *
 * <p>并发控制：令牌桶 ({@link TokenBucketRateLimiter}) 主动限流 +
 * 429 / Retry-After / 偶发 IO 异常的指数退避重试。
 *
 * 取代原来 ZhipuService 的 generateText / generateDescription 功能。
 */
@Slf4j
@Service
public class UnifiedTextService {

    private final TextModelConfig config;
    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TokenBucketRateLimiter rateLimiter;

    public UnifiedTextService(TextModelConfig config, ProxyConfig proxyConfig) {
        this.config = config;
        this.proxyConfig = proxyConfig;
    }

    @PostConstruct
    public void init() {
        this.rateLimiter = new TokenBucketRateLimiter(
                "text-model", config.getQps(), config.getBurst());
    }

    @PreDestroy
    public void destroy() {
        if (rateLimiter != null) {
            rateLimiter.shutdown();
        }
    }

    /**
     * 生成代码描述
     */
    public String generateDescription(String className, String methodName, String signature, String comment) {
        String prompt = buildCodeDescriptionPrompt(className, methodName, signature, comment);
        return generateText(prompt);
    }

    /**
     * 基于方法体内容生成详细描述
     */
    public String generateDescriptionWithBody(String className, String methodName, String signature, String methodBody) {
        String truncatedBody = methodBody != null && methodBody.length() > 2000
                ? methodBody.substring(0, 2000) + "\n// ... (truncated)"
                : methodBody;

        String prompt = String.format("""
                你是专业的Java代码语义解析专家，请用简洁精准的中文描述下面Java方法的核心功能、业务意图，不超过80字，不要输出代码。

                类名：%s
                方法名：%s
                签名：%s
                方法体：
                %s

                请直接输出描述，不要有任何额外内容。
                """, className, methodName, signature, truncatedBody != null ? truncatedBody : "无");
        return generateText(prompt);
    }

    /**
     * 通用文本生成
     */
    public String generateText(String prompt) {
        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (!rateLimiter.tryAcquire(config.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                    throw new RuntimeException("[TextModel] 获取令牌超时（"
                            + config.getAcquireTimeoutSeconds() + "s），上游限流过严或负载过高");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[TextModel] 等待令牌被中断", ie);
            }

            try {
                String url = config.getBaseUrl() + "/chat/completions";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getModel());
                requestBody.put("temperature", config.getTemperature());
                requestBody.put("max_tokens", config.getMaxTokens());

                ArrayNode messages = requestBody.putArray("messages");
                ObjectNode userMessage = messages.addObject();
                userMessage.put("role", "user");
                userMessage.put("content", prompt);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(
                        objectMapper.writeValueAsString(requestBody), headers);

                log.debug("[TextModel] 调用文本生成API: url={}, model={}, attempt={}/{}, permits={}",
                        url, config.getModel(), attempt + 1, maxRetries + 1,
                        rateLimiter.availablePermits());

                RestTemplate rt = proxyConfig.getCurrentRestTemplate();
                ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);

                return extractTextContent(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long delay = computeRetryDelay(e.getResponseHeaders(), baseDelay, attempt);
                    log.warn("[TextModel] 限流(429)，第{}次重试，等待{}ms (Retry-After 优先)", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[TextModel] 文本生成失败: status={}, body={}",
                        e.getStatusCode(), truncate(e.getResponseBodyAsString(), 200));
                throw new RuntimeException("文本生成失败: " + e.getMessage(), e);
            } catch (HttpServerErrorException e) {
                if (attempt < maxRetries) {
                    long delay = computeRetryDelay(e.getResponseHeaders(), baseDelay, attempt);
                    log.warn("[TextModel] 服务端错误({})，第{}次重试，等待{}ms",
                            e.getStatusCode().value(), attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[TextModel] 文本生成失败(5xx 重试耗尽): {}", e.getMessage());
                throw new RuntimeException("文本生成失败: " + e.getMessage(), e);
            } catch (ResourceAccessException e) {
                if (attempt < maxRetries) {
                    long delay = baseDelay * (1L << attempt);
                    log.warn("[TextModel] IO 异常({})，第{}次重试，等待{}ms",
                            e.getMostSpecificCause().getClass().getSimpleName(), attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                log.error("[TextModel] 文本生成失败(IO 重试耗尽): {}", e.getMessage());
                throw new RuntimeException("文本生成失败: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("[TextModel] 文本生成失败: {}", e.getMessage());
                throw new RuntimeException("文本生成失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("文本生成失败: 超过最大重试次数 (" + maxRetries + ")");
    }

    /**
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    public int availablePermits() {
        return rateLimiter == null ? 0 : rateLimiter.availablePermits();
    }

    // ==================== 内部方法 ====================

    private long computeRetryDelay(HttpHeaders headers, long baseDelay, int attempt) {
        if (headers != null) {
            String v = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (v != null && !v.isBlank()) {
                try {
                    return Long.parseLong(v.trim()) * 1000L;
                } catch (NumberFormatException ignored) {
                    // 回退指数退避
                }
            }
        }
        return baseDelay * (1L << attempt);
    }

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

    private String extractTextContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    String content = message.get("content").asText().trim();
                    if (content.length() > 100) {
                        content = content.substring(0, 100);
                    }
                    return content;
                }
            }
            throw new RuntimeException("无法从响应中提取内容: " + truncate(response, 200));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析响应失败: " + e.getMessage(), e);
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
