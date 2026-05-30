package com.huawei.hisi.knowledgegraph.link;

import java.util.List;

/**
 * Strategy for linking method nodes across services.
 * Each implementation handles a specific protocol (HTTP, MQ, OpenAPI, gRPC).
 */
public interface LinkStrategy {
    /**
     * Create EXTERNAL_CALL edges between services sharing the given project paths.
     * @param projectPaths the project paths to link across
     */
    void link(List<String> projectPaths);
}
