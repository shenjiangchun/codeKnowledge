package com.huawei.hisi.ram.chat;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RamChatWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessionByRamSessionId = new ConcurrentHashMap<>();
    private final Map<String, String> ramSessionIdByWsId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ramSessionId = extractSessionId(session);
        if (ramSessionId == null) {
            log.warn("[RamChatWS] connection rejected: missing sessionId in query");
            session.close();
            return;
        }
        sessionByRamSessionId.put(ramSessionId, session);
        ramSessionIdByWsId.put(session.getId(), ramSessionId);
        log.info("[RamChatWS] connected ramSessionId={} wsId={}", ramSessionId, session.getId());
        sendMessage(session, Map.of("type", "connected", "sessionId", ramSessionId));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        try {
            Map<String, Object> msg = JSON.parseObject(payload, Map.class);
            String action = (String) msg.get("action");
            if ("ping".equals(action)) {
                sendMessage(session, Map.of("type", "pong"));
            }
        } catch (Exception e) {
            log.warn("[RamChatWS] failed to handle message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String ramSessionId = ramSessionIdByWsId.remove(session.getId());
        if (ramSessionId != null) {
            sessionByRamSessionId.remove(ramSessionId);
        }
        log.info("[RamChatWS] closed wsId={} ramSessionId={} status={}",
                session.getId(), ramSessionId, status);
    }

    public void pushEvent(long sessionId, Map<String, Object> event) {
        String key = String.valueOf(sessionId);
        WebSocketSession session = sessionByRamSessionId.get(key);
        if (session == null || !session.isOpen()) {
            log.warn("[RamChatWS] no active WebSocket session for sessionId={} — event dropped (client may have disconnected)", sessionId);
            return;
        }
        try {
            session.sendMessage(new TextMessage(JSON.toJSONString(event)));
        } catch (IOException e) {
            log.warn("[RamChatWS] push failed sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private static final String WS_PATH_PREFIX = "/ws/ram-chat/";

    private String extractSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        // 1) Path-based: /ws/ram-chat/{sessionId} — used by FixChatView.vue
        String path = uri.getPath();
        if (path != null && path.startsWith(WS_PATH_PREFIX)) {
            String tail = path.substring(WS_PATH_PREFIX.length());
            if (!tail.isBlank() && !tail.contains("/")) {
                return tail;
            }
        }
        // 2) Query-based fallback: /ws/ram-chat?sessionId={sid} — used by useRamChatWebSocket.ts
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("sessionId");
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(JSON.toJSONString(message)));
            } catch (IOException e) {
                log.warn("[RamChatWS] sendMessage failed: {}", e.getMessage());
            }
        }
    }
}
