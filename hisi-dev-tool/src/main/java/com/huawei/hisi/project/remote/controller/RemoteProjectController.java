package com.huawei.hisi.project.remote.controller;

import com.huawei.hisi.config.DataSourceConfig;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.project.group.repository.ProjectGroupRepository;
import com.huawei.hisi.project.group.model.ProjectGroup;
import com.huawei.hisi.project.remote.model.RemoteProject;
import com.huawei.hisi.project.remote.service.RemoteProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/remote-projects")
@RequiredArgsConstructor
public class RemoteProjectController {

    private final RemoteProjectService service;
    private final ProjectGroupRepository projectGroupRepository;

    // Enhanced request/response records with auth type support
    public record CreateRequest(
        String name, String gitUrl, String username, String password,
        String authType, String sshKeyPath, String token, String branch, Long groupId
    ) {}

    public record UpdateRequest(
        String name, String gitUrl, String username, String password,
        String authType, String sshKeyPath, String token, String branch, Long groupId
    ) {}

    public record ProjectResponse(
        Long id, String name, String gitUrl, String username, String branch,
        String localPath, String cloneStatus, String cloneError, Long lastSyncAt,
        String authType, String sshKeyPath, Long groupId, String groupName
    ) {}

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list() {
        List<ProjectResponse> responses = service.list().stream()
            .map(this::toResponse)
            .toList();
        return ApiResponse.success(responses);
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@RequestBody CreateRequest req) {
        long id = service.create(
            req.name(), req.gitUrl(), req.username(), req.password(),
            req.authType(), req.sshKeyPath(), req.token(), req.branch(), req.groupId()
        );
        return ApiResponse.success(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ApiResponse<String> update(@PathVariable long id, @RequestBody UpdateRequest req) {
        service.update(
            id, req.name(), req.gitUrl(), req.username(), req.password(),
            req.authType(), req.sshKeyPath(), req.token(), req.branch(), req.groupId()
        );
        return ApiResponse.success("Updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.success("Deleted");
    }

    @PostMapping("/{id}/clone")
    public ApiResponse<String> cloneProject(@PathVariable long id) {
        CompletableFuture.runAsync(() -> service.cloneProject(id))
            .exceptionally(ex -> {
                log.error("[Clone] Unhandled exception for project id={}: {}", id, ex.getMessage(), ex);
                return null;
            });
        return ApiResponse.success("Clone started");
    }

    @PostMapping("/{id}/pull")
    public ApiResponse<String> pullProject(@PathVariable long id) {
        CompletableFuture.runAsync(() -> service.pullProject(id))
            .exceptionally(ex -> {
                log.error("[Pull] Unhandled exception for project id={}: {}", id, ex.getMessage(), ex);
                return null;
            });
        return ApiResponse.success("Pull started");
    }

    private ProjectResponse toResponse(RemoteProject p) {
        // Use stored fullPath if available (immutable, set at clone time)
        // Otherwise fallback to dynamic calculation for legacy projects
        String fullPath = p.getFullPath();
        if (fullPath == null || fullPath.isBlank()) {
            // Fallback: dynamically calculate path for legacy projects without fullPath
            String baseDir = DataSourceConfig.PROJECT_DIR;
            if (baseDir == null || baseDir.isBlank()) {
                baseDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".hisi-devtool").toString();
            }
            fullPath = com.huawei.hisi.utils.PathUtils.normalize(
                java.nio.file.Paths.get(baseDir, "remote-repos", p.getLocalPath()).toString()
            );
        }
        // Convert seconds to milliseconds for frontend
        Long lastSyncAtMs = p.getLastSyncAt() != null ? p.getLastSyncAt() * 1000 : null;
        // Get group name if groupId exists
        String groupName = null;
        if (p.getGroupId() != null) {
            ProjectGroup group = projectGroupRepository.findById(p.getGroupId());
            if (group != null) {
                groupName = group.getAppName();
            }
        }
        return new ProjectResponse(
            p.getId(), p.getName(), p.getGitUrl(), p.getUsername(),
            p.getBranch(), fullPath, p.getCloneStatus(), p.getCloneError(), lastSyncAtMs,
            p.getAuthType(), p.getSshKeyPath(), p.getGroupId(), groupName
        );
    }
}
