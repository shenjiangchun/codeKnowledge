package com.huawei.hisi.ram.mcp.tools;

import com.huawei.hisi.ram.mcp.McpTool;
import com.huawei.hisi.ram.mcp.RamDagNodes;
import com.huawei.hisi.workflow.ExecutionResult;
import com.huawei.hisi.ram.orchestrator.RequirementAnalysisOrchestrator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP tool that submits a node-confirmation (approve / reject / edit) and
 * resumes the DAG execution. Mirrors {@link SubmitClarificationTool}.
 */
@Component
public class SubmitConfirmationTool implements McpTool {

    private final RequirementAnalysisOrchestrator orchestrator;
    private final RamDagNodes nodes;

    public SubmitConfirmationTool(RequirementAnalysisOrchestrator orchestrator,
                                   RamDagNodes nodes) {
        this.orchestrator = orchestrator;
        this.nodes = nodes;
    }

    @Override
    public String name() {
        return "submit_confirmation";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> args) {
        if (args == null) {
            throw new IllegalArgumentException("args is required");
        }
        long sid = toLong(args.get("session_id"));
        String nodeName = (String) args.get("node_name");
        String action = (String) args.getOrDefault("action", "approve");
        String feedback = (String) args.get("feedback");
        Map<String, Object> editedOutput = args.get("edited_output") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : null;

        ExecutionResult result = orchestrator.confirmAndResume(
                sid, nodeName, action, feedback, editedOutput, nodes.phaseOne());
        return ExecutionResultMapper.toMap(result);
    }

    private static long toLong(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("session_id is required");
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("session_id must be a number: " + v);
        }
    }
}
