package com.huawei.hisi.apm.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.service.TargetProcessManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ApmWebSocketHandler extends TextWebSocketHandler {

    // sessionId -> WebSocket session (thread-safe decorator)
    private final Map<String, ConcurrentWebSocketSessionDecorator> sessionMap = new ConcurrentHashMap<>();
    // WebSocket session ID -> APM session ID (for cleanup on disconnect)
    private final Map<String, String> wsToApmSessionMap = new ConcurrentHashMap<>();

    /** Lazy to break the cycle: TargetProcessManager indirectly back-references this handler. */
    private final TargetProcessManager targetProcessManager;

    @Autowired
    public ApmWebSocketHandler(@Lazy TargetProcessManager targetProcessManager) {
        this.targetProcessManager = targetProcessManager;
    }

    private static final int SEND_TIME_LIMIT = 5000;   // 5s per message send timeout
    private static final int BUFFER_SIZE_LIMIT = 65536; // 64KB buffer per session

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JSONObject json = JSON.parseObject(message.getPayload());
            String action = json.getString("action");

            switch (action != null ? action : "") {
                case "connect" -> handleConnect(session, json);
                case "ping" -> sendMessage(session.getId(), "pong", Map.of());
                default -> log.warn("[APM WS] Unknown action: {}", action);
            }
        } catch (Exception e) {
            log.error("[APM WS] Error handling message", e);
            sendError(session, "Invalid message format");
        }
    }

    private void handleConnect(WebSocketSession session, JSONObject json) {
        String sessionId = json.getString("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            sendError(session, "Missing sessionId");
            return;
        }

        ConcurrentWebSocketSessionDecorator decorated =
            new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT, BUFFER_SIZE_LIMIT);
        sessionMap.put(sessionId, decorated);
        wsToApmSessionMap.put(session.getId(), sessionId);

        log.info("[APM WS] Client connected for session: {}", sessionId);
        sendMessage(sessionId, "connected", Map.of("sessionId", sessionId));

        // Replay buffered stdout lines emitted before this client connected,
        // so a fast-dying target process's error output is not lost.
        try {
            List<String> buffered = targetProcessManager.getOutputLines(sessionId, 500);
            if (!buffered.isEmpty()) {
                log.info("[APM WS] Replaying {} buffered log lines for session {}",
                        buffered.size(), sessionId);
                for (String line : buffered) {
                    sendMessage(sessionId, "PROCESS_LOG",
                            Map.of("sessionId", sessionId, "line", line, "replayed", true));
                }
            }
        } catch (Exception ex) {
            log.warn("[APM WS] Failed to replay buffered logs for session {}: {}",
                    sessionId, ex.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String apmSessionId = wsToApmSessionMap.remove(session.getId());
        if (apmSessionId != null) {
            sessionMap.remove(apmSessionId);
            log.info("[APM WS] Client disconnected for session: {}", apmSessionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[APM WS] Transport error for session: {}", session.getId(), exception);
        String apmSessionId = wsToApmSessionMap.remove(session.getId());
        if (apmSessionId != null) {
            sessionMap.remove(apmSessionId);
        }
    }

    // ── Public API for pushing events ───────────────────────────────

    /**
     * Push a batch of spans to the connected client.
     */
    public void pushSpans(String sessionId, List<ApmSpanEntity> spans) {
        List<Map<String, Object>> spanList = spans.stream()
            .map(this::spanToMap)
            .toList();

        sendMessage(sessionId, "SPAN_BATCH", Map.of("spans", spanList));
    }

    /**
     * Push a general event to the connected client.
     */
    public void pushEvent(String sessionId, String eventType, Map<String, Object> data) {
        sendMessage(sessionId, eventType, data);
    }

    /**
     * Check if a client is connected for a session.
     */
    public boolean isConnected(String sessionId) {
        ConcurrentWebSocketSessionDecorator session = sessionMap.get(sessionId);
        return session != null && session.isOpen();
    }

    // ── Internal helpers ────────────────────────────────────────────

    private void sendMessage(String sessionId, String type, Map<String, Object> data) {
        ConcurrentWebSocketSessionDecorator session = sessionMap.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            Map<String, Object> messageMap = new LinkedHashMap<>();
            messageMap.put("type", type);
            messageMap.put("timestamp", System.currentTimeMillis());
            messageMap.putAll(data);

            session.sendMessage(new TextMessage(JSON.toJSONString(messageMap)));
        } catch (IOException e) {
            log.error("[APM WS] Failed to send message to session: {}", sessionId, e);
        }
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        try {
            Map<String, Object> msg = Map.of(
                "type", "error",
                "message", errorMessage,
                "timestamp", System.currentTimeMillis()
            );
            session.sendMessage(new TextMessage(JSON.toJSONString(msg)));
        } catch (IOException e) {
            log.error("[APM WS] Failed to send error message", e);
        }
    }

    private Map<String, Object> spanToMap(ApmSpanEntity span) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spanId", span.getSpanId());
        map.put("parentSpanId", span.getParentSpanId());
        map.put("traceId", span.getTraceId());
        map.put("operationName", span.getOperationName());
        map.put("serviceName", span.getServiceName());
        map.put("spanKind", span.getSpanKind());
        map.put("startTimeNs", span.getStartTimeNs());
        map.put("endTimeNs", span.getEndTimeNs());
        map.put("durationMs", (span.getEndTimeNs() - span.getStartTimeNs()) / 1_000_000);
        map.put("statusCode", span.getStatusCode());
        map.put("statusMessage", span.getStatusMessage());
        map.put("kgNodeId", span.getKgNodeId());
        map.put("kgMatchLevel", span.getKgMatchLevel());
        if (span.getAttributes() != null) {
            map.put("className", span.getAttributes().get("code.namespace"));
            map.put("methodName", span.getAttributes().get("code.function"));
        }
        return map;
    }
}
