package com.huawei.hisi.service;

import com.huawei.hisi.model.ClaudeMessage;
import com.huawei.hisi.model.ClaudeSession;

import java.util.List;

/**
 * 会话服务接口
 */
public interface SessionService {

    /**
     * 创建新会话
     * @param scene 场景标识
     * @param title 会话标题
     * @param metadata 元数据
     * @return 会话对象
     */
    ClaudeSession createSession(String scene, String title, String metadata);

    /**
     * 创建新会话（带工作目录）
     * @param scene 场景标识
     * @param title 会话标题
     * @param metadata 元数据
     * @param workingDirectory 工作目录
     * @return 会话对象
     */
    ClaudeSession createSession(String scene, String title, String metadata, String workingDirectory);

    /**
     * 使用指定的 ID 创建新会话
     * @param sessionId 指定的会话 ID
     * @param scene 场景标识
     * @param title 会话标题
     * @param metadata 元数据
     * @return 会话对象
     */
    ClaudeSession createSessionWithId(String sessionId, String scene, String title, String metadata);

    /**
     * 使用指定的 ID 创建新会话（带工作目录）
     * @param sessionId 指定的会话 ID
     * @param scene 场景标识
     * @param title 会话标题
     * @param metadata 元数据
     * @param workingDirectory 工作目录
     * @return 会话对象
     */
    ClaudeSession createSessionWithId(String sessionId, String scene, String title, String metadata, String workingDirectory);

    /**
     * 获取会话详情
     * @param sessionId 会话ID
     * @return 会话对象（包含消息列表）
     */
    ClaudeSession getSession(String sessionId);

    /**
     * 获取会话列表
     * @param status 状态过滤（可选）
     * @param page 页码
     * @param pageSize 每页大小
     * @return 会话列表
     */
    List<ClaudeSession> getSessions(String status, int page, int pageSize);

    /**
     * 获取会话总数
     * @param status 状态过滤（可选）
     * @return 总数
     */
    int getSessionCount(String status);

    /**
     * 更新会话标题
     * @param sessionId 会话ID
     * @param title 新标题
     */
    void updateTitle(String sessionId, String title);

    /**
     * 更新 Claude 会话码（用于恢复会话）
     * @param sessionId 会话ID
     * @param claudeSessionCode Claude 会话码
     */
    void updateClaudeSessionCode(String sessionId, String claudeSessionCode);

    /**
     * 归档会话
     * @param sessionId 会话ID
     */
    void archiveSession(String sessionId);

    /**
     * 删除会话
     * @param sessionId 会话ID
     */
    void deleteSession(String sessionId);

    /**
     * 清除会话消息历史
     * @param sessionId 会话ID
     */
    void clearMessages(String sessionId);

    /**
     * 添加消息
     * @param sessionId 会话ID
     * @param role 角色（user/assistant）
     * @param content 消息内容
     * @return 消息对象
     */
    ClaudeMessage addMessage(String sessionId, String role, String content);

    /**
     * 获取会话消息列表
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<ClaudeMessage> getMessages(String sessionId);

    /**
     * 导出会话
     * @param sessionId 会话ID
     * @param format 格式（markdown/json）
     * @return 导出内容
     */
    String exportSession(String sessionId, String format);

    /**
     * 检查会话是否存在
     * @param sessionId 会话ID
     * @return 是否存在
     */
    boolean sessionExists(String sessionId);
}
