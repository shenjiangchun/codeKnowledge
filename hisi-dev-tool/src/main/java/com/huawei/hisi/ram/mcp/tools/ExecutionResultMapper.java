package com.huawei.hisi.ram.mcp.tools;

import com.huawei.hisi.workflow.ExecutionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link ExecutionResult} instances to plain JSON-serializable maps
 * for return through the MCP tool interface.
 */
final class ExecutionResultMapper {

    private ExecutionResultMapper() {}

    static Map<String, Object> toMap(ExecutionResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", r.sessionId());
        out.put("status", r.status() == null ? null : r.status().name());
        out.put("executed_nodes", r.executedNodes());
        out.put("skipped_nodes", r.skippedNodes());
        out.put("last_node_output", r.lastNodeOutput());
        return out;
    }
}
