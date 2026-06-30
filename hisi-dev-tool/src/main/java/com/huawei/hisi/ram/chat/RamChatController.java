package com.huawei.hisi.ram.chat;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.chat.dto.*;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ram/chat")
@RequiredArgsConstructor
public class RamChatController {

    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final RamChatOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    @PostMapping("/sessions")
    public ApiResponse<CreateSessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        if (request == null || request.projectPath() == null || request.projectPath().isBlank()) {
            return ApiResponse.error(400, "projectPath is required");
        }

        AgentSession session = AgentSession.newRunning("ram-chat", SessionType.STATUS);
        session.setIntent("RAM 对话: " + (request.projectName() != null ? request.projectName() : request.projectPath()));
        try {
            session.setProjectPaths(objectMapper.writeValueAsString(List.of(request.projectPath())));
        } catch (Exception ignored) {}

        AgentSession saved = sessionRepository.save(session);
        log.info("[RamChatController] created sessionId={} projectPath={}",
                saved.getId(), request.projectPath());

        // If initialQuestion provided, run first turn
        if (request.initialQuestion() != null && !request.initialQuestion().isBlank()) {
            orchestrator.runTurn(saved.getId(), request.initialQuestion(), request.projectPath());
        }

        return ApiResponse.success(new CreateSessionResponse(
                String.valueOf(saved.getId()),
                request.projectPath(),
                request.projectName()
        ));
    }

    @PostMapping("/{sid}/messages")
    public ApiResponse<SendMessageResponse> sendMessage(
            @PathVariable Long sid,
            @RequestBody SendMessageRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return ApiResponse.error(400, "text is required");
        }

        AgentSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null) {
            return ApiResponse.error(404, "session not found: " + sid);
        }

        String projectPath = extractFirstProjectPath(session);
        if (projectPath == null) {
            return ApiResponse.error(400, "session has no projectPath");
        }

        TurnResult result = orchestrator.runTurn(sid, request.text(), projectPath);
        return ApiResponse.success(new SendMessageResponse(
                result.turnId(),
                result.status(),
                result.errorMessage()
        ));
    }

    @GetMapping("/{sid}/events")
    public ApiResponse<List<ChatEventDto>> getEvents(@PathVariable Long sid) {
        List<AgentEvent> events = eventRepository.findBySessionId(sid);
        List<ChatEventDto> dtos = events.stream()
                .map(e -> new ChatEventDto(
                        e.getId(),
                        e.getSessionId(),
                        e.getSeq(),
                        e.getType(),
                        e.getPayload(),
                        e.getCreatedAt()))
                .toList();
        return ApiResponse.success(dtos);
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionSummaryDto>> listSessions() {
        List<AgentSession> sessions = sessionRepository.listRecentBySessionType("STATUS", 50);
        return ApiResponse.success(sessions.stream()
                .map(s -> new SessionSummaryDto(
                        String.valueOf(s.getId()),
                        extractProjectName(s),
                        extractFirstProjectPath(s),
                        s.getIntent(),
                        s.getStatus() != null ? s.getStatus().name() : "UNKNOWN",
                        s.getCreatedAt(),
                        s.getUpdatedAt(),
                        eventRepository.countBySessionId(s.getId())
                ))
                .toList());
    }

    @PatchMapping("/{sid}/title")
    public ApiResponse<Void> renameSession(@PathVariable Long sid, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ApiResponse.error(400, "title is required");
        }
        sessionRepository.findById(sid).ifPresent(s -> {
            s.setIntent(title);
            sessionRepository.update(s);
        });
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{sid}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sid) {
        sessionRepository.updateStatus(sid, SessionStatus.ARCHIVED);
        return ApiResponse.success(null);
    }

    private String extractFirstProjectPath(AgentSession session) {
        if (session.getProjectPaths() == null) return null;
        try {
            List<String> paths = objectMapper.readValue(session.getProjectPaths(), List.class);
            return paths.isEmpty() ? null : paths.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractProjectName(AgentSession session) {
        if (session.getIntent() == null) return "";
        int idx = session.getIntent().indexOf(":");
        return idx > 0 ? session.getIntent().substring(idx + 1).trim() : session.getIntent();
    }
}
