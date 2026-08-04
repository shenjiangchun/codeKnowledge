package com.huawei.hisi.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UnifiedWebSocketHandler")
class UnifiedWebSocketHandlerTest {

    private UnifiedWebSocketHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);

        var testChannel = new TestChannel();
        handler = new UnifiedWebSocketHandler(List.of(testChannel));
    }

    @Test
    @DisplayName("registers channels from constructor list")
    void registersChannels() {
        assertThat(handler.channelCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("subscribe to known channel returns subscribed event")
    void subscribe_knownChannel_returnsSubscribed() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session,
                new TextMessage("""
                    {"type":"subscribe","channel":"test","sessionId":"abc"}
                    """));

        verify(session).sendMessage(argThat(msg -> {
            String payload = ((TextMessage) msg).getPayload();
            return payload.contains("\"subscribed\"") && payload.contains("\"test\"");
        }));
    }

    @Test
    @DisplayName("subscribe to unknown channel returns error")
    void subscribe_unknownChannel_returnsError() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session,
                new TextMessage("""
                    {"type":"subscribe","channel":"nonexistent"}
                    """));

        verify(session).sendMessage(argThat(msg -> {
            String payload = ((TextMessage) msg).getPayload();
            return payload.contains("\"unknown_channel\"");
        }));
    }

    @Test
    @DisplayName("unknown message type returns error")
    void unknownType_returnsError() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session,
                new TextMessage("""
                    {"type":"invalid","channel":"test"}
                    """));

        verify(session).sendMessage(argThat(msg -> {
            String payload = ((TextMessage) msg).getPayload();
            return payload.contains("\"unknown_type\"");
        }));
    }

    @Test
    @DisplayName("disconnect removes subscription and calls onDisconnect")
    void disconnect_callsOnDisconnect() throws Exception {
        var tracked = new TrackedChannel();
        handler = new UnifiedWebSocketHandler(List.of(tracked));
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session,
                new TextMessage("""
                    {"type":"subscribe","channel":"tracked"}
                    """));
        verify(session, atLeastOnce()).sendMessage(any());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(tracked.disconnected).isTrue();
    }

    // ── test channels ──

    static class TestChannel implements ChannelHandler {
        @Override public String channelName() { return "test"; }
        @Override public void onSubscribe(WebSocketSession s, String sid, long seq) {}
        @Override public void onMessage(WebSocketSession s, Map<String, Object> p) {}
        @Override public void onDisconnect(WebSocketSession s) {}
    }

    static class TrackedChannel implements ChannelHandler {
        boolean disconnected = false;
        @Override public String channelName() { return "tracked"; }
        @Override public void onSubscribe(WebSocketSession s, String sid, long seq) {}
        @Override public void onMessage(WebSocketSession s, Map<String, Object> p) {}
        @Override public void onDisconnect(WebSocketSession s) { disconnected = true; }
    }
}
