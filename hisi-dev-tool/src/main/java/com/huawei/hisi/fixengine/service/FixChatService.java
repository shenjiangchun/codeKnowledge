package com.huawei.hisi.fixengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.fixengine.agent.FixAgent;
import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.ram.chat.RamChatWebSocketHandler;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles multi-turn follow-up chat for an in-progress fix session.
 *
 * <p>Persists {@code USER_MSG}, then asynchronously runs {@link FixAgent#handleFollowUp}
 * and pushes the reply via WebSocket as {@code assistant_delta} + {@code checkpoint}
 * events (same shape as {@code RamChatOrchestrator}), so the frontend renders
 * follow-up replies with the same components as RAM chat.
 *
 * <p>HTTP returns immediately ({@code 202} style) — the AI reply is delivered
 * through the WebSocket channel, not the HTTP response body.
 */
@Slf4j
@Service
public class FixChatService {

    private final FixSessionRepository fixSessionRepository;
    private final AgentEventRepository agentEventRepository;
    private final FixAgent fixAgent;
    private final RamChatWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fix-chat");
        t.setDaemon(true);
        return t;
    });

    public FixChatService(FixSessionRepository fixSessionRepository,
                          AgentEventRepository agentEventRepository,
                          FixAgent fixAgent,
                          RamChatWebSocketHandler wsHandler,
                          ObjectMapper objectMapper) {
        this.fixSessionRepository = fixSessionRepository;
        this.agentEventRepository = agentEventRepository;
        this.fixAgent = fixAgent;
        this.wsHandler = wsHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * @param sessionId   fix session id (String)
     * @param userMessage user's follow-up message
     * @return {@code null} — reply is delivered via WebSocket
     */
    public String chat(String sessionId, String userMessage) {
        FixSession session = fixSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("FixSession not found: " + sessionId));

        long chatSessionId;
        try {
            chatSessionId = Long.parseLong(session.getChatSessionId());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalStateException(
                    "FixSession " + sessionId + " has invalid chatSessionId: " + session.getChatSessionId());
        }

        String turnId = "fix-chat-" + UUID.randomUUID();
        String userPayload = toJson(Map.of(
                "turnId", turnId,
                "text", userMessage == null ? "" : userMessage
        ));
        AgentEvent userEvent = AgentEvent.userMsg(chatSessionId, 0, userPayload,
                idemKey(chatSessionId, "user-msg", turnId));
        userEvent.setTurnId(turnId);
        agentEventRepository.append(userEvent);
        wsPush(chatSessionId, wsEvent(userEvent, chatSessionId, Map.of(
                "type", "user_msg",
                "turnId", turnId,
                "text", userMessage
        )));
        log.info("[FixChatService] persisted USER_MSG sid={} turnId={}", chatSessionId, turnId);

        CompletableFuture.runAsync(() -> {
            try {
                String reply = fixAgent.handleFollowUp(chatSessionId, userMessage);
                Map<String, Object> deltaPayloadMap = new LinkedHashMap<>();
                deltaPayloadMap.put("turnId", turnId);
                deltaPayloadMap.put("delta", reply == null ? "" : reply);
                String deltaPayload = toJson(deltaPayloadMap);
                AgentEvent deltaEv = AgentEvent.assistantDelta(chatSessionId, 0, deltaPayload,
                        idemKey(chatSessionId, "assistant-delta", turnId));
                deltaEv.setTurnId(turnId);
                agentEventRepository.append(deltaEv);
                wsPush(chatSessionId, wsEvent(deltaEv, chatSessionId, Map.of(
                        "type", "assistant_delta",
                        "turnId", turnId,
                        "delta", reply
                )));

                Map<String, Object> ckptPayloadMap = new LinkedHashMap<>();
                ckptPayloadMap.put("turnId", turnId);
                ckptPayloadMap.put("summary", "");
                ckptPayloadMap.put("finalText", reply == null ? "" : reply);
                String ckptPayload = toJson(ckptPayloadMap);
                AgentEvent ckptEv = AgentEvent.builder()
                        .sessionId(chatSessionId)
                        .seq(0)
                        .type(com.huawei.hisi.ram.model.EventType.CHECKPOINT)
                        .payload(ckptPayload)
                        .idempotencyKey(idemKey(chatSessionId, "checkpoint", turnId))
                        .turnId(turnId)
                        .createdAt(System.currentTimeMillis() / 1000L)
                        .build();
                agentEventRepository.append(ckptEv);
                wsPush(chatSessionId, wsEvent(ckptEv, chatSessionId, Map.of(
                        "type", "checkpoint",
                        "turnId", turnId,
                        "summary", "",
                        "finalText", reply
                )));
                log.info("[FixChatService] done turnId={} reply.len={}", turnId, reply.length());
            } catch (Exception e) {
                log.error("[FixChatService] failed turnId={}: {}", turnId, e.getMessage(), e);
                AgentEvent errEv = AgentEvent.builder()
                        .sessionId(chatSessionId)
                        .seq(0)
                        .type(com.huawei.hisi.ram.model.EventType.ERROR)
                        .payload(toJson(Map.of(
                                "turnId", turnId,
                                "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                        )))
                        .idempotencyKey(idemKey(chatSessionId, "error", turnId))
                        .turnId(turnId)
                        .createdAt(System.currentTimeMillis() / 1000L)
                        .build();
                agentEventRepository.append(errEv);
                wsPush(chatSessionId, wsEvent(errEv, chatSessionId, Map.of(
                        "type", "error",
                        "turnId", turnId,
                        "error", e.getMessage()
                )));
            }
        }, asyncExecutor);

        return null;
    }

    private void wsPush(long sessionId, Map<String, Object> event) {
        try {
            wsHandler.pushEvent(sessionId, event);
        } catch (Exception e) {
            log.debug("[FixChatService] ws push failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> wsEvent(AgentEvent ev, long sessionId, Map<String, Object> base) {
        Map<String, Object> enriched = new LinkedHashMap<>(base);
        enriched.put("sessionId", sessionId);
        if (ev != null && ev.getId() != null) {
            enriched.put("eventId", ev.getId());
            enriched.put("seq", ev.getSeq());
            enriched.put("createdAt", ev.getCreatedAt());
        } else {
            enriched.put("createdAt", System.currentTimeMillis() / 1000L);
        }
        return enriched;
    }

    private String toJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            log.debug("[FixChatService] payload serialize failed: {}", e.getMessage());
            return "{}";
        }
    }

    private static String idemKey(long sid, String kind, String turnId) {
        return "fix-" + sid + "-" + turnId + "-" + kind;
    }
}
