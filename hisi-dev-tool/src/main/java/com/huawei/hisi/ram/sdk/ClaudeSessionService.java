package com.huawei.hisi.ram.sdk;

import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Thin facade over the Anthropic Messages API with append-only event sourcing.
 *
 * <p>Each session is identified by an opaque numeric id ({@code sid}) and is
 * persisted via {@code AgentSessionRepository} / {@code AgentEventRepository}.
 * Streaming methods return Reactor {@link Flux} of {@link SSEEvent}.
 */
public interface ClaudeSessionService {

    long createSession(String userId, Map<String, Object> plan);

    Flux<SSEEvent> sendUserMessage(long sid, String text, SendOptions opts);

    void injectSystemMessage(long sid, String msg);

    void registerTool(long sid, ToolDefinition def, ToolHandler handler);

    Flux<SSEEvent> resumeSession(long sid, Long fromEventId);

    void abortSession(long sid, String reason);

    long forkSession(long sid, long atEventId);
}
