package com.huawei.hisi.service;

import com.huawei.hisi.model.ClaudeWorkspaceSession;

import java.util.List;

/**
 * 工作空间会话服务接口
 * 管理系统会话与 Claude CLI session_id 的映射关系
 */
public interface WorkspaceSessionService {

    /**
     * 获取会话列表
     *
     * @param status 会话状态过滤 (可选, null 表示获取全部)
     * @return 会话列表
     */
    List<ClaudeWorkspaceSession> getSessions(String status);

    /**
     * 获取单个会话
     *
     * @param id 会话 ID
     * @return 会话实体, 不存在返回 null
     */
    ClaudeWorkspaceSession getSession(String id);

    /**
     * 创建新会话
     *
     * @param scene 场景类型
     * @param initialPrompt 初始提示词
     * @param workingDirectory 工作目录
     * @return 创建的会话实体
     */
    ClaudeWorkspaceSession createSession(String scene, String initialPrompt, String workingDirectory);

    /**
     * 更新会话信息
     *
     * @param id 会话 ID
     * @param title 会话标题
     * @param status 会话状态
     * @return 更新后的会话实体
     */
    ClaudeWorkspaceSession updateSession(String id, String title, String status);

    /**
     * 删除会话
     *
     * @param id 会话 ID
     */
    void deleteSession(String id);

    /**
     * 归档会话
     *
     * @param id 会话 ID
     * @return 归档后的会话实体
     */
    ClaudeWorkspaceSession archiveSession(String id);

    /**
     * 绑定 Claude CLI session_id
     *
     * @param id 系统会话 ID
     * @param claudeSessionId Claude CLI session_id
     * @return 更新后的会话实体
     */
    ClaudeWorkspaceSession bindClaudeSession(String id, String claudeSessionId);
}