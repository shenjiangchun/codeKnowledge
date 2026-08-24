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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                // GLM-4.5+: thinking: {"type": "disabled"}
                // Qwen3+: chat_template_kwargs: {"enable_thinking": false}
                disableThinking(requestBody, config.getModel());

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
            disableThinking(requestBody, config.getModel());

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
     * 通用 Chat 调用 — 支持独立的 system / user prompt，返回完整响应文本（不截断）。
     *
     * <p>适用于需要自定义 system prompt 且返回内容较长的场景（如查询分解），
     * 区别于 {@link #generateText(String)} 的 100 字截断和单 prompt 设计。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入
     * @param maxTokens    本次调用最大 token 数（覆盖默认配置）
     * @return 完整的响应文本
     */
    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (!rateLimiter.tryAcquire(config.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                    throw new RuntimeException("[TextModel] chat: 获取令牌超时（"
                            + config.getAcquireTimeoutSeconds() + "s）");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[TextModel] chat: 等待令牌被中断", ie);
            }

            try {
                String url = config.getBaseUrl() + "/chat/completions";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getModel());
                requestBody.put("temperature", config.getTemperature());
                requestBody.put("max_tokens", maxTokens);

                // 关闭推理模型的思考模式
                disableThinking(requestBody, config.getModel());

                ArrayNode messages = requestBody.putArray("messages");
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
                ObjectNode userMsg = messages.addObject();
                userMsg.put("role", "user");
                userMsg.put("content", userPrompt);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(
                        objectMapper.writeValueAsString(requestBody), headers);

                log.info("[TextModel] chat: model={}, max_tokens={}, attempt={}/{}",
                        config.getModel(), maxTokens, attempt + 1, maxRetries + 1);

                RestTemplate rt = proxyConfig.getCurrentRestTemplate();
                ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);

                return extractFullTextContent(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long delay = computeRetryDelay(e.getResponseHeaders(), baseDelay, attempt);
                    log.warn("[TextModel] chat: 限流(429)，第{}次重试，等待{}ms", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                throw new RuntimeException("chat 调用失败: " + e.getMessage(), e);
            } catch (HttpServerErrorException e) {
                if (attempt < maxRetries) {
                    sleepQuietly(baseDelay * (1L << attempt));
                    continue;
                }
                throw new RuntimeException("chat 调用失败: " + e.getMessage(), e);
            } catch (ResourceAccessException e) {
                if (attempt < maxRetries) {
                    sleepQuietly(baseDelay * (1L << attempt));
                    continue;
                }
                throw new RuntimeException("chat 调用失败(IO): " + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("chat 调用失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("chat 调用失败: 超过最大重试次数");
    }

    /**
     * 从 chat/completions 响应提取完整文本内容（不截断）。
     * 供 {@link #chat} 使用，区别于 {@link #extractTextContent} 的 100 字截断。
     */
    private String extractFullTextContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("chat: 无法从响应提取内容");
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null) {
                throw new RuntimeException("chat: 响应中无 message 字段");
            }
            String content = message.has("content") ? message.get("content").asText().trim() : "";
            // 兜底: 推理模型思考链消耗预算时取 reasoning_content
            if (content.isEmpty() && message.has("reasoning_content")) {
                String reasoning = message.get("reasoning_content").asText("").trim();
                if (!reasoning.isEmpty()) {
                    int lastNl = reasoning.lastIndexOf('\n');
                    content = (lastNl >= 0 && lastNl < reasoning.length() - 1)
                            ? reasoning.substring(lastNl + 1).trim()
                            : reasoning;
                }
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("chat: 解析响应失败: " + e.getMessage(), e);
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

    /**
     * 多模态文本生成 — 支持图片输入
     *
     * <p>图片格式遵循 OpenAI Vision API：
     * {@code [{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}]}}
     *
     * @param textPrompt 文本提示词（不能为空）
     * @param images     图片内容数组（OpenAI vision 格式）
     * @param maxTokens  最大 token 数
     * @return 生成的文本内容（不截断）
     */
    public String generateWithImages(String textPrompt, java.util.List<java.util.Map<String, Object>> images, int maxTokens) {
        // 参数校验
        if (textPrompt == null || textPrompt.isBlank()) {
            throw new IllegalArgumentException("textPrompt is required");
        }
        if (images == null || images.isEmpty()) {
            return chat("", textPrompt, maxTokens);
        }

        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (!rateLimiter.tryAcquire(config.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                    throw new RuntimeException("[TextModel] generateWithImages: 获取令牌超时");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[TextModel] generateWithImages: 等待令牌被中断", ie);
            }

            try {
                String url = config.getBaseUrl() + "/chat/completions";

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getVisionModel() != null ? config.getVisionModel() : config.getModel());
                requestBody.put("temperature", config.getTemperature());
                requestBody.put("max_tokens", maxTokens);

                ArrayNode messages = requestBody.putArray("messages");
                ObjectNode userMsg = messages.addObject();
                userMsg.put("role", "user");

                // 构建多模态 content 数组：文本 + 图片
                ArrayNode contentArray = userMsg.putArray("content");
                ObjectNode textPart = contentArray.addObject();
                textPart.put("type", "text");
                textPart.put("text", textPrompt);

                // 添加图片
                for (java.util.Map<String, Object> img : images) {
                    Object urlObj = img.get("url");
                    if (!(urlObj instanceof String imageUrl)) {
                        log.warn("[TextModel] generateWithImages: invalid image url format, skipping");
                        continue;
                    }
                    ObjectNode imgPart = contentArray.addObject();
                    imgPart.put("type", "image_url");
                    ObjectNode imgUrl = imgPart.putObject("image_url");
                    imgUrl.put("url", imageUrl);
                }

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(
                        objectMapper.writeValueAsString(requestBody), headers);

                log.info("[TextModel] generateWithImages: model={}, images={}, max_tokens={}, attempt={}/{}",
                        config.getVisionModel() != null ? config.getVisionModel() : config.getModel(),
                        images.size(), maxTokens, attempt + 1, maxRetries + 1);

                RestTemplate rt = proxyConfig.getCurrentRestTemplate();
                ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);

                return extractFullTextContent(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    long delay = computeRetryDelay(e.getResponseHeaders(), baseDelay, attempt);
                    log.warn("[TextModel] generateWithImages: 限流(429)，第{}次重试，等待{}ms", attempt + 1, delay);
                    sleepQuietly(delay);
                    continue;
                }
                throw new RuntimeException("generateWithImages 调用失败: " + e.getMessage(), e);
            } catch (HttpServerErrorException e) {
                if (attempt < maxRetries) {
                    sleepQuietly(baseDelay * (1L << attempt));
                    continue;
                }
                throw new RuntimeException("generateWithImages 调用失败: " + e.getMessage(), e);
            } catch (ResourceAccessException e) {
                if (attempt < maxRetries) {
                    sleepQuietly(baseDelay * (1L << attempt));
                    continue;
                }
                throw new RuntimeException("generateWithImages 调用失败(IO): " + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("generateWithImages 调用失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("generateWithImages 调用失败: 超过最大重试次数");
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

    /**
     * 根据模型类型关闭思考模式，避免 reasoning 消耗 token 预算导致 content 为空。
     * GLM-4.5+: thinking: {"type": "disabled"}
     * Qwen3+: chat_template_kwargs: {"enable_thinking": false}
     */
    private void disableThinking(ObjectNode requestBody, String model) {
        if (model != null && model.toLowerCase().contains("qwen")) {
            ObjectNode kwargs = objectMapper.createObjectNode();
            kwargs.put("enable_thinking", false);
            requestBody.set("chat_template_kwargs", kwargs);
        } else {
            ObjectNode thinkingNode = objectMapper.createObjectNode();
            thinkingNode.put("type", "disabled");
            requestBody.set("thinking", thinkingNode);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== 批量描述生成 ====================

    /** 每个方法描述约需的输出 token 数（50 中文字符 ≈ 25-30 tokens） */
    private static final int OUTPUT_TOKENS_PER_DESC = 30;

    /**
     * 批量生成方法描述。
     *
     * @param methods 方法信息列表（className, methodName, signature, comment, methodBody）
     * @param glossary 术语表片段（可为空字符串）
     * @return 与输入顺序一致的描述列表
     */
    public List<String> generateDescriptionsBatch(List<Map<String, String>> methods, String glossary) {
        if (methods == null || methods.isEmpty()) {
            return List.of();
        }
        int count = methods.size();
        if (count == 1) {
            return List.of(generateDescriptionWithBody(
                    methods.get(0).get("className"),
                    methods.get(0).get("methodName"),
                    methods.get(0).get("signature"),
                    methods.get(0).get("methodBody")));
        }

        // token 预检 → 按输出 token 估算（每方法 ~30 输出 tokens）
        int outputTokensNeeded = count * OUTPUT_TOKENS_PER_DESC;
        int batchMax = config.getBatchMaxTokens() > 0
                ? config.getBatchMaxTokens()
                : Math.max(config.getMaxTokens(), 2048);  // 批量默认至少 2048
        int maxTokenBudget = (int) (batchMax * 0.7);
        if (outputTokensNeeded > maxTokenBudget && count > 5) {
            int safeCount = Math.max(5, maxTokenBudget * count / outputTokensNeeded);
            log.info("[BATCH-LLM] token 预检超限 (need={}, budget={}), batch size {} → {}",
                    outputTokensNeeded, maxTokenBudget, count, safeCount);
            List<String> results = new ArrayList<>(methods.stream()
                    .limit(safeCount)
                    .map(m -> generateDescriptionWithBody(
                            m.get("className"), m.get("methodName"),
                            m.get("signature"), m.get("methodBody")))
                    .toList());
            // 递归处理剩余
            results.addAll(generateDescriptionsBatch(methods.subList(safeCount, count), glossary));
            return results;
        }

        String prompt = buildBatchPrompt(methods, glossary);
        String strategy = config.getJsonOutputStrategy();
        long batchStart = System.currentTimeMillis();

        // 尝试①: json_object 模式
        if (!"prompt-only".equals(strategy)) {
            try {
                String response = generateBatchText(prompt, true);
                List<String> results = extractDescriptions(response, count);
                if (results != null) {
                    logPerf(count, batchStart, "batch(json)");
                    return results;
                }
                log.warn("[BATCH-LLM] json_mode 解析失败, resp前500字={}", truncate(response, 500));
            } catch (Exception e) {
                log.warn("[BATCH-LLM] json_mode 调用失败: {}", e.getMessage());
            }
        }

        // 尝试②: prompt-only 模式（不设 response_format，纯 prompt 工程）
        try {
            String response = generateBatchText(prompt, false);
            // prompt-only 可能输出 markdown fence → 兼容裸数组 和 {"descriptions": [...]}
            List<String> results = extractDescriptions(stripMarkdownFence(response), count);
            if (results != null) {
                logPerf(count, batchStart, "batch(prompt-only)");
                return results;
            }
            log.warn("[BATCH-LLM] prompt-only 解析失败, resp前500字={}", truncate(response, 500));
        } catch (Exception e) {
            log.warn("[BATCH-LLM] prompt-only 调用失败: {}", e.getMessage());
        }

        // 降级单条
        long singleStart = System.currentTimeMillis();
        List<String> singleResults = fallbackToSingle(methods);
        logPerf(count, batchStart, String.format("fallback-single(%d calls, %dms)", count,
                System.currentTimeMillis() - singleStart));
        return singleResults;
    }

    private List<String> fallbackToSingle(List<Map<String, String>> methods) {
        List<String> results = new ArrayList<>(methods.size());
        for (Map<String, String> m : methods) {
            results.add(generateDescriptionWithBody(
                    m.get("className"), m.get("methodName"),
                    m.get("signature"), m.get("methodBody")));
        }
        return results;
    }

    private String generateBatchText(String prompt, boolean useJsonMode) {
        int maxRetries = config.getMaxRetries();
        long baseDelay = config.getRetryBaseDelayMs();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (!rateLimiter.tryAcquire(config.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                    throw new RuntimeException("[BATCH-LLM] 获取令牌超时");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[BATCH-LLM] 等待令牌被中断", ie);
            }

            try {
                String url = config.getBaseUrl() + "/chat/completions";
                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", config.getModel());
                requestBody.put("temperature", 0.0);
                int effectiveMaxTokens = config.getBatchMaxTokens() > 0
                        ? config.getBatchMaxTokens()
                        : Math.max(config.getMaxTokens(), 2048);
                requestBody.put("max_tokens", effectiveMaxTokens);

                disableThinking(requestBody, config.getModel());

                // json_object 模式
                if (useJsonMode) {
                    ObjectNode rf = requestBody.putObject("response_format");
                    rf.put("type", "json_object");
                }

                ArrayNode messages = requestBody.putArray("messages");
                ObjectNode userMsg = messages.addObject();
                userMsg.put("role", "user");
                userMsg.put("content", prompt);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + config.getApiKey());

                HttpEntity<String> entity = new HttpEntity<>(
                        objectMapper.writeValueAsString(requestBody), headers);

                log.info("[BATCH-LLM] 批量调用: count={}, attempt={}/{}, jsonMode={}",
                        prompt.length(), attempt + 1, maxRetries + 1, useJsonMode);

                RestTemplate rt = proxyConfig.getCurrentRestTemplate();
                ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);

                return extractFullTextContent(response.getBody());

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    // 如果 prompt 超长，先切半再重试，而不是原样重试
                    String splitMarker = "\n---\n";
                    int mid = prompt.length() / 2;
                    if (prompt.length() > 2000 && prompt.contains(splitMarker)) {
                        int cut = prompt.lastIndexOf(splitMarker, mid);
                        if (cut > 0 && prompt.indexOf(splitMarker, cut + 1) > 0) {
                            String first = prompt.substring(0, cut);
                            String secondStart = prompt.substring(cut).trim();
                            String second = secondStart.startsWith("---\n")
                                    ? "你是专业的代码语义解析专家。\n" + secondStart
                                    : secondStart;
                            log.warn("[BATCH-LLM] 限流(429)，切半重试: len={} → {} + {}",
                                    prompt.length(), first.length(), second.length());
                            try {
                                String r1 = generateBatchText(first, useJsonMode);
                                String r2 = generateBatchText(second, useJsonMode);
                                return mergeBatchResults(r1, r2);
                            } catch (RuntimeException re) {
                                log.error("[BATCH-LLM] 切半重试失败: {}", re.getMessage());
                            }
                        }
                    }
                    long delay = computeRetryDelay(e.getResponseHeaders(), baseDelay, attempt);
                    log.warn("[BATCH-LLM] 限流(429)，重试等待{}ms", delay);
                    sleepQuietly(delay);
                    continue;
                }
                throw new RuntimeException("批量文本生成失败: " + e.getMessage(), e);
            } catch (HttpServerErrorException e) {
                if (attempt < maxRetries) {
                    sleepQuietly(baseDelay * (1L << attempt));
                    continue;
                }
                throw new RuntimeException("批量文本生成失败: " + e.getMessage(), e);
            } catch (ResourceAccessException e) {
                if (attempt < maxRetries) {
                    sleepQuietly(baseDelay * (1L << attempt));
                    continue;
                }
                throw new RuntimeException("批量文本生成失败(IO): " + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("批量文本生成失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("批量文本生成失败: 超过最大重试次数");
    }

    /**
     * 合并两个 JSON 批处理结果中的 descriptions 数组。
     */
    private String mergeBatchResults(String r1, String r2) {
        try {
            JsonNode n1 = objectMapper.readTree(r1);
            JsonNode n2 = objectMapper.readTree(r2);
            var arr1 = n1.has("descriptions") ? n1.get("descriptions") : n1;
            var arr2 = n2.has("descriptions") ? n2.get("descriptions") : n2;
            ArrayNode merged = objectMapper.createArrayNode();
            if (arr1.isArray()) arr1.forEach(merged::add);
            if (arr2.isArray()) arr2.forEach(merged::add);
            ObjectNode result = objectMapper.createObjectNode();
            result.set("descriptions", merged);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[BATCH-LLM] merge 失败: {}", e.getMessage());
            return r1; // 至少返回第一部分
        }
    }

    /**
     * 构建批量 prompt 模板。
     * 编号格式 [0] [1] ... + 强约束禁止错位 + 1 个示例。
     */
    static String buildBatchPrompt(List<Map<String, String>> methods, String glossary) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("你是专业的代码语义解析专家。请为以下方法列表中的每个方法生成一句中文描述（50字以内）。\n\n");
        sb.append("## 规则\n");
        sb.append("1. 分析方法体实际行为，以方法体为准（注释可能过时）\n");
        sb.append("2. 不要输出代码或实现细节\n");
        if (glossary != null && !glossary.isBlank()) {
            sb.append(glossary).append("\n");
        }
        sb.append("\n## 方法列表（共 ").append(methods.size()).append(" 个）\n");

        for (int i = 0; i < methods.size(); i++) {
            Map<String, String> m = methods.get(i);
            sb.append("---\n");
            sb.append("[").append(i).append("] 类名：").append(m.get("className")).append("\n");
            sb.append("    方法名：").append(m.get("methodName")).append("\n");
            sb.append("    签名：").append(m.getOrDefault("signature", "")).append("\n");
            sb.append("    注释：").append(m.getOrDefault("comment", "无")).append("\n");
            String body = m.getOrDefault("methodBody", "无");
            sb.append("    方法体：\n    ").append(body.replace("\n", "\n    ")).append("\n");
        }

        sb.append("\n## 输出格式（严格遵守，违反将导致解析失败）\n");
        sb.append("返回一个 JSON 对象：{\"descriptions\": [\"描述0\", \"描述1\", ...]}\n");
        sb.append("\"descriptions\" 数组长度必须 = ").append(methods.size()).append("。\n");
        sb.append("数组的第 i 个元素必须是编号 [i] 的方法的描述。\n");
        sb.append("禁止跳位、错位、多输出或少输出。\n");
        sb.append("禁止输出 JSON 以外的任何内容（禁止 markdown fence、禁止解释文字）。\n\n");
        sb.append("示例（count=").append(Math.min(methods.size(), 3)).append("）：\n");
        sb.append("{\"descriptions\": [\"描述0\", \"描述1\", \"描述2\"]}");

        return sb.toString();
    }

    /**
     * 从 LLM 响应中提取描述列表。
     * 优先从 {"descriptions": [...]} 中提取，兼容旧格式的裸 JSON 数组。
     */
    private static List<String> extractDescriptions(String response, int expectedCount) {
        try {
            JsonNode root = new ObjectMapper().readTree(response);
            if (root.has("descriptions") && root.get("descriptions").isArray()) {
                JsonNode arr = root.get("descriptions");
                if (arr.size() == expectedCount) {
                    List<String> results = new ArrayList<>(expectedCount);
                    for (JsonNode n : arr) {
                        results.add(n.asText());
                    }
                    return results;
                }
            }
            if (root.isArray() && root.size() == expectedCount) {
                List<String> results = new ArrayList<>(expectedCount);
                for (JsonNode n : root) {
                    results.add(n.asText());
                }
                return results;
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    /** 去除 markdown ```json fence */
    private static String stripMarkdownFence(String response) {
        if (response == null) return "";
        String s = response.trim();
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (start >= 0 && end > start) {
                return s.substring(start + 1, end).trim();
            }
        }
        return response;
    }

    /** 记录批量 vs 单条的耗时对比 */
    private void logPerf(int methodCount, long batchStartMs, String strategy) {
        long elapsed = System.currentTimeMillis() - batchStartMs;
        long perMethod = elapsed / methodCount;
        log.info("[BATCH-PERF] strategy={} methods={} elapsed={}ms perMethod={}ms",
                strategy, methodCount, elapsed, perMethod);
    }
}
