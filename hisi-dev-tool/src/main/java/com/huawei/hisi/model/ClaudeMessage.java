package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Claude 会话消息实体
 */
@Data
public class ClaudeMessage {
    /** 消息ID */
    private Long id;

    /** 所属会话ID */
    private String sessionId;

    /** 角色: user, assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
