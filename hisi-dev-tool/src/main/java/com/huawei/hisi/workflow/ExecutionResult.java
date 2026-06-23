package com.huawei.hisi.workflow;

import com.huawei.hisi.ram.model.SessionStatus;

import java.util.List;
import java.util.Map;

/**
 * Outcome of a single {@link DagExecutor#run} invocation.
 */
public record ExecutionResult(
        long sessionId,
        SessionStatus status,
        List<String> executedNodes,
        List<String> skippedNodes,
        Map<String, Object> lastNodeOutput
) {
    public ExecutionResult {
        executedNodes = executedNodes == null ? List.of() : List.copyOf(executedNodes);
        skippedNodes = skippedNodes == null ? List.of() : List.copyOf(skippedNodes);
        lastNodeOutput = lastNodeOutput == null ? Map.of() : Map.copyOf(lastNodeOutput);
    }
}
