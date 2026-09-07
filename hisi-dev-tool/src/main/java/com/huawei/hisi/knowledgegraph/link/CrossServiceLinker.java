package com.huawei.hisi.knowledgegraph.link;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates cross-service linking by executing all registered {@link LinkStrategy} beans.
 *
 * <p>Aggregates the relations matched by each strategy (pure computation, no write),
 * so the caller can persist them and surface per-strategy counts or failures
 * instead of swallowing exceptions.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrossServiceLinker {

    private final List<LinkStrategy> strategies;

    /**
     * Execute all link strategies and aggregate their matched relations.
     *
     * @param projectPaths the project paths to link across
     * @return a map of {@code strategyName -> List<relation>} with the relations
     *         each strategy matched; empty maps are omitted
     */
    public Map<String, List<Map<String, Object>>> link(List<String> projectPaths) {
        log.info("[CrossServiceLinker] Linking services for projectPaths: {}", projectPaths);
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (LinkStrategy strategy : strategies) {
            String name = strategy.getClass().getSimpleName();
            try {
                log.info("[CrossServiceLinker] Running strategy: {}", name);
                List<Map<String, Object>> relations = strategy.link(projectPaths);
                if (relations != null && !relations.isEmpty()) {
                    result.merge(name, relations, (existing, incoming) -> {
                        List<Map<String, Object>> merged = new ArrayList<>(existing);
                        merged.addAll(incoming);
                        return merged;
                    });
                }
            } catch (Exception e) {
                // Surface the failure to the caller instead of silently swallowing it.
                log.error("[CrossServiceLinker] Strategy {} failed: {}", name, e.getMessage(), e);
                throw new IllegalStateException("Cross-service strategy " + name + " failed: " + e.getMessage(), e);
            }
        }
        log.info("[CrossServiceLinker] Linking completed for projectPaths: {} ({} strategies matched)",
                projectPaths, result.size());
        return result;
    }
}
