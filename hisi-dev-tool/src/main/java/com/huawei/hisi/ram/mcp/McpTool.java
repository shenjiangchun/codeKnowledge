package com.huawei.hisi.ram.mcp;

import java.util.Map;

/**
 * Contract for a single MCP tool exposed through {@link RamMcpServer}.
 */
public interface McpTool {

    /** Unique tool name (used by the MCP client to address the tool). */
    String name();

    /** Execute the tool with the supplied JSON-like argument map. */
    Map<String, Object> execute(Map<String, Object> args);
}
