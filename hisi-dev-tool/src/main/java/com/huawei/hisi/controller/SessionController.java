package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.ClaudeMessage;
import com.huawei.hisi.model.ClaudeSession;
import com.huawei.hisi.service.SessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /**
     * 获取会话列表
     * GET /api/sessions
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> getSessions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<ClaudeSession> sessions = sessionService.getSessions(status, page, pageSize);
        int total = sessionService.getSessionCount(status);

        Map<String, Object> result = new HashMap<>();
        result.put("list", sessions);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);

        return ApiResponse.success(result);
    }

    /**
     * 获取会话详情
     * GET /api/sessions/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable String id) {
        ClaudeSession session = sessionService.getSession(id);
        if (session == null) {
            return ApiResponse.error("会话不存在");
        }

        List<ClaudeMessage> messages = sessionService.getMessages(id);

        Map<String, Object> result = new HashMap<>();
        result.put("session", session);
        result.put("messages", messages);

        return ApiResponse.success(result);
    }

    /**
     * 更新会话
     * PATCH /api/sessions/{id}
     */
    @PatchMapping("/{id}")
    public ApiResponse<String> updateSession(@PathVariable String id, @RequestBody UpdateSessionRequest request) {
        if (request.getTitle() != null) {
            sessionService.updateTitle(id, request.getTitle());
        }
        return ApiResponse.success("更新成功");
    }

    /**
     * 删除会话
     * DELETE /api/sessions/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<String> deleteSession(@PathVariable String id) {
        sessionService.deleteSession(id);
        return ApiResponse.success("删除成功");
    }

    /**
     * 归档会话
     * POST /api/sessions/{id}/archive
     */
    @PostMapping("/{id}/archive")
    public ApiResponse<String> archiveSession(@PathVariable String id) {
        sessionService.archiveSession(id);
        return ApiResponse.success("归档成功");
    }

    /**
     * 导出会话
     * GET /api/sessions/{id}/export
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportSession(
            @PathVariable String id,
            @RequestParam(defaultValue = "markdown") String format) {

        String content = sessionService.exportSession(id, format);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }

        String filename = "session-" + id + "." + format;
        String contentType = "json".equalsIgnoreCase(format) ?
            MediaType.APPLICATION_JSON_VALUE : "text/markdown";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 清除会话历史
     * DELETE /api/sessions/{id}/messages
     */
    @DeleteMapping("/{id}/messages")
    public ApiResponse<String> clearMessages(@PathVariable String id) {
        sessionService.clearMessages(id);
        return ApiResponse.success("清除成功");
    }

    /**
     * 更新会话请求
     */
    @Data
    public static class UpdateSessionRequest {
        private String title;
    }
}
