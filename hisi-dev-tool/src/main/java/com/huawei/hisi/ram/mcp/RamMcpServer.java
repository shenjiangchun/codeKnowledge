package com.huawei.hisi.ram.mcp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Facade that dispatches MCP tool invocations to the registered {@link McpTool}
 * implementations. Tools are auto-discovered through Spring constructor injection.
 */
@Component
public class RamMcpServer {

    private static final Logger log = LoggerFactory.getLogger(RamMcpServer.class);

    private final List<McpTool> tools;
    private final Map<String, McpTool> toolByName = new HashMap<>();

    public RamMcpServer(List<McpTool> tools) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        registerTools();
    }

    @PostConstruct
    void registerTools() {
        toolByName.clear();
        for (McpTool t : tools) {
            if (t == null || t.name() == null || t.name().isBlank()) {
                continue;
            }
            toolByName.put(t.name(), t);
        }
    }

    public McpResponse invoke(String name, Map<String, Object> args) {
        if (name == null || name.isBlank()) {
            return McpResponse.error("Tool name is required");
        }
        McpTool tool = toolByName.get(name);
        if (tool == null) {
            return McpResponse.error("Unknown tool: " + name);
        }
        try {
            Map<String, Object> result = tool.execute(args == null ? Map.of() : args);
            return McpResponse.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("MCP tool '{}' rejected args: {}", name, e.getMessage());
            return McpResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("MCP tool '{}' execution failed", name, e);
            return McpResponse.error("Tool execution failed: " + e.getMessage());
        }
    }
}
