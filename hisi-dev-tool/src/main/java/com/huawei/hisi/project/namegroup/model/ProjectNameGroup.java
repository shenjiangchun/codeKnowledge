package com.huawei.hisi.project.namegroup.model;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 项目名称分组实体
 * Task 74: 按项目名称前缀/模式分组
 */
@Data
public class ProjectNameGroup {

    private Long id;
    private String groupName;      // 分组名称，如 "HiSi DevTool 系列"
    private String groupPattern;   // 分组模式，如 "hisi-*"
    private List<String> projectNames;  // 分组下的项目名称列表
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}