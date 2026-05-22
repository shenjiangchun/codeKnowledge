package com.huawei.hisi.ram.mcp.tools;

import com.huawei.hisi.ram.hitl.HitlQueue;
import com.huawei.hisi.ram.mcp.McpTool;
import com.huawei.hisi.ram.mcp.RamDagNodes;
import com.huawei.hisi.ram.orchestrator.ExecutionResult;
import com.huawei.hisi.ram.orchestrator.RequirementAnalysisOrchestrator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP tool that submits a clarification batch and resumes the session.
 */
@Component
public class SubmitClarificationTool implements McpTool {

    private final HitlQueue hitlQueue;
    private final RequirementAnalysisOrchestrator orchestrator;
    private final RamDagNodes nodes;

    public SubmitClarificationTool(HitlQueue hitlQueue,
                                   RequirementAnalysisOrchestrator orchestrator,
                                   RamDagNodes nodes) {
        this.hitlQueue = hitlQueue;
        this.orchestrator = orchestrator;
        this.nodes = nodes;
    }

    @Override
    public String name() {
        return "submit_clarification";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> args) {
        if (args == null) {
            throw new IllegalArgumentException("args is required");
        }
        long sid = toLong(args.get("session_id"));
        Object answersRaw = args.get("answers");
        Map<String, Object> answers = answersRaw instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        hitlQueue.submitAnswers(sid, answers);
        ExecutionResult result = orchestrator.resume(sid, answers, nodes.phaseOne());
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
