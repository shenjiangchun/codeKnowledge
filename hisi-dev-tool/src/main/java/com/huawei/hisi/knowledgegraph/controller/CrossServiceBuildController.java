package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.service.CrossServiceBuildService;
import com.huawei.hisi.model.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-graph/cross-service")
@RequiredArgsConstructor
@Slf4j
public class CrossServiceBuildController {

    private final CrossServiceBuildService buildService;

    public record BuildRequest(@NotEmpty List<String> projectPaths) {}

    @PostMapping("/build")
    public ApiResponse<Map<String, Object>> build(@Valid @RequestBody BuildRequest request) {
        try {
            buildService.build(request.projectPaths());
            return ApiResponse.success(Map.of(
                    "projectPaths", request.projectPaths(),
                    "status", "completed"
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Cross-service build failed", e);
            return ApiResponse.error(500, "Build failed: " + e.getMessage());
        }
    }
}
