package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.ClaudeWorkspaceSession;
import com.huawei.hisi.service.WorkspaceSessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作空间会话控制器
 * 提供会话管理的 REST API
 */
@RestController
@RequestMapping("/api/workspace-sessions")
@RequiredArgsConstructor
public class WorkspaceSessionController {

    private final WorkspaceSessionService workspaceSessionService;

    /**
     * 获取会话列表
     * GET /api/workspace-sessions
     *
     * @param status 会话状态过滤 (可选)
     * @return 会话列表
     */
    @GetMapping
    public ApiResponse<List<ClaudeWorkspaceSession>> getSessions(
            @RequestParam(required = false) String status) {
        List<ClaudeWorkspaceSession> sessions = workspaceSessionService.getSessions(status);
        return ApiResponse.success(sessions);
    }

    /**
     * 获取单个会话
     * GET /api/workspace-sessions/{id}
     *
     * @param id 会话 ID
     * @return 会话详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ClaudeWorkspaceSession> getSession(@PathVariable String id) {
        ClaudeWorkspaceSession session = workspaceSessionService.getSession(id);
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        return ApiResponse.success(session);
    }

    /**
     * 创建新会话
     * POST /api/workspace-sessions
     *
     * @param request 创建请求
     * @return 创建的会话
     */
    @PostMapping
    public ApiResponse<ClaudeWorkspaceSession> createSession(@RequestBody CreateSessionRequest request) {
        ClaudeWorkspaceSession session = workspaceSessionService.createSession(
                request.getScene(),
                request.getInitialPrompt(),
                request.getWorkingDirectory()
        );
        return ApiResponse.success(session);
    }

    /**
     * 更新会话信息
     * PUT /api/workspace-sessions/{id}
     *
     * @param id 会话 ID
     * @param request 更新请求
     * @return 更新后的会话
     */
    @PutMapping("/{id}")
    public ApiResponse<ClaudeWorkspaceSession> updateSession(
            @PathVariable String id,
            @RequestBody UpdateSessionRequest request) {
        ClaudeWorkspaceSession session = workspaceSessionService.updateSession(
                id,
                request.getTitle(),
                request.getStatus()
        );
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        return ApiResponse.success(session);
    }

    /**
     * 删除会话
     * DELETE /api/workspace-sessions/{id}
     *
     * @param id 会话 ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable String id) {
        workspaceSessionService.deleteSession(id);
        return ApiResponse.success(null);
    }

    /**
     * 归档会话
     * POST /api/workspace-sessions/{id}/archive
     *
     * @param id 会话 ID
     * @return 归档后的会话
     */
    @PostMapping("/{id}/archive")
    public ApiResponse<ClaudeWorkspaceSession> archiveSession(@PathVariable String id) {
        ClaudeWorkspaceSession session = workspaceSessionService.archiveSession(id);
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        return ApiResponse.success(session);
    }

    /**
     * 绑定 Claude CLI session_id
     * POST /api/workspace-sessions/{id}/bind-claude-session
     *
     * @param id 系统会话 ID
     * @param request 绑定请求
     * @return 更新后的会话
     */
    @PostMapping("/{id}/bind-claude-session")
    public ApiResponse<ClaudeWorkspaceSession> bindClaudeSession(
            @PathVariable String id,
            @RequestBody BindClaudeSessionRequest request) {
        ClaudeWorkspaceSession session = workspaceSessionService.bindClaudeSession(
                id,
                request.getClaudeSessionId()
        );
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        return ApiResponse.success(session);
    }

    /**
     * 创建会话请求
     */
    @Data
    public static class CreateSessionRequest {
        private String scene;
        private String initialPrompt;
        private String workingDirectory;
    }

    /**
     * 更新会话请求
     */
    @Data
    public static class UpdateSessionRequest {
        private String title;
        private String status;
    }

    /**
     * 绑定 Claude Session 请求
     */
    @Data
    public static class BindClaudeSessionRequest {
        private String claudeSessionId;
    }
}