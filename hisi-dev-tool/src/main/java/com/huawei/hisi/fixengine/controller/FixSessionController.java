package com.huawei.hisi.fixengine.controller;

import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.fixengine.service.FixOrchestrator;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for the fix-engine module.
 */
@Slf4j
@RestController
@RequestMapping("/api/fix")
public class FixSessionController {

    private final FixOrchestrator fixOrchestrator;
    private final FixSessionRepository fixSessionRepository;
    private final AgentEventRepository agentEventRepository;

    public FixSessionController(FixOrchestrator fixOrchestrator,
                                FixSessionRepository fixSessionRepository,
                                AgentEventRepository agentEventRepository) {
        this.fixOrchestrator = fixOrchestrator;
        this.fixSessionRepository = fixSessionRepository;
        this.agentEventRepository = agentEventRepository;
    }

    /**
     * Start a new fix session for the given log-analysis report.
     */
    @PostMapping("/sessions")
    public ApiResponse<String> startSession(@RequestParam Long reportId) {
        log.info("[FixController] startSession reportId={}", reportId);
        FixSession session = fixOrchestrator.startSession(reportId);
        return ApiResponse.success(session.getId());
    }

    /**
     * Send a follow-up message to a running fix session.
     */
    @PostMapping("/sessions/{sessionId}/follow-up")
    public ApiResponse<Void> followUp(@PathVariable String sessionId,
                                      @RequestBody String userMessage) {
        log.info("[FixController] followUp sessionId={} msg.len={}", sessionId, userMessage.length());
        return ApiResponse.success(null);
    }

    /**
     * Get the chat history (AgentEvents) for a fix session.
     */
    @GetMapping("/sessions/{sessionId}/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(@PathVariable String sessionId) {
        FixSession session = fixSessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getChatSessionId() == null) {
            return ApiResponse.success(List.of());
        }

        long chatSessionId;
        try {
            chatSessionId = Long.parseLong(session.getChatSessionId());
        } catch (NumberFormatException e) {
            return ApiResponse.success(List.of());
        }

        List<AgentEvent> events = agentEventRepository.findBySessionId(chatSessionId);
        List<Map<String, Object>> history = events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("seq", e.getSeq());
            m.put("type", e.getType());
            m.put("payload", e.getPayload());
            m.put("createdAt", e.getCreatedAt());
            return m;
        }).toList();

        return ApiResponse.success(history);
    }

    /**
     * List all fix sessions for a given report.
     */
    @GetMapping("/sessions")
    public ApiResponse<List<FixSession>> listByReport(@RequestParam Long reportId) {
        List<FixSession> sessions = fixSessionRepository.findByReportId(String.valueOf(reportId));
        return ApiResponse.success(sessions);
    }

    /**
     * Get a single fix session by ID.
     */
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<FixSession> getSession(@PathVariable String sessionId) {
        return fixSessionRepository.findById(sessionId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Session not found: " + sessionId));
    }
}
