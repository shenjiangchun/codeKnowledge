package com.huawei.hisi.agent.event;

import com.huawei.hisi.agent.model.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 事件发布器
 * 负责通过 WebSocket 推送 Agent 执行状态
 *
 * 功能：
 * 1. 管理 WebSocket 会话
 * 2. 推送 Agent 执行事件
 * 3. 支持按 requestId 分组推送
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class AgentEventPublisher extends TextWebSocketHandler {

    // WebSocket 会话映射：requestId -> WebSocketSession
    private final Map<String, WebSocketSession> sessionByRequestId = new ConcurrentHashMap<>();

    // WebSocket 会话映射：sessionId -> requestId
    private final Map<String, String> requestIdBySessionId = new ConcurrentHashMap<>();

    /**
     * 注册会话
     * 将 WebSocket 会话与 requestId 关联
     *
     * @param requestId 诊断请求 ID
     * @param session WebSocket 会话
     */
    public void registerSession(String requestId, WebSocketSession session) {
        sessionByRequestId.put(requestId, session);
        requestIdBySessionId.put(session.getId(), requestId);
        log.info("Registered WebSocket session for requestId: {}, sessionId: {}", requestId, session.getId());
    }

    /**
     * 取消注册会话
     *
     * @param requestId 诊断请求 ID
     */
    public void unregisterSession(String requestId) {
        WebSocketSession session = sessionByRequestId.remove(requestId);
        if (session != null) {
            requestIdBySessionId.remove(session.getId());
            log.info("Unregistered WebSocket session for requestId: {}", requestId);
        }
    }

    /**
     * 发布事件
     * 推送 Agent 事件到关联的 WebSocket 会话
     *
     * @param event Agent 事件
     */
    public void publishEvent(AgentEvent event) {
        String requestId = event.getRequestId();
        WebSocketSession session = sessionByRequestId.get(requestId);

        if (session == null || !session.isOpen()) {
            log.debug("No active WebSocket session for requestId: {}, event will be logged only", requestId);
            logEvent(event);
            return;
        }

        try {
            String message = JSON.toJSONString(event);
            session.sendMessage(new TextMessage(message));
            log.debug("Published event to WebSocket: requestId={}, eventType={}", requestId, event.getEventType());
        } catch (IOException e) {
            log.error("Failed to send event to WebSocket: requestId={}, error={}", requestId, e.getMessage());
        }
    }

    /**
     * 发布事件（异步）
     * 不阻塞调用线程
     *
     * @param event Agent 事件
     */
    public void publishEventAsync(AgentEvent event) {
        // 使用 CompletableFuture 异步推送（兼容 Java 17）
        CompletableFuture.runAsync(() -> publishEvent(event));
    }

    /**
     * 广播事件到所有会话
     *
     * @param event Agent 事件
     */
    public void broadcastEvent(AgentEvent event) {
        for (WebSocketSession session : sessionByRequestId.values()) {
            if (session.isOpen()) {
                try {
                    String message = JSON.toJSONString(event);
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.warn("Failed to broadcast to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
        log.debug("Broadcasted event to {} sessions", sessionByRequestId.size());
    }

    /**
     * 记录事件日志
     */
    private void logEvent(AgentEvent event) {
        log.info("[AgentEvent] requestId={}, type={}, agent={}, message={}",
                event.getRequestId(),
                event.getEventType(),
                event.getAgentType(),
                event.getMessage());
    }

    /**
     * 检查会话是否存在
     *
     * @param requestId 诊断请求 ID
     * @return 是否存在活跃会话
     */
    public boolean hasActiveSession(String requestId) {
        WebSocketSession session = sessionByRequestId.get(requestId);
        return session != null && session.isOpen();
    }

    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return (int) sessionByRequestId.values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }

    // ========== WebSocket Handler 方法 ==========

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: sessionId={}", session.getId());
        // 发送连接成功消息
        sendMessage(session, Map.of("type", "connected", "sessionId", session.getId()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received WebSocket message: sessionId={}, payload={}", session.getId(), payload);

        try {
            // 解析 JSON 消息
            Map<String, Object> msg = JSON.parseObject(payload, Map.class);
            String action = (String) msg.get("action");

            // 支持 action 字段（前端格式）
            if (action != null) {
                switch (action) {
                    case "start":
                        // 启动诊断，订阅请求
                        String requestId = java.util.UUID.randomUUID().toString();
                        registerSession(requestId, session);
                        sendMessage(session, Map.of(
                                "type", "started",
                                "requestId", requestId,
                                "message", "诊断会话已启动"
                        ));
                        break;
                    case "cancel":
                        // 取消诊断
                        String cancelRequestId = (String) msg.get("requestId");
                        if (cancelRequestId != null) {
                            unregisterSession(cancelRequestId);
                            sendMessage(session, Map.of("type", "cancelled", "requestId", cancelRequestId));
                        }
                        break;
                    case "ping":
                        // 心跳响应
                        sendMessage(session, Map.of("type", "pong"));
                        break;
                    default:
                        sendMessage(session, Map.of("type", "error", "message", "Unknown action: " + action));
                }
                return;
            }

            // 兼容旧格式（type 字段）
            String type = (String) msg.get("type");
            if ("subscribe".equals(type)) {
                // 订阅诊断请求
                String requestId = (String) msg.get("requestId");
                if (requestId != null) {
                    registerSession(requestId, session);
                    sendMessage(session, Map.of("type", "subscribed", "requestId", requestId));
                }
            } else if ("unsubscribe".equals(type)) {
                // 取消订阅
                String requestId = requestIdBySessionId.get(session.getId());
                if (requestId != null) {
                    unregisterSession(requestId);
                    sendMessage(session, Map.of("type", "unsubscribed"));
                }
            } else if ("ping".equals(type)) {
                // 心跳响应
                sendMessage(session, Map.of("type", "pong"));
            }
        } catch (Exception e) {
            log.warn("Failed to handle WebSocket message: {}", e.getMessage());
            sendMessage(session, Map.of("type", "error", "message", "Invalid message format"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        String requestId = requestIdBySessionId.remove(session.getId());
        if (requestId != null) {
            sessionByRequestId.remove(requestId);
        }
        log.info("WebSocket connection closed: sessionId={}, requestId={}, status={}", session.getId(), requestId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error: sessionId={}, error={}", session.getId(), exception.getMessage());
    }

    /**
     * 发送消息到 WebSocket 会话
     */
    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(JSON.toJSONString(message)));
            } catch (IOException e) {
                log.warn("Failed to send message to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}