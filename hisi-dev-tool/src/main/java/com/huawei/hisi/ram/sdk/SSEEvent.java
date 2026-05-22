package com.huawei.hisi.ram.sdk;

import java.util.Map;

/**
 * Streaming event surfaced by {@link ClaudeSessionService#sendUserMessage}.
 *
 * <p>Carries either a text delta, a tool-use call, a tool result, a finish
 * marker, or an error. Discriminated via {@link #type()}; the value fields are
 * populated only for the relevant variant.
 */
public record SSEEvent(
        Type type,
        String text,
        String toolUseId,
        String toolName,
        Map<String, Object> input,
        String output,
        String error
) {

    public enum Type { DELTA, TOOL_USE, TOOL_RESULT, FINISH, ERROR }

    public static SSEEvent delta(String text) {
        return new SSEEvent(Type.DELTA, text, null, null, null, null, null);
    }

    public static SSEEvent toolUse(String id, String name, Map<String, Object> input) {
        return new SSEEvent(Type.TOOL_USE, null, id, name, input, null, null);
    }

    public static SSEEvent toolResult(String id, String output) {
        return new SSEEvent(Type.TOOL_RESULT, null, id, null, null, output, null);
    }

    public static SSEEvent finish() {
        return new SSEEvent(Type.FINISH, null, null, null, null, null, null);
    }

    public static SSEEvent error(String msg) {
        return new SSEEvent(Type.ERROR, null, null, null, null, null, msg);
    }
}
