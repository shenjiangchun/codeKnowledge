package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.link.CrossServiceLinker;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrossServiceBuildService {

    private final Neo4jMethodNodeRepository methodRepo;
    private final IncrementalKnowledgeGraphBuilder incrementalBuilder;
    private final CrossServiceLinker linker;

    /**
     * Build cross-service links between the given projects.
     *
     * <p>Order is deliberately "compute → delete → write": matched relations are
     * computed in memory first, so a link failure can be surfaced (and old edges
     * retained) rather than silently dropping data.</p>
     *
     * @param projectPaths the project paths to link across
     * @return a structured result map with per-strategy matched counts and the
     *         number of deleted old edges
     */
    public Map<String, Object> build(List<String> projectPaths) {
        // 1. Validate all projects have a knowledge graph
        for (String path : projectPaths) {
            if (methodRepo.countByProjectPath(path) == 0) {
                throw new IllegalArgumentException("Project has no knowledge graph: " + path);
            }
        }

        // 2. Incremental refresh each project — refresh failure aborts (stale node IDs
        //    would produce dangling EXTERNAL_CALL edges after the old ones are deleted).
        for (String path : projectPaths) {
            IncrementalKnowledgeGraphBuilder.RefreshResult result =
                    incrementalBuilder.incrementalRefresh(path);
            if (!result.success()) {
                throw new IllegalStateException("Incremental refresh failed for: " + path);
            }
        }

        // 3. Compute matched relations in memory (no write yet).
        Map<String, List<Map<String, Object>>> matchedByStrategy = linker.link(projectPaths);

        // 4. Clean old EXTERNAL_CALL relations between these projects.
        long deleted = methodRepo.deleteExternalCallsBetween(projectPaths);
        log.info("Deleted {} existing EXTERNAL_CALL relations", deleted);

        // 5. Persist the newly matched relations.
        List<Map<String, Object>> allRelations = new ArrayList<>();
        matchedByStrategy.values().forEach(allRelations::addAll);
        if (!allRelations.isEmpty()) {
            methodRepo.createCallRelations(allRelations);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedExternalCalls", deleted);
        Map<String, Integer> strategyCounts = new LinkedHashMap<>();
        matchedByStrategy.forEach((name, rels) -> strategyCounts.put(name, rels.size()));
        result.put("strategyCounts", strategyCounts);
        result.put("totalCreated", allRelations.size());
        log.info("Cross-service build completed for {} projects: deleted={}, created={}, strategies={}",
                projectPaths.size(), deleted, allRelations.size(), strategyCounts);
        return result;
    }
}
