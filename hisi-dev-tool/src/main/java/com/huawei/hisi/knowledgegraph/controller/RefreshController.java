package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.service.IncrementalKnowledgeGraphBuilder;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for triggering incremental knowledge-graph refresh.
 *
 * <p>Uses {@link IncrementalKnowledgeGraphBuilder} which composes
 * {@link com.huawei.hisi.knowledgegraph.service.KnowledgeGraphBuilder}
 * to ensure graph state equivalence with a full build at the same commit.
 */
@RestController
@RequestMapping("/api/knowledge-graph")
@RequiredArgsConstructor
@Slf4j
public class RefreshController {

    private final IncrementalKnowledgeGraphBuilder incrementalBuilder;

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<IncrementalKnowledgeGraphBuilder.RefreshResult>> refresh(
            @RequestBody RefreshRequest request) {

        if (request.projectPath() == null || request.projectPath().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "projectPath is required"));
        }

        try {
            var result = incrementalBuilder.incrementalRefresh(request.projectPath());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (NoCheckpointException e) {
            log.warn("Refresh rejected — no checkpoint: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "No checkpoint found. Run full generation first: "
                            + e.getMessage()));
        } catch (Exception e) {
            log.error("Refresh failed unexpectedly", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "Refresh failed: " + e.getMessage()));
        }
    }

    public record RefreshRequest(String projectPath, Boolean preview) {}
}
