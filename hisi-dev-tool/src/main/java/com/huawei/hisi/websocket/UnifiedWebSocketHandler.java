package com.huawei.hisi.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unified WebSocket endpoint at {@code /ws/agent} — replaces 6 fragmented
 * WS endpoints with a single connection using channel-based routing.
 *
 * <h3>Protocol</h3>
 * <pre>
 * Client → Server:
 *   {"type":"subscribe", "channel":"ram-chat", "sessionId":"abc"}
 *   {"type":"message",    "channel":"ram-chat", "payload":{...}}
 *
 * Server → Client:
 *   {"type":"event",  "channel":"ram-chat", "eventType":"...", "seq":42, ...}
 *   {"type":"error",  "channel":"ram-chat", "eventType":"unknown_channel"}
 * </pre>
 */
@Slf4j
@Component
public class UnifiedWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, ChannelHandler> channels = new ConcurrentHashMap<>();
    private final Map<String, ChannelHandler> sessionSubscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public UnifiedWebSocketHandler(List<ChannelHandler> channelHandlers) {
        for (ChannelHandler h : channelHandlers) {
            channels.put(h.channelName(), h);
            log.info("[UnifiedWS] registered channel: {}", h.channelName());
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[UnifiedWS] connected: sessionId={}", session.getId());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = mapper.readValue(message.getPayload(), Map.class);
        String type = (String) msg.getOrDefault("type", "");
        String channelName = (String) msg.get("channel");

        switch (type) {
            case "subscribe" -> handleSubscribe(session, channelName, msg);
            case "message" -> handleChannelMessage(session, channelName, msg);
            default -> sendError(session, "unknown_type", "Unknown message type: " + type);
        }
    }

    private void handleSubscribe(WebSocketSession session, String channelName,
                                  Map<String, Object> msg) throws IOException {
        if (channelName == null || !channels.containsKey(channelName)) {
            sendError(session, "unknown_channel",
                    "Unknown channel: " + channelName);
            return;
        }
        ChannelHandler handler = channels.get(channelName);
        String sessionId = (String) msg.get("sessionId");
        long lastSeq = msg.get("lastSeq") instanceof Number n ? n.longValue() : 0L;

        sessionSubscriptions.put(session.getId(), handler);
        handler.onSubscribe(session, sessionId, lastSeq);

        sendToSession(session, Map.of(
                "type", "subscribed",
                "channel", channelName));
    }

    private void handleChannelMessage(WebSocketSession session, String channelName,
                                       Map<String, Object> msg) {
        if (channelName == null || !channels.containsKey(channelName)) {
            try {
                sendError(session, "unknown_channel",
                        "Unknown channel: " + channelName);
            } catch (IOException ignored) {}
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
        channels.get(channelName).onMessage(session, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ChannelHandler handler = sessionSubscriptions.remove(session.getId());
        if (handler != null) {
            handler.onDisconnect(session);
        }
        log.info("[UnifiedWS] disconnected: sessionId={}", session.getId());
    }

    // ── helpers ──

    void sendToSession(WebSocketSession session, Map<String, Object> payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (IOException e) {
            log.error("[UnifiedWS] send failed: sessionId={}", session.getId(), e);
        }
    }

    private void sendError(WebSocketSession session, String eventType, String message)
            throws IOException {
        sendToSession(session, Map.of(
                "type", "error",
                "eventType", eventType,
                "error", message));
    }

    /** Visible for testing — number of registered channels. */
    int channelCount() { return channels.size(); }

    /** Visible for testing — number of active subscriptions. */
    int subscriptionCount() { return sessionSubscriptions.size(); }
}
