package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.exception.WorkingDirDirtyException;
import com.huawei.hisi.knowledgegraph.service.IncrementalRefreshService;
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
 */
@RestController
@RequestMapping("/api/knowledge-graph")
@RequiredArgsConstructor
@Slf4j
public class RefreshController {

    private final IncrementalRefreshService refreshService;

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<IncrementalRefreshService.RefreshResult>> refresh(
            @RequestBody RefreshRequest request) {

        if (request.projectPath() == null || request.projectPath().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "projectPath is required"));
        }

        try {
            boolean preview = request.preview() != null && request.preview();
            var result = refreshService.refresh(request.projectPath(), preview);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (NoCheckpointException e) {
            log.warn("Refresh rejected — no checkpoint: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "No checkpoint found: " + e.getMessage()));
        } catch (WorkingDirDirtyException e) {
            log.warn("Refresh rejected — dirty working directory: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(ApiResponse.error(412, "Working directory is dirty: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Refresh failed unexpectedly", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "Refresh failed: " + e.getMessage()));
        }
    }

    public record RefreshRequest(String projectPath, Boolean preview) {}
}
