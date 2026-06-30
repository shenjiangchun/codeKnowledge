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
 * WebSocket handler for real-time log analysis node progress.
 * Endpoint: /ws/log-analysis?reportId={reportId}
 *
 * Follows the same pattern as {@link com.huawei.hisi.ram.chat.RamChatWebSocketHandler}.
 */
@Slf4j
@Component
public class LogAnalysisWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessionByReportId = new ConcurrentHashMap<>();
    private final Map<String, String> reportIdByWsId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String reportId = extractReportId(session);
        if (reportId == null) {
            log.warn("[LogAnalysisWS] connection rejected: missing reportId in query");
            session.close();
            return;
        }
        sessionByReportId.put(reportId, session);
        reportIdByWsId.put(session.getId(), reportId);
        log.info("[LogAnalysisWS] connected reportId={} wsId={}", reportId, session.getId());
        sendMessage(session, Map.of("type", "connected", "reportId", reportId));
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
            log.warn("[LogAnalysisWS] failed to handle message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String reportId = reportIdByWsId.remove(session.getId());
        if (reportId != null) {
            sessionByReportId.remove(reportId);
        }
        log.info("[LogAnalysisWS] closed wsId={} reportId={} status={}",
                session.getId(), reportId, status);
    }

    /**
     * Push event to WebSocket subscriber for the given reportId.
     * Called by LogAnalysisEventEmitter.
     */
    public void pushEvent(long reportId, String jsonPayload) {
        String key = String.valueOf(reportId);
        WebSocketSession session = sessionByReportId.get(key);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(jsonPayload));
        } catch (IOException e) {
            log.warn("[LogAnalysisWS] push failed reportId={}: {}", reportId, e.getMessage());
        }
    }

    public boolean hasActiveSession(long reportId) {
        WebSocketSession session = sessionByReportId.get(String.valueOf(reportId));
        return session != null && session.isOpen();
    }

    private String extractReportId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("reportId");
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(JSON.toJSONString(message)));
            } catch (IOException e) {
                log.warn("[LogAnalysisWS] sendMessage failed: {}", e.getMessage());
            }
        }
    }
}
