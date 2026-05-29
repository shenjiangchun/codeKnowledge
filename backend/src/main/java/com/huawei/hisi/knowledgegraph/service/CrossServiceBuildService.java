package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.link.CrossServiceLinker;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrossServiceBuildService {

    private final Neo4jMethodNodeRepository methodRepo;
    private final IncrementalRefreshService refreshService;
    private final CrossServiceLinker linker;

    public void build(List<String> projectPaths) {
        // 1. Validate all projects have a knowledge graph
        for (String path : projectPaths) {
            if (methodRepo.countByProjectPath(path) == 0) {
                throw new IllegalArgumentException("Project has no knowledge graph: " + path);
            }
        }

        // 2. Incremental refresh each project
        for (String path : projectPaths) {
            try {
                refreshService.refresh(path);
            } catch (Exception e) {
                log.warn("Refresh skipped for {}: {}", path, e.getMessage());
            }
        }

        // 3. Clean old EXTERNAL_CALL relations between these projects
        long deleted = methodRepo.deleteExternalCallsBetween(projectPaths);
        log.info("Deleted {} existing EXTERNAL_CALL relations", deleted);

        // 4. Rebuild cross-service links
        linker.link(projectPaths);
        log.info("Cross-service build completed for {} projects", projectPaths.size());
    }
}
