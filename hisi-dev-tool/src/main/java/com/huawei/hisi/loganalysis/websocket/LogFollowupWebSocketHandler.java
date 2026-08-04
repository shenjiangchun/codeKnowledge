package com.huawei.hisi.loganalysis.websocket;

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

/**
 * WebSocket handler for log follow-up Q&A sessions.
 * Endpoint: /ws/log-followup?sessionId={sessionId}
 *
 * Streams Claude assistant deltas and tool-use events in real-time.
 */
@Slf4j
@Component
public class LogFollowupWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessionByFollowupId = new ConcurrentHashMap<>();
    private final Map<String, String> followupIdByWsId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId == null) {
            log.warn("[LogFollowupWS] connection rejected: missing sessionId in query");
            session.close();
            return;
        }
        sessionByFollowupId.put(sessionId, session);
        followupIdByWsId.put(session.getId(), sessionId);
        log.info("[LogFollowupWS] connected sessionId={} wsId={}", sessionId, session.getId());
        sendMessage(session, Map.of("type", "connected", "sessionId", sessionId));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> msg = JSON.parseObject(message.getPayload(), Map.class);
            String action = (String) msg.get("action");
            if ("ping".equals(action)) {
                sendMessage(session, Map.of("type", "pong"));
            }
        } catch (Exception e) {
            log.warn("[LogFollowupWS] failed to handle message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = followupIdByWsId.remove(session.getId());
        if (sessionId != null) {
            sessionByFollowupId.remove(sessionId);
        }
        log.info("[LogFollowupWS] closed wsId={} sessionId={} status={}",
                session.getId(), sessionId, status);
    }

    public void pushEvent(String sessionId, Map<String, Object> event) {
        WebSocketSession session = sessionByFollowupId.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(JSON.toJSONString(event)));
        } catch (IOException e) {
            log.warn("[LogFollowupWS] push failed sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private String extractSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("sessionId");
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(JSON.toJSONString(message)));
            } catch (IOException e) {
                log.warn("[LogFollowupWS] sendMessage failed: {}", e.getMessage());
            }
        }
    }
}
