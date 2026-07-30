package com.huawei.hisi.controller;

import com.huawei.hisi.config.AgentTypeRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
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
            return ResponseEntity.notFound().build();
        }

        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("[AgentChat] agentType={}, provider={}, msgLen={}",
                agentType, config.systemPrompt() != null ? "configured" : "null",
                request.message().length());

        SseEmitter emitter = new SseEmitter(300_000L);
        String sessionId = request.sessionId() != null ? request.sessionId() : "";

        emitter.onTimeout(() -> log.warn("[AgentChat] SSE timeout: agentType={}", agentType));
        emitter.onCompletion(() -> log.debug("[AgentChat] SSE done: agentType={}", agentType));

        sseExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("session").data(sessionId));

                // Stream using Spring AI ChatClient with per-agent system prompt
                agentChatClient.prompt()
                        .system(config.systemPrompt() != null ? config.systemPrompt() : "")
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
        // Fallback: read directly from registry; if empty, list known keys
        var keys = agentTypeRegistry.keys();
        if (keys.isEmpty()) {
            result.put("agentTypes", java.util.List.of(
                    "apm-diagnose", "call-chain-analysis", "log-analysis",
                    "code-analysis", "dialog", "fix"));
            result.put("_note", "YAML binding of hisi.agents not active; showing hardcoded list");
        } else {
            result.put("agentTypes", keys);
        }
        return ResponseEntity.ok(result);
    }

    // ── DTOs ──

    public record ChatRequest(String message, String sessionId, Map<String, Object> context) {}

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
