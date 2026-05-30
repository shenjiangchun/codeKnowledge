package com.huawei.hisi.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Claude 工作空间会话实体
 * 存储系统会话 ID 与 Claude CLI session_id 的映射关系
 */
@Data
@NoArgsConstructor
public class ClaudeWorkspaceSession {

    /**
     * 系统会话 ID (主键)
     */
    private String id;

    /**
     * Claude CLI session_id (核心关联)
     * 用于与 Claude CLI 进行会话恢复
     */
    private String claudeSessionId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 场景类型
     * 例如: log-analysis, code-analysis, trace-analysis, impact-analysis, free-chat
     */
    private String scene;

    /**
     * 会话状态
     * active: 活跃, archived: 已归档
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 工作目录
     * 会话关联的项目目录路径
     */
    private String workingDirectory;

    /**
     * 初始提示词
     * 创建会话时的初始提示内容
     */
    private String initialPrompt;
}