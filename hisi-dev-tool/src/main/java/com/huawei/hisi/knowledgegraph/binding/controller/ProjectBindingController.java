package com.huawei.hisi.knowledgegraph.binding.controller;

import com.huawei.hisi.knowledgegraph.binding.model.ProjectBinding;
import com.huawei.hisi.knowledgegraph.binding.service.ProjectBindingService;
import com.huawei.hisi.knowledgegraph.service.FrontendGraphOrchestrator;
import com.huawei.hisi.model.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前后端绑定关系 CRUD 端点 + 「构建前后端完整图谱」端点。
 */
@RestController
@RequestMapping("/api/knowledge-graph/binding")
@RequiredArgsConstructor
@Slf4j
public class ProjectBindingController {

    private final ProjectBindingService bindingService;
    private final FrontendGraphOrchestrator frontendGraphOrchestrator;

    public record CreateRequest(
            @NotBlank String backendPath,
            @NotBlank String frontendPath) {}

    @GetMapping
    public ApiResponse<List<ProjectBinding>> list() {
        try {
            return ApiResponse.success(bindingService.list());
        } catch (Exception e) {
            log.error("[ProjectBinding] 查询绑定列表失败", e);
            return ApiResponse.error("查询绑定列表失败");
        }
    }

    @PostMapping
    public ApiResponse<ProjectBinding> create(@Valid @RequestBody CreateRequest request) {
        try {
            ProjectBinding saved = bindingService.create(request.backendPath(), request.frontendPath());
            return ApiResponse.success(saved);
        } catch (DuplicateKeyException e) {
            log.warn("[ProjectBinding] 重复绑定被拒绝: backend={}, frontend={}",
                    request.backendPath(), request.frontendPath());
            return ApiResponse.error(409, "该前后端目录已绑定");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[ProjectBinding] 创建绑定失败", e);
            return ApiResponse.error("创建绑定失败");
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            boolean deleted = bindingService.delete(id);
            if (!deleted) {
                return ApiResponse.error(404, "绑定不存在: id=" + id);
            }
            return ApiResponse.success(Map.of("id", id, "deleted", true));
        } catch (Exception e) {
            log.error("[ProjectBinding] 删除绑定失败 id={}", id, e);
            return ApiResponse.error("删除绑定失败");
        }
    }

    @PostMapping("/build")
    public ApiResponse<Map<String, Object>> build(@Valid @RequestBody CreateRequest request) {
        try {
            // 1. 建立（或复用）绑定
            try {
                bindingService.create(request.backendPath(), request.frontendPath());
            } catch (DuplicateKeyException e) {
                log.info("[ProjectBinding] 绑定已存在，复用: backend={}, frontend={}",
                        request.backendPath(), request.frontendPath());
            }

            // 2. 前端实体化 + 跨层链接（向量化在编排器内一并执行）
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("backendPath", request.backendPath());
            result.put("frontendPath", request.frontendPath());
            result.putAll(frontendGraphOrchestrator.run(request.backendPath(), request.frontendPath()));
            result.put("status", "completed");
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[ProjectBinding] 构建前后端完整图谱失败", e);
            return ApiResponse.error(500, "构建前后端完整图谱失败: " + e.getMessage());
        }
    }
}
