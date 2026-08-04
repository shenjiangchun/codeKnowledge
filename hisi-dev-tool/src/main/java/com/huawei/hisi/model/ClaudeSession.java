package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Claude 会话实体
 */
@Data
public class ClaudeSession {
    /** 会话ID (UUID) */
    private String id;

    /** 会话标题 */
    private String title;

    /** 场景标识: log-analysis, code-analysis, trace-analysis, impact-analysis, free-chat */
    private String scene;

    /** 会话状态: active, archived */
    private String status;

    /** 元数据 (JSON字符串) */
    private String metadata;

    /** 工作目录（项目目录） */
    private String workingDirectory;

    /** Claude 会话码（用于恢复会话，格式如：claude --resume xxx） */
    private String claudeSessionCode;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
