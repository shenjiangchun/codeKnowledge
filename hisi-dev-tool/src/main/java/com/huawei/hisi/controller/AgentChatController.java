package com.huawei.hisi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified SSE endpoint for all LLM agent types.
 *
 * <p>Replaces the fragmented {@code /api/claude/universal-chat} + scene-based
 * routing with {@code POST /api/chat/{agentType}}. Agent types and system
 * prompts are declaratively configured in {@code hisi.agents.*} YAML.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class AgentChatController {

    private final ChatClient agentChatClient;
    private final AgentTypeRegistry agentTypeRegistry;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-chat-sse");
        t.setDaemon(true);
        return t;
    });

    /**
     * Sends a chat message to the specified agent type and returns an SSE stream.
     */
    @PostMapping(value = "/{agentType}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(
            @PathVariable String agentType,
            @RequestBody ChatRequest request) {

        var config = agentTypeRegistry.get(agentType);
        if (config == null) {
            SseEmitter err = new SseEmitter();
            err.completeWithError(new IllegalArgumentException("Unknown agent type: " + agentType));
            return ResponseEntity.notFound().build();
        }

        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("[AgentChat] agentType={}, provider={}, msgLen={}",
                agentType, config.provider(), request.message().length());

        SseEmitter emitter = new SseEmitter(300_000L);
        String sessionId = request.sessionId() != null ? request.sessionId() : "";

        emitter.onTimeout(() -> log.warn("[AgentChat] SSE timeout: agentType={}", agentType));
        emitter.onCompletion(() -> log.debug("[AgentChat] SSE done: agentType={}", agentType));

        sseExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("session").data(sessionId));

                // Stream using Spring AI ChatClient with per-agent system prompt
                agentChatClient.prompt()
                        .system(config.systemPrompt())
                        .user(request.message())
                        .stream()
                        .chatResponse()
                        .doOnNext(response -> {
                            try {
                                String token = response.getResult().getOutput().getText();
                                if (token != null && !token.isEmpty()) {
                                    emitter.send(SseEmitter.event()
                                            .data("{\"choices\":[{\"delta\":{\"content\":\"" +
                                                    escapeJson(token) + "\"}}]}"));
                                }
                            } catch (IOException e) {
                                throw new RuntimeException("SSE send failed", e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.error("[AgentChat] Stream error: agentType={}", agentType, error);
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data("AI service error"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("[AgentChat] Fatal error: agentType={}", agentType, e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return ResponseEntity.ok(emitter);
    }

    /** List configured agent types for frontend discovery. */
    @PostMapping("/_list")
    public ResponseEntity<Map<String, Object>> listAgents() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentTypes", agentTypeRegistry.keys());
        return ResponseEntity.ok(result);
    }

    // ── DTOs ──

    public record ChatRequest(String message, String sessionId, Map<String, Object> context) {}

    /** Agent type config loaded from hisi.agents.* YAML. */
    public record AgentTypeConfig(String systemPrompt, String provider, Integer toolCallLimit) {}

    /** Registry that auto-binds hisi.agents.* config. */
    @ConfigurationProperties(prefix = "hisi.agents")
    @Component
    @Primary
    public static class AgentTypeRegistry {
        private final Map<String, AgentTypeConfig> agents = new ConcurrentHashMap<>();

        public AgentTypeConfig get(String key) { return agents.get(key); }
        public java.util.Set<String> keys() { return agents.keySet(); }
        public Map<String, AgentTypeConfig> getAgents() { return agents; }
        public void setAgents(Map<String, AgentTypeConfig> agents) {
            this.agents.clear();
            this.agents.putAll(agents);
        }
    }

    // ── utilities ──

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 10);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
