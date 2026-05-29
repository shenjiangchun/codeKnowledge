package com.huawei.hisi.knowledgegraph.link;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enhances cross-service linking using OpenAPI/Swagger specification files.
 *
 * <p>Current implementation: placeholder. Full OpenAPI spec parsing will be added
 * when service directory metadata is available via the linking pipeline.
 */
@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class OpenApiLinkStrategy implements LinkStrategy {

    @Override
    public void link(List<String> projectPaths) {
        log.info("[OpenApiLink] OpenAPI spec scanning for projectPaths: {} — not yet active (HTTP strategy handles URL matching)", projectPaths);
    }
}
