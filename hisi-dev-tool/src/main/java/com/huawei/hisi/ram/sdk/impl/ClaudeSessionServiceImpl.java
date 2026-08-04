package com.huawei.hisi.ram.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.sdk.ClaudeSessionService;
import com.huawei.hisi.ram.sdk.SSEEvent;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import com.huawei.hisi.ram.sdk.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Claude SDK wrapper. Persists every turn as an append-only event log
 * (via {@link AgentEventRepository}), streams typed {@link SSEEvent}s back to
 * the caller, and dispatches {@code tool_use} blocks to per-session
 * {@link ToolHandler}s registered in-process.
 *
 * <p>Each persisted event uses an idempotency key derived from the session id
 * and payload (sha256), so retries don't duplicate rows.
 */
@Slf4j
@Service
public class ClaudeSessionServiceImpl implements ClaudeSessionService {

    private final AgentSessionRepository sessionRepo;
    private final AgentEventRepository eventRepo;
    private final AnthropicHttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /** session id -> tool name -> (definition, handler). In-memory; not persisted. */
    private final Map<Long, Map<String, RegisteredTool>> toolRegistry = new ConcurrentHashMap<>();

    public ClaudeSessionServiceImpl(AgentSessionRepository sessionRepo,
                                    AgentEventRepository eventRepo,
                                    AnthropicHttpClient http) {
        this.sessionRepo = sessionRepo;
        this.eventRepo = eventRepo;
        this.http = http;
    }

    @Override
    public long createSession(String userId, Map<String, Object> plan) {
        AgentSession s = sessionRepo.save(AgentSession.newRunning(userId, SessionType.DEMAND));
        return s.getId();
    }

    @Override
    public Flux<SSEEvent> sendUserMessage(long sid, String text, SendOptions opts) {
        // 1. Persist USER_MSG.
        String userPayload = jsonString(Map.of("text", text));
        eventRepo.append(AgentEvent.userMsg(sid, 0, userPayload, idemKey(sid, userPayload)));

        // 2. Build conversation history from event log.
        List<Map<String, Object>> messages = buildMessages(sid);

        // 3. Resolve registered tools.
        List<ToolDefinition> tools = new ArrayList<>();
        Map<String, RegisteredTool> registered = toolRegistry.get(sid);
        if (registered != null) {
            for (RegisteredTool rt : registered.values()) tools.add(rt.def);
        }

        SendOptions effective = opts == null ? SendOptions.defaults() : opts;

        // 4. Stream raw SSE lines and translate into typed events.
        StringBuilder textAccum = new StringBuilder();
        return http.stream(messages, tools, effective)
                .concatMap(raw -> parseSseLine(sid, raw, textAccum, registered))
                .doOnComplete(() -> {
                    if (textAccum.length() > 0) {
                        String payload = jsonString(Map.of("text", textAccum.toString()));
                        eventRepo.append(AgentEvent.assistantDelta(
                                sid, 0, payload, idemKey(sid, payload)));
                    }
                });
    }

