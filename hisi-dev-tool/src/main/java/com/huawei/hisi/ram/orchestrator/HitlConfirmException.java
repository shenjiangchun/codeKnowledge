package com.huawei.hisi.ram.orchestrator;

import java.util.Map;

/**
 * Thrown by {@link DagExecutor} after a node completes when user confirmation
 * is required before proceeding to the next node. Mirrors the
 * {@link ClarifyRequiredException} pattern.
 *
 * <p>Carries the completed node's name and output so the frontend can display
 * them for user review.
 */
public class HitlConfirmException extends RuntimeException {

    private final String nodeName;
    private final Map<String, Object> nodeOutput;

    public HitlConfirmException(String nodeName, Map<String, Object> nodeOutput) {
        super("HITL confirmation required after node: " + nodeName);
        this.nodeName = nodeName;
        this.nodeOutput = nodeOutput == null ? Map.of() : Map.copyOf(nodeOutput);
    }

    public String getNodeName() {
        return nodeName;
    }

    public Map<String, Object> getNodeOutput() {
        return nodeOutput;
    }
}
