package com.huawei.hisi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.config.TextModelConfig;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.service.SessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通用对话 Controller — 提供 SSE 流式 AI 聊天端点。
 * <p>
 * 前端 `src/api/claude.ts` 的 `universalChat()` 调用此端点。
 * 底层调用 TextModelConfig 配置的 OpenAI 兼容模型 (streaming mode)。
 * <p>
 * 支持多场景 prompt 模板：APM_DIAGNOSIS, call-chain-analysis, log-analysis 等。
 */
@Slf4j
@RestController
@RequestMapping("/api/claude")
@RequiredArgsConstructor
public class ClaudeChatController {

    private final TextModelConfig textModelConfig;
    private final ProxyConfig proxyConfig;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-claude-chat");
        t.setDaemon(true);
        return t;
    });

    /**
     * 通用对话请求 DTO
     */
    @Data
    public static class UniversalChatRequest {
        private String sessionId;
        private String prompt;
        private String scene;
        private Map<String, Object> metadata;
        private String workingDirectory;
    }

    /**
     * 通用 AI 对话 — SSE 流式响应
     * <p>
     * POST /api/claude/universal-chat
     * <p>
     * Events:
     * - event:session / data:{sessionId}
     * - data:{content chunk}
     * - event:done / data:completed
     * - event:error / data:{error message}
     */
    @PostMapping(value = "/universal-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter universalChat(@RequestBody UniversalChatRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        log.info("[Claude Chat] scene={}, sessionId={}, promptLen={}",
                request.getScene(), sessionId, request.getPrompt() != null ? request.getPrompt().length() : 0);

        SseEmitter emitter = new SseEmitter(300_000L); // 5 minutes timeout

        String finalSessionId = sessionId;
        sseExecutor.execute(() -> streamChat(emitter, finalSessionId, request));

        emitter.onTimeout(() -> log.warn("[Claude Chat] SSE timeout: sessionId={}", finalSessionId));
        emitter.onCompletion(() -> log.debug("[Claude Chat] SSE complete: sessionId={}", finalSessionId));

        return emitter;
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ApiResponse<Boolean> health() {
        boolean configured = textModelConfig.getApiKey() != null && !textModelConfig.getApiKey().isBlank();
        return ApiResponse.success(configured);
    }

    private void streamChat(SseEmitter emitter, String sessionId, UniversalChatRequest request) {
        try {
            // 1. Send session event
            emitter.send(SseEmitter.event().name("session").data(sessionId));

            // 2. Build system prompt based on scene
            String systemPrompt = buildSystemPrompt(request.getScene(), request.getMetadata());

            // 3. Call model with streaming
            String url = textModelConfig.getBaseUrl() + "/chat/completions";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", textModelConfig.getModel());
            body.put("temperature", 0.7);
            body.put("max_tokens", 2048);
            body.put("stream", true);

            ArrayNode messages = body.putArray("messages");

            // System message
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);

            // User message
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", request.getPrompt());

            // Create connection (respecting proxy config)
            HttpURLConnection conn = createConnection(url);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + textModelConfig.getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(textModelConfig.getTimeout());
            conn.setReadTimeout(120_000); // 2 min read timeout for streaming

            // Write request body
            byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
            conn.getOutputStream().write(bodyBytes);
            conn.getOutputStream().flush();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[Claude Chat] Model API error {}: {}", responseCode, error);
                emitter.send(SseEmitter.event().name("error").data("AI 服务错误: HTTP " + responseCode));
                emitter.complete();
                return;
            }

            // Read SSE stream from model
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;

                        try {
                            JsonNode chunk = objectMapper.readTree(data);
                            JsonNode choices = chunk.get("choices");
                            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta = choices.get(0).get("delta");
                                if (delta != null && delta.has("content")) {
                                    String content = delta.get("content").asText();
                                    if (!content.isEmpty()) {
                                        emitter.send(SseEmitter.event().data(content));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("[Claude Chat] Skipping unparseable chunk: {}", data);
                        }
                    }
                }
            }

            // 4. Done
            emitter.send(SseEmitter.event().name("done").data("completed"));
            emitter.complete();

        } catch (Exception e) {
            log.error("[Claude Chat] Stream error: sessionId={}", sessionId, e);
            try {
                emitter.send(SseEmitter.event().name("error").data("对话异常: " + e.getMessage()));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    private HttpURLConnection createConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        if (proxyConfig != null && proxyConfig.isEnabled()
                && proxyConfig.getHost() != null && !proxyConfig.getHost().isBlank()) {
            Proxy.Type proxyType = "SOCKS".equalsIgnoreCase(proxyConfig.getType())
                    ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            Proxy proxy = new Proxy(proxyType,
                    new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()));
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    /**
     * Build system prompt based on scene type.
     */
    private String buildSystemPrompt(String scene, Map<String, Object> metadata) {
        if (scene == null) scene = "";
        return switch (scene) {
            case "APM_DIAGNOSIS" -> """
                你是一个专业的 APM (Application Performance Monitoring) 诊断专家。
                你的职责是分析用户提供的调用链路追踪数据、错误信息和性能指标，找出根本原因并给出修复建议。

                请遵循以下原则：
                1. 首先确认问题类型（错误、性能瓶颈、超时等）
                2. 分析调用链中每个关键 Span 的状态和耗时
                3. 定位问题节点（错误 Span 或耗时异常 Span）
                4. 给出具体的修复建议（包括代码层面和架构层面）
                5. 如果是性能问题，给出优化优先级

                回答时使用中文，代码示例使用 markdown 格式。
                """;
            case "call-chain-analysis" -> """
                你是一个资深的 Java/Spring 架构师和代码审查专家。
                请基于提供的调用链数据进行深度分析，包括：
                1. 调用路径合理性
                2. 潜在的性能瓶颈
                3. 异常处理完整性
                4. 设计模式建议
                """;
            case "log-analysis" -> """
                你是一个日志分析专家。请基于提供的错误日志信息：
                1. 识别错误类型和根因
                2. 分析异常调用栈
                3. 给出修复建议
                4. 提供预防措施
                """;
            default -> """
                你是一个全能的开发助手，精通 Java、Spring Boot、微服务架构和性能调优。
                请根据用户提供的上下文信息给出专业、具体的回答。
                回答使用中文，代码示例使用 markdown 格式。
                """;
        };
    }
}
