package com.huawei.hisi.websocket;

import org.springframework.web.socket.WebSocketSession;

/**
 * Channel handler for the Unified WebSocket Gateway at {@code /ws/agent}.
 * <p>
 * Each implementation handles one channel type (ram-chat, apm, log-analysis, …).
 * The {@link UnifiedWebSocketHandler} routes incoming JSON messages to the
 * matching channel by {@code channel} field.
 */
public interface ChannelHandler {

    /** Channel name used in the {@code "channel"} field of client messages. */
    String channelName();

    /**
     * Called when a client subscribes to this channel.
     *
     * @param session   the WebSocket session
     * @param sessionId client-supplied session id (may be null)
     * @param lastSeq   last seen sequence number for replay, or 0
     */
    void onSubscribe(WebSocketSession session, String sessionId, long lastSeq);

    /**
     * Called when a client sends a message on this channel.
     *
     * @param session the WebSocket session
     * @param payload the message payload (JSON object as Map)
     */
    void onMessage(WebSocketSession session, java.util.Map<String, Object> payload);

    /**
     * Called when the WebSocket session disconnects.
     */
    void onDisconnect(WebSocketSession session);
}
