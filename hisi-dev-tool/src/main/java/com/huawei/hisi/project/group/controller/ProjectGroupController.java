package com.huawei.hisi.project.group.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.project.group.model.ProjectGroup;
import com.huawei.hisi.project.group.repository.ProjectGroupRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目分组 Controller
 * 支持按 appId 分组多个项目
 */
@Slf4j
@RestController
@RequestMapping("/api/project-group")
@RequiredArgsConstructor
@Validated
public class ProjectGroupController {

    private final ProjectGroupRepository repository;

    /**
     * 获取所有分组
     */
    @GetMapping
    public ApiResponse<List<ProjectGroup>> getAllGroups() {
        try {
            List<ProjectGroup> groups = repository.findAll();
            return ApiResponse.success(groups);
        } catch (Exception e) {
            log.error("获取分组列表失败", e);
            return ApiResponse.error("获取分组列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取单个分组
     */
    @GetMapping("/{appId}")
    public ApiResponse<ProjectGroup> getGroup(@PathVariable String appId) {
        try {
            ProjectGroup group = repository.findByAppId(appId);
            if (group == null) {
                return ApiResponse.error(404, "分组不存在");
            }
            return ApiResponse.success(group);
        } catch (Exception e) {
            log.error("获取分组失败: appId={}", appId, e);
            return ApiResponse.error("获取分组失败: " + e.getMessage());
        }
    }

    /**
     * 创建或更新分组
     */
    @PostMapping
    public ApiResponse<ProjectGroup> saveGroup(@Valid @RequestBody ProjectGroup group) {
        try {
            if (group.getAppId() == null || group.getAppId().isBlank()) {
                return ApiResponse.error(400, "appId 不能为空");
            }
            if (group.getAppName() == null || group.getAppName().isBlank()) {
                return ApiResponse.error(400, "appName 不能为空");
            }
            if (group.getProjectPaths() == null || group.getProjectPaths().isEmpty()) {
                return ApiResponse.error(400, "projectPaths 不能为空");
            }

            repository.save(group);
            ProjectGroup saved = repository.findByAppId(group.getAppId());
            return ApiResponse.success(saved);
        } catch (Exception e) {
            log.error("保存分组失败", e);
            return ApiResponse.error("保存分组失败: " + e.getMessage());
        }
    }

    /**
     * 删除分组
     */
    @DeleteMapping("/{appId}")
    public ApiResponse<String> deleteGroup(@PathVariable String appId) {
        try {
            repository.deleteByAppId(appId);
            return ApiResponse.success("分组已删除");
        } catch (Exception e) {
            log.error("删除分组失败: appId={}", appId, e);
            return ApiResponse.error("删除分组失败: " + e.getMessage());
        }
    }

    /**
     * 查询项目路径所属的分组
     */
    @GetMapping("/by-path")
    public ApiResponse<ProjectGroup> getGroupByPath(@RequestParam String path) {
        try {
            ProjectGroup group = repository.findContainingPath(path);
            if (group == null) {
                return ApiResponse.error(404, "该项目不属于任何分组");
            }
            return ApiResponse.success(group);
        } catch (Exception e) {
            log.error("查询项目分组失败: path={}", path, e);
            return ApiResponse.error("查询项目分组失败: " + e.getMessage());
        }
    }
}