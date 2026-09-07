package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.service.CrossServiceBuildService;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.utils.PathUtils;
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
            List<String> paths = request.projectPaths().stream()
                    .map(PathUtils::normalize)
                    .filter(p -> !p.isEmpty())
                    .toList();

            // 至少两个项目路径方可跨服务链接
            if (paths.size() < 2) {
                return ApiResponse.error(400, "至少需要 2 个项目路径才能构建跨服务依赖");
            }

            Map<String, Object> result = buildService.build(paths);
            result.put("projectPaths", paths);
            result.put("status", "completed");
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("Cross-service build failed", e);
            return ApiResponse.error(500, "Build failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("Cross-service build failed", e);
            return ApiResponse.error(500, "Build failed: " + e.getMessage());
        }
    }
}
