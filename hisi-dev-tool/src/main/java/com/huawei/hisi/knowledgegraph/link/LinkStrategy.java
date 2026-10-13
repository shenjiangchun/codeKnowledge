package com.huawei.hisi.knowledgegraph.link;

import java.util.List;
import java.util.Map;

/**
 * Strategy for linking method nodes across services.
 * Each implementation handles a specific protocol (HTTP, MQ, ...).
 *
 * <p>Strategies are pure computation: they return the EXTERNAL_CALL relations
 * they matched, without writing to Neo4j. The orchestrator ({@link CrossServiceLinker})
 * aggregates all matched relations and persists them in one place, so failures can
 * be surfaced instead of silently swallowed.</p>
 */
public interface LinkStrategy {
    /**
     * Compute cross-service EXTERNAL_CALL relations for the given project paths.
     *
     * @param projectPaths the project paths to link across
     * @return matched relations (each a map with keys: callerId, calleeId, callType,
     *         callLine, bridgeType, targetEndpoint); never null
     */
    List<Map<String, Object>> link(List<String> projectPaths);
}
