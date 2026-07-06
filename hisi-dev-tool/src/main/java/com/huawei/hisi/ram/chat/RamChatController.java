package com.huawei.hisi.ram.chat;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.chat.dto.*;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
    private final TurnRegistry turnRegistry;
    private final RamChatWebSocketHandler wsHandler;

    @PostMapping("/sessions")
    public ApiResponse<CreateSessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        if (request == null || request.projectPaths() == null || request.projectPaths().isEmpty()) {
            return ApiResponse.error(400, "projectPaths is required");
        }

        AgentSession session = AgentSession.newRunning("ram-chat", SessionType.STATUS);
        String displayPath = request.projectPaths().get(0);
        session.setIntent("RAM 对话: " + (request.projectName() != null ? request.projectName() : displayPath));
        try {
            session.setProjectPaths(objectMapper.writeValueAsString(request.projectPaths()));
        } catch (Exception ignored) {}

        AgentSession saved = sessionRepository.save(session);
        log.info("[RamChatController] created sessionId={} projectPaths={}",
                saved.getId(), request.projectPaths());

        // If initialQuestion provided, run first turn
        if (request.initialQuestion() != null && !request.initialQuestion().isBlank()) {
            orchestrator.runTurn(saved.getId(), request.initialQuestion(), request.projectPaths());
        }

        return ApiResponse.success(new CreateSessionResponse(
                String.valueOf(saved.getId()),
                displayPath,
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

        List<String> projectPaths = extractProjectPaths(session);
        if (projectPaths.isEmpty()) {
            return ApiResponse.error(400, "session has no projectPath");
        }

        // Async: return immediately with turnId; streaming reaches the client via WebSocket.
        // The previous synchronous call to orchestrator.runTurn(...) blocked the HTTP thread
        // for the full multi-tool turn, exceeding the frontend's 120s axios timeout.
        String turnId = orchestrator.startTurnAsync(sid, request.text(), projectPaths);
        return ApiResponse.success(new SendMessageResponse(
                turnId,
                "STARTED",
                null
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
                        extractProjectPaths(s).stream().findFirst().orElse(""),
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

    /**
     * Inject a user message mid-turn. If a turn is currently streaming, interrupts it,
     * persists a {@code TURN_INTERRUPTED} event with the partial text, pushes it over
     * the WebSocket, then starts a new turn with the injected content.
     *
     * <p>Returns 202 on success, 400 for validation errors, 404 if session is missing,
     * and 500 if serializing the interrupt payload fails.
     */
    @PostMapping("/{sid}/inject")
    public ResponseEntity<?> inject(@PathVariable Long sid, @RequestBody InjectRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }

        AgentSession session = sessionRepository.findById(sid).orElse(null);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("error", "session not found: " + sid));
        }

        List<String> projectPaths = extractProjectPaths(session);
        if (projectPaths.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "session has no projectPath"));
        }

        try {
            orchestrator.injectAndContinue(sid, request.content(), projectPaths);
        } catch (IllegalStateException e) {
            log.error("Failed to inject for session {}: {}", sid, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{sid}/interrupt")
    public ResponseEntity<?> interrupt(@PathVariable Long sid) {
        var maybe = turnRegistry.interrupt(sid);
        if (maybe.isEmpty()) {
            return ResponseEntity.ok(Map.of("interrupted", false));
        }
        TurnRegistry.InterruptResult res = maybe.get();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "turnId", res.turnId(),
                    "partialText", res.partialText(),
                    "reason", "user_interrupt"
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize interrupt payload for session {}: {}", sid, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
        }
        AgentEvent ev = eventRepository.append(AgentEvent.turnInterrupted(
                sid, 0L, res.turnId(), payload, "interrupt-" + res.turnId())); // seq assigned by repository on append

        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("type", "turn_interrupted");
        wsPayload.put("turnId", res.turnId());
        wsPayload.put("partialText", res.partialText());
        wsPayload.put("sessionId", sid);
        wsPayload.put("eventId", ev != null ? ev.getId() : null);
        wsPayload.put("seq", ev != null ? ev.getSeq() : null);
        wsPayload.put("createdAt", ev != null ? ev.getCreatedAt() : System.currentTimeMillis() / 1000L);
        wsHandler.pushEvent(sid, wsPayload);

        return ResponseEntity.accepted().body(Map.of(
                "interrupted", true,
                "turnId", res.turnId(),
                "partialText", res.partialText()
        ));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractProjectPaths(AgentSession session) {
        if (session.getProjectPaths() == null) return List.of();
        try {
            return objectMapper.readValue(session.getProjectPaths(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractProjectName(AgentSession session) {
        if (session.getIntent() == null) return "";
        int idx = session.getIntent().indexOf(":");
        return idx > 0 ? session.getIntent().substring(idx + 1).trim() : session.getIntent();
    }
}
