package com.huawei.hisi.ram.orchestrator;

import java.util.Map;

/**
 * A single executable step in the RAM DAG (clarify / impact / implement / verify / ...).
 */
public interface DagNode {

    /** Logical node name used for checkpoint lookup, e.g. {@code "clarify"}. */
    String name();

    /** Agent id used for {@code AgentRegistry} lookup. */
    String agentId();

    /**
     * Execute this node with the supplied input and return its output.
     *
     * @throws ClarifyRequiredException when the node cannot produce its output
     *                                  without additional user clarification
     */
    Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException;
}
