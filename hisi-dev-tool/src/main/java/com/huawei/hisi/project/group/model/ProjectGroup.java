package com.huawei.hisi.project.group.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目分组实体
 * 将多个项目归到一个 appId 下
 */
public class ProjectGroup {
    private Long id;
    private String appId;
    private String appName;
    private List<String> projectPaths;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public List<String> getProjectPaths() { return projectPaths; }
    public void setProjectPaths(List<String> projectPaths) { this.projectPaths = projectPaths; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}