package com.huawei.hisi.project.remote.controller;

import com.huawei.hisi.model.ApiResponse;
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

    public record CreateRequest(String name, String gitUrl, String username, String password, String branch) {}
    public record UpdateRequest(String name, String gitUrl, String username, String password, String branch) {}
    public record ProjectResponse(Long id, String name, String gitUrl, String username, String branch,
                                  String localPath, String cloneStatus, Long lastSyncAt) {}

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list() {
        List<ProjectResponse> responses = service.list().stream()
            .map(RemoteProjectController::toResponse)
            .toList();
        return ApiResponse.success(responses);
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@RequestBody CreateRequest req) {
        long id = service.create(req.name(), req.gitUrl(), req.username(), req.password(), req.branch());
        return ApiResponse.success(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ApiResponse<String> update(@PathVariable long id, @RequestBody UpdateRequest req) {
        service.update(id, req.name(), req.gitUrl(), req.username(), req.password(), req.branch());
        return ApiResponse.success("Updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.success("Deleted");
    }

    @PostMapping("/{id}/clone")
    public ApiResponse<String> cloneProject(@PathVariable long id) {
        CompletableFuture.runAsync(() -> service.cloneProject(id));
        return ApiResponse.success("Clone started");
    }

    @PostMapping("/{id}/pull")
    public ApiResponse<String> pullProject(@PathVariable long id) {
        CompletableFuture.runAsync(() -> service.pullProject(id));
        return ApiResponse.success("Pull started");
    }

    private static ProjectResponse toResponse(RemoteProject p) {
        String fullPath = java.nio.file.Paths.get(System.getProperty("user.dir"), "remote-repos", p.getLocalPath()).toString();
        // Convert seconds to milliseconds for frontend
        Long lastSyncAtMs = p.getLastSyncAt() != null ? p.getLastSyncAt() * 1000 : null;
        return new ProjectResponse(
            p.getId(), p.getName(), p.getGitUrl(), p.getUsername(),
            p.getBranch(), fullPath, p.getCloneStatus(), lastSyncAtMs
        );
    }
}
