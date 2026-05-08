package com.huawei.hisi.knowledgegraph.link;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrates cross-service linking by executing all registered {@link LinkStrategy} beans.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrossServiceLinker {

    private final List<LinkStrategy> strategies;

    /**
     * Execute all link strategies for the given project paths.
     * @param projectPaths the project paths to link across
     */
    public void link(List<String> projectPaths) {
        log.info("[CrossServiceLinker] Linking services for projectPaths: {}", projectPaths);
        for (LinkStrategy strategy : strategies) {
            try {
                log.info("[CrossServiceLinker] Running strategy: {}", strategy.getClass().getSimpleName());
                strategy.link(projectPaths);
            } catch (Exception e) {
                log.warn("[CrossServiceLinker] Strategy {} failed: {}",
                        strategy.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
        log.info("[CrossServiceLinker] Linking completed for projectPaths: {}", projectPaths);
    }
}
