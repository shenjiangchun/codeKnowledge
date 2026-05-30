package com.huawei.hisi.ram.mcp;

import java.util.Map;

/**
 * Envelope returned by {@link RamMcpServer#invoke(String, Map)}.
 */
public record McpResponse(boolean ok, Map<String, Object> result, String error) {

    public static McpResponse ok(Map<String, Object> r) {
        return new McpResponse(true, r == null ? Map.of() : Map.copyOf(r), null);
    }

    public static McpResponse error(String msg) {
        return new McpResponse(false, Map.of(), msg);
    }
}
