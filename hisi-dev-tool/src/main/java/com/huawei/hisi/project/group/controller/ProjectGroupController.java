package com.huawei.hisi.project.group.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.project.group.model.ProjectGroup;
import com.huawei.hisi.project.group.repository.ProjectGroupRepository;
import com.huawei.hisi.project.remote.repository.RemoteProjectRepository;
import com.huawei.hisi.utils.PathUtils;
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
    private final RemoteProjectRepository remoteProjectRepository;

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
     * 同时同步更新 remote_project 表中对应项目的 group_id
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

            // 查询之前的分组项目路径（用于清除旧的 group_id）
            ProjectGroup existing = repository.findByAppId(group.getAppId());
            List<String> oldPaths = existing != null ? existing.getProjectPaths() : List.of();

            // 保存分组到 project_group 表
            repository.save(group);

            // 同步更新 remote_project 表的 group_id
            String groupId = group.getAppId();
            String groupName = group.getAppName();

            // 清除旧路径项目的 group_id（如果路径不再属于该分组）
            for (String oldPath : oldPaths) {
                if (!group.getProjectPaths().contains(oldPath)) {
                    String normalizedOldPath = PathUtils.normalize(oldPath);
                    remoteProjectRepository.clearGroupIdByPath(normalizedOldPath);
                    log.info("[ProjectGroup] Cleared group_id for project path: {}", normalizedOldPath);
                }
            }

            // 设置新路径项目的 group_id 和 groupName
            for (String path : group.getProjectPaths()) {
                String normalizedPath = PathUtils.normalize(path);
                remoteProjectRepository.setGroupIdByPath(normalizedPath, groupId, groupName);
                log.info("[ProjectGroup] Set group_id={} for project path: {}", groupId, normalizedPath);
            }

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