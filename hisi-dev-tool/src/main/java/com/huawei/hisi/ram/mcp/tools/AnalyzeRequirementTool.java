package com.huawei.hisi.ram.mcp.tools;

import com.huawei.hisi.ram.mcp.McpTool;
import com.huawei.hisi.ram.mcp.RamDagNodes;
import com.huawei.hisi.ram.orchestrator.ExecutionResult;
import com.huawei.hisi.ram.orchestrator.RequirementAnalysisOrchestrator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool that starts a new RAM session by invoking the orchestrator's
 * {@code start} method with the four phase-one nodes.
 */
@Component
public class AnalyzeRequirementTool implements McpTool {

    private final RequirementAnalysisOrchestrator orchestrator;
    private final RamDagNodes nodes;

    public AnalyzeRequirementTool(RequirementAnalysisOrchestrator orchestrator,
                                  RamDagNodes nodes) {
        this.orchestrator = orchestrator;
        this.nodes = nodes;
    }

    @Override
    public String name() {
        return "analyze_requirement";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        if (args == null) {
            throw new IllegalArgumentException("args is required");
        }
        Object rawInputObj = args.get("raw_input");
        if (rawInputObj == null || rawInputObj.toString().isBlank()) {
            throw new IllegalArgumentException("raw_input is required");
        }
        String userId = String.valueOf(args.getOrDefault("user_id", "anonymous"));
        String mode = String.valueOf(args.getOrDefault("mode", "interactive"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("userRequirement", rawInputObj.toString());
        input.put("mode", mode);

        // Optional pre-allocated session id (REST controller pre-creates the row so
        // it can register the UUID->id mapping synchronously). When absent, the
        // orchestrator allocates a new session.
        Object sidObj = args.get("session_id");
        ExecutionResult result;
        if (sidObj instanceof Number n) {
            result = orchestrator.start(n.longValue(), input, nodes.phaseOne());
        } else {
            result = orchestrator.start(userId, input, nodes.phaseOne());
        }
        return ExecutionResultMapper.toMap(result);
    }
}