    private Flux<SSEEvent> parseSseLine(long sid, String raw, StringBuilder accum,
                                        Map<String, RegisteredTool> registered) {
        try {
            JsonNode node = json.readTree(raw);
            String type = node.path("type").asText("");
            switch (type) {
                case "content_block_delta": {
                    JsonNode delta = node.path("delta");
                    if ("text_delta".equals(delta.path("type").asText())) {
                        String t = delta.path("text").asText("");
                        accum.append(t);
                        return Flux.just(SSEEvent.delta(t));
                    }
                    return Flux.empty();
                }
                case "content_block_start": {
                    JsonNode block = node.path("content_block");
                    if ("tool_use".equals(block.path("type").asText())) {
                        String id = block.path("id").asText();
                        String name = block.path("name").asText();
                        Map<String, Object> input = json.convertValue(
                                block.path("input"), Map.class);
                        return handleToolUse(sid, id, name, input, registered);
                    }
                    return Flux.empty();
                }
                case "message_stop":
                    return Flux.just(SSEEvent.finish());
                default:
                    return Flux.empty();
            }
        } catch (Exception ex) {
            log.warn("[ClaudeSessionService] failed to parse SSE line: {}", ex.toString());
            return Flux.just(SSEEvent.error(ex.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Flux<SSEEvent> handleToolUse(long sid, String id, String name,
                                         Map<String, Object> input,
                                         Map<String, RegisteredTool> registered) {
        Map<String, Object> usePayload = new LinkedHashMap<>();
        usePayload.put("id", id);
        usePayload.put("name", name);
        usePayload.put("input", input == null ? Map.of() : input);
        String useJson = jsonString(usePayload);
        eventRepo.append(AgentEvent.toolUse(sid, 0, id, useJson, idemKey(sid, useJson)));

        Map<String, Object> result = Map.of("error", "tool not registered");
        if (registered != null && registered.containsKey(name)) {
            try {
                result = registered.get(name).handler.handle(id, input);
            } catch (Exception ex) {
                log.warn("[ClaudeSessionService] tool handler '{}' threw: {}", name, ex.toString());
                result = Map.of("error", ex.getMessage() == null ? "tool failed" : ex.getMessage());
            }
        }
        String resJson = jsonString(result);
        eventRepo.append(AgentEvent.toolResult(sid, 0, id, resJson, idemKey(sid, resJson)));

        return Flux.just(
                SSEEvent.toolUse(id, name, input),
                SSEEvent.toolResult(id, resJson));
    }

    @Override
    public void injectSystemMessage(long sid, String msg) {
        String payload = jsonString(Map.of("text", msg, "role", "system"));
        eventRepo.append(AgentEvent.userMsg(sid, 0, payload, idemKey(sid, payload)));
    }

    @Override
    public void registerTool(long sid, ToolDefinition def, ToolHandler handler) {
        toolRegistry
                .computeIfAbsent(sid, k -> new ConcurrentHashMap<>())
                .put(def.name(), new RegisteredTool(def, handler));
    }

    @Override
    public Flux<SSEEvent> resumeSession(long sid, Long fromEventId) {
        long threshold = fromEventId == null ? 0L : fromEventId;
        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        return Flux.fromIterable(events)
                .filter(e -> e.getId() != null && e.getId() > threshold)
                .map(this::toSseEvent);
    }

    private SSEEvent toSseEvent(AgentEvent e) {
        return switch (e.getType()) {
            case ASSISTANT_DELTA -> SSEEvent.delta(extractText(e.getPayload()));
            case TOOL_USE -> SSEEvent.toolUse(e.getToolUseId(), null, Map.of());
            case TOOL_RESULT -> SSEEvent.toolResult(e.getToolUseId(), e.getPayload());
            default -> SSEEvent.delta(e.getPayload() == null ? "" : e.getPayload());
        };
    }

    private String extractText(String payload) {
        if (payload == null) return "";
        try {
            JsonNode n = json.readTree(payload);
            return n.path("text").asText(payload);
        } catch (Exception ex) {
            return payload;
        }
    }

    @Override
    public void abortSession(long sid, String reason) {
        sessionRepo.updateStatus(sid, SessionStatus.ABORTED);
        String payload = jsonString(Map.of("reason", reason == null ? "" : reason));
        try {
            eventRepo.append(AgentEvent.builder()
                    .sessionId(sid)
                    .type(EventType.ERROR)
                    .payload(payload)
                    .idempotencyKey(idemKey(sid, "abort:" + payload))
                    .circuitState("OK")
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build());
        } catch (Exception ex) {
            log.warn("[ClaudeSessionService] abort log failed: {}", ex.toString());
        }
    }

    @Override
    public long forkSession(long sid, long atEventId) {
        AgentSession parent = sessionRepo.findById(sid)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sid));
        AgentSession forked = sessionRepo.save(AgentSession.newRunning(parent.getUserId(), SessionType.DEMAND));
        long newSid = forked.getId();
        for (AgentEvent e : eventRepo.findBySessionId(sid)) {
            if (e.getId() != null && e.getId() <= atEventId) {
                String key = idemKey(newSid, "fork:" + e.getId() + ":" + safe(e.getPayload()));
                AgentEvent copy = AgentEvent.builder()
                        .sessionId(newSid)
                        .type(e.getType())
                        .payload(e.getPayload())
                        .toolUseId(e.getToolUseId())
                        .idempotencyKey(key)
                        .circuitState("OK")
                        .validatorStatus("OK")
                        .createdAt(System.currentTimeMillis() / 1000L)
                        .build();
                eventRepo.append(copy);
            }
        }
        return newSid;
    }

    // ============================== helpers ==============================

    private List<Map<String, Object>> buildMessages(long sid) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentEvent e : eventRepo.findBySessionId(sid)) {
            switch (e.getType()) {
                case USER_MSG -> out.add(Map.of(
                        "role", "user",
                        "content", extractText(e.getPayload())));
                case ASSISTANT_DELTA -> out.add(Map.of(
                        "role", "assistant",
                        "content", extractText(e.getPayload())));
                default -> { /* skip */ }
            }
        }
        return out;
    }

    private String jsonString(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String idemKey(long sid, String payload) {
        return sha256(sid + ":" + safe(payload));
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(b);
        } catch (Exception ex) {
            throw new IllegalStateException("sha256 unavailable", ex);
        }
    }

    private record RegisteredTool(ToolDefinition def, ToolHandler handler) {}
}
