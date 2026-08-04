package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 项目信息 DTO
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
public class ProjectInfo {
    /**
     * 项目名称
     */
    private String name;

    /**
     * Git 仓库 URL
     */
    private String gitUrl;

    /**
     * 分支名
     */
    private String branch;

    /**
     * 本地路径
     */
    private String localPath;

    /**
     * 最后分析时间
     */
    private LocalDateTime lastAnalyzed;

    /**
     * 状态：idle, cloning, analyzing, completed, error
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}