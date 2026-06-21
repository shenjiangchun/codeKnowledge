package com.huawei.hisi.project.namegroup.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.project.namegroup.model.ProjectNameGroup;
import com.huawei.hisi.project.namegroup.repository.ProjectNameGroupRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目名称分组 Controller
 * Task 74: 按项目名称前缀/模式分组
 */
@Slf4j
@RestController
@RequestMapping("/api/project-name-group")
@RequiredArgsConstructor
public class ProjectNameGroupController {

    private final ProjectNameGroupRepository repository;

    /**
     * 获取所有分组
     */
    @GetMapping
    public ApiResponse<List<ProjectNameGroup>> getAllGroups() {
        List<ProjectNameGroup> groups = repository.findAll();
        return ApiResponse.success(groups);
    }

    /**
     * 获取单个分组
     */
    @GetMapping("/{groupName}")
    public ApiResponse<ProjectNameGroup> getGroup(@PathVariable String groupName) {
        ProjectNameGroup group = repository.findByGroupName(groupName);
        if (group == null) {
            return ApiResponse.error("分组不存在: " + groupName);
        }
        return ApiResponse.success(group);
    }

    /**
     * 创建或更新分组
     */
    @PostMapping
    public ApiResponse<ProjectNameGroup> saveGroup(@RequestBody ProjectNameGroup group) {
        if (group.getGroupName() == null || group.getGroupName().isBlank()) {
            return ApiResponse.error("分组名称不能为空");
        }
        if (group.getGroupPattern() == null || group.getGroupPattern().isBlank()) {
            return ApiResponse.error("分组模式不能为空");
        }
        repository.save(group);
        ProjectNameGroup saved = repository.findByGroupName(group.getGroupName());
        return ApiResponse.success(saved);
    }

    /**
     * 删除分组
     */
    @DeleteMapping("/{groupName}")
    public ApiResponse<Void> deleteGroup(@PathVariable String groupName) {
        repository.deleteByGroupName(groupName);
        return ApiResponse.success(null);
    }
}