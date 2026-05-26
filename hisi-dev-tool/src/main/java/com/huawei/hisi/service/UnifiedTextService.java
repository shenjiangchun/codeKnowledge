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
     * 通用文本生成。
     *
     * <p>推理模型（GLM-4.7-flashX / DeepSeek-R1 / Qwen3 等）会在
     * {@code reasoning_content} 中输出思考链，再在 {@code content} 输出答案。
     * 若 {@code finish_reason=length}（token 预算耗尽），会自动以 2x max_tokens 重试一次。
     */
    public String generateText(String prompt) {
        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 阶段1：获取令牌
            long acquireStart = System.currentTimeMillis();
            try {
                if (!rateLimiter.tryAcquire(config.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                    throw new RuntimeException("[TextModel] 获取令牌超时（"
                            + config.getAcquireTimeoutSeconds() + "s），上游限流过严或负载过高");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[TextModel] 等待令牌被中断", ie);
            }
            long acquireMs = System.currentTimeMillis() - acquireStart;
            log.info("[TextModel] 令牌获取耗时={}ms, 当前可用令牌={}", acquireMs, rateLimiter.availablePermits());

            try {
                // 阶段2：构建请求 & 调用 API
                long apiStart = System.currentTimeMillis();
                String url = config.getBaseUrl() + "/chat/completions";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getModel());
                requestBody.put("temperature", config.getTemperature());
                requestBody.put("max_tokens", config.getMaxTokens());

                // 关闭推理模型的思考模式，避免 reasoning_content 消耗 token 预算
                // 导致 content 为空（GLM-4.5+ 默认强制开启思考模式）
                // 参见 https://docs.bigmodel.cn 核心参数 → thinking: {"type": "disabled"}
                ObjectNode thinkingNode = objectMapper.createObjectNode();
                thinkingNode.put("type", "disabled");
                requestBody.set("thinking", thinkingNode);

                ArrayNode messages = requestBody.putArray("messages");
                ObjectNode userMessage = messages.addObject();
                userMessage.put("role", "user");
                userMessage.put("content", prompt);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(
                        objectMapper.writeValueAsString(requestBody), headers);

                String requestJson = objectMapper.writeValueAsString(requestBody);
                log.info("[TextModel] 开始调用API: url={}, model={}, max_tokens={}, attempt={}/{}, permits={}",
                        url, config.getModel(), config.getMaxTokens(), attempt + 1, maxRetries + 1,
                        rateLimiter.availablePermits());
                log.info("[TextModel] 请求体: {}", truncate(requestJson, 500));

                RestTemplate rt = proxyConfig.getCurrentRestTemplate();
                ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);
                long apiMs = System.currentTimeMillis() - apiStart;
                log.info("[TextModel] API响应耗时={}ms, status={}, body={}", apiMs, response.getStatusCode(), truncate(response.getBody(), 300));

                String result = extractTextContent(response.getBody());

                // finish_reason=length → token 预算不够，推理模型思考链吃光了预算
                // 用 2x max_tokens 重试一次（不走外层重试循环，因为不是错误，只是截断）
                if (isFinishReasonLength(response.getBody()) && config.getMaxTokens() < 2048) {
                    log.warn("[TextModel] finish_reason=length, 以 max_tokens={} 重试一次", config.getMaxTokens() * 2);
                    result = retryWithMoreTokens(prompt, config.getMaxTokens() * 2);
                }

                return result;

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
                log.error("[TextModel] 服务端错误: status={}, body={}",
                        e.getStatusCode(), truncate(e.getResponseBodyAsString(), 500));
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
     * 检测响应中 finish_reason 是否为 length（token 预算耗尽被截断）。
     */
    private boolean isFinishReasonLength(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                String fr = choices.get(0).has("finish_reason")
                        ? choices.get(0).get("finish_reason").asText() : "";
                return "length".equals(fr);
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 以更大的 max_tokens 重试一次文本生成（用于推理模型思考链消耗预算的场景）。
     */
    private String retryWithMoreTokens(String prompt, int overrideMaxTokens) {
        try {
            if (!rateLimiter.tryAcquire(config.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                log.warn("[TextModel] 重试获取令牌超时，使用上一次的结果");
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }

        try {
            String url = config.getBaseUrl() + "/chat/completions";

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.getModel());
            requestBody.put("temperature", config.getTemperature());
            requestBody.put("max_tokens", overrideMaxTokens);

            // 关闭推理模型的思考模式
            ObjectNode thinkingNode = objectMapper.createObjectNode();
            thinkingNode.put("type", "disabled");
            requestBody.set("thinking", thinkingNode);

            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + config.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(requestBody), headers);

            log.info("[TextModel] 以 max_tokens={} 重试文本生成", overrideMaxTokens);

            RestTemplate rt = proxyConfig.getCurrentRestTemplate();
            ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("[TextModel] 重试文本生成成功: status={}, body={}", response.getStatusCode(), truncate(response.getBody(), 300));

            return extractTextContent(response.getBody());
        } catch (Exception e) {
            log.warn("[TextModel] 重试文本生成失败: {}, 使用上一次的结果", e.getMessage());
            return null;
        }
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

    /**
     * 从 chat/completions 响应提取文本内容。
     *
     * <p>兼容推理模型（DeepSeek-R1、Qwen3、GLM-4.7-flashX 等）：
     * 推理模型先在 {@code reasoning_content} 中输出思考链，再在 {@code content} 中输出答案。
     * 当 {@code finish_reason=length}（token 预算耗尽）时，{@code content} 可能为空
     * 而全部输出都在 {@code reasoning_content} 中 —— 此时取其最后一行作为兜底。
     *
     * @return 提取的文本内容，不超过 100 字符
     */
    private String extractTextContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("无法从响应中提取内容: " + truncate(response, 200));
            }

            JsonNode firstChoice = choices.get(0);
            String finishReason = firstChoice.has("finish_reason")
                    ? firstChoice.get("finish_reason").asText() : "unknown";

            JsonNode message = firstChoice.get("message");
            if (message == null) {
                throw new RuntimeException("响应中无 message 字段: " + truncate(response, 200));
            }

            // 1. 优先取 content
            String content = message.has("content") ? message.get("content").asText().trim() : "";

            // 2. content 为空但存在 reasoning_content（推理模型特有），
            //    说明 token 预算被思考链消耗殆尽，取思考链最后一段作为兜底描述
            if (content.isEmpty() && message.has("reasoning_content")) {
                String reasoning = message.get("reasoning_content").asText("").trim();
                if (!reasoning.isEmpty()) {
                    // 取最后一个换行段（通常是最终结论）
                    String lastParagraph = reasoning;
                    int lastNewline = reasoning.lastIndexOf('\n');
                    if (lastNewline >= 0 && lastNewline < reasoning.length() - 1) {
                        String tail = reasoning.substring(lastNewline + 1).trim();
                        if (!tail.isEmpty()) {
                            lastParagraph = tail;
                        }
                    }
                    log.warn("[TextModel] content为空(finish_reason={}), 从reasoning_content兜底提取: {}",
                            finishReason, truncate(lastParagraph, 80));
                    content = lastParagraph;
                }
            }

            // 3. 仍然为空，记录详细诊断
            if (content.isEmpty()) {
                log.error("[TextModel] 提取内容为空! finish_reason={}, message字段={}",
                        finishReason, message.toPrettyString().substring(0, Math.min(message.toPrettyString().length(), 300)));
                throw new RuntimeException("文本生成返回空内容(finish_reason=" + finishReason
                        + ")，可能是 max_tokens(" + config.getMaxTokens() + ") 过小，"
                        + "推理模型思考链消耗了全部 token 预算。建议增大 max_tokens 或切换非推理模型。");
            }

            // 4. finish_reason=length 警告（输出被截断，质量可能下降）
            if ("length".equals(finishReason)) {
                log.warn("[TextModel] finish_reason=length, 输出被截断(当前max_tokens={}), 考虑增大配置",
                        config.getMaxTokens());
            }

            if (content.length() > 100) {
                content = content.substring(0, 100);
            }
            return content;

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
