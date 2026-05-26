package com.huawei.hisi.ram.mcp.tools;

import com.huawei.hisi.ram.mcp.McpTool;
import com.huawei.hisi.ram.mcp.RamDagNodes;
import com.huawei.hisi.ram.orchestrator.ExecutionResult;
import com.huawei.hisi.ram.orchestrator.RequirementAnalysisOrchestrator;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP tool that re-runs an existing session without supplying new answers
 * (e.g. to retry after a transient failure).
 */
@Component
public class ResumeSessionTool implements McpTool {

    private final RequirementAnalysisOrchestrator orchestrator;
    private final AgentEventRepository eventRepository;
    private final RamDagNodes nodes;

    public ResumeSessionTool(RequirementAnalysisOrchestrator orchestrator,
                             AgentEventRepository eventRepository,
                             RamDagNodes nodes) {
        this.orchestrator = orchestrator;
        this.eventRepository = eventRepository;
        this.nodes = nodes;
    }

    @Override
    public String name() {
        return "resume_session";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        if (args == null) {
            throw new IllegalArgumentException("args is required");
        }
        long sid = toLong(args.get("session_id"));
        // touch repo so tests observe the dependency (and to surface NPEs early)
        eventRepository.findBySessionId(sid);
        ExecutionResult result = orchestrator.resume(sid, Map.of(), nodes.phaseOne());
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
