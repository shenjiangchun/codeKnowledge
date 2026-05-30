package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.GitRepositoryInfo;
import com.huawei.hisi.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目管理接口控制器
 * M1.3 - 提供项目克隆和分析管理功能
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    /**
     * 获取项目列表
     * GET /api/projects/list
     */
    @GetMapping("/list")
    public ApiResponse<List<String>> listProjects() {
        List<String> projects = projectService.listProjects();
        return ApiResponse.success(projects);
    }

    /**
     * 克隆项目
     * POST /api/projects/clone
     * Body: { "repository": "git@codehub.huawei.com:...", "branch": "master" }
     */
    @PostMapping("/clone")
    public ApiResponse<Map<String, Object>> cloneProject(@RequestBody Map<String, String> request) {
        String repository = request.get("repository");
        String branch = request.getOrDefault("branch", "master");

        Map<String, Object> result = projectService.cloneProject(repository, branch);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ApiResponse.success(result);
        } else {
            return ApiResponse.error(result.getOrDefault("message", "克隆失败").toString());
        }
    }

    /**
     * 获取项目状态
     * GET /api/projects/status?project={project}
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus(@RequestParam String project) {
        Map<String, Object> status = projectService.getStatus(project);
        return ApiResponse.success(status);
    }

    /**
     * 扫描项目目录中的 Git 仓库
     * GET /api/projects/scan-git-repos
     */
    @GetMapping("/scan-git-repos")
    public ApiResponse<List<GitRepositoryInfo>> scanGitRepositories() {
        List<GitRepositoryInfo> repositories = projectService.scanGitRepositories();
        return ApiResponse.success(repositories);
    }
}