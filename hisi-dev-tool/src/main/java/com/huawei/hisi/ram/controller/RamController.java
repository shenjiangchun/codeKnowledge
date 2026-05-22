package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.mcp.McpResponse;
import com.huawei.hisi.ram.mcp.RamMcpServer;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST + SSE entry point for the Requirement Analysis Master (RAM) Phase-1 UI.
 *
 * <p>Endpoints (all under {@code /api/ram}):
 * <ul>
 *   <li>{@code POST /sessions} – kicks off an {@code analyze_requirement} run
 *       asynchronously and returns a UUID handle.</li>
 *   <li>{@code GET /sessions/{sid}/stream} – Server-Sent-Events tail of
 *       {@code agent_event} rows for that session.</li>
 *   <li>{@code POST /sessions/{sid}/clarify} – submits clarification answers.</li>
 *   <li>{@code POST /sessions/{sid}/resume} – resumes a parked session.</li>
 *   <li>{@code POST /sessions/{sid}/abort} – signals abort, appends an
 *       {@code ERROR} event tagged {@code RUN_ABORTED}.</li>
 * </ul>
 *
 * <p>The frontend-facing {@code sessionId} is a UUID. Once
 * {@code analyze_requirement} completes its first orchestrator call the
 * backend long {@link AgentSession#getId()} is recorded in
 * {@link #sessionIdMap} so subsequent endpoints can resolve the handle.
 */
@RestController
@RequestMapping("/api/ram")
public class RamController {

    private static final Logger log = LoggerFactory.getLogger(RamController.class);

    private static final long SSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long POLL_INTERVAL_MS = 500L;

    private final RamMcpServer ramMcpServer;
    private final AgentEventRepository eventRepository;
    private final AgentSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    private final Executor asyncExecutor;
    private final ScheduledExecutorService streamScheduler;

    /** Maps the frontend UUID handle to the backend long session id. */
    private final Map<String, Long> sessionIdMap = new ConcurrentHashMap<>();
    /** Aborted UUID handles. */
    private final Map<String, Boolean> abortedSessions = new ConcurrentHashMap<>();

    public RamController(RamMcpServer ramMcpServer,
                         AgentEventRepository eventRepository,
                         AgentSessionRepository sessionRepository,
                         ObjectMapper objectMapper) {
        this(ramMcpServer, eventRepository, sessionRepository, objectMapper,
                Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "ram-controller-async");
                    t.setDaemon(true);
                    return t;
                }),
                Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "ram-sse-poll");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** Constructor for tests so executors can be swapped for synchronous variants. */
    RamController(RamMcpServer ramMcpServer,
                  AgentEventRepository eventRepository,
                  AgentSessionRepository sessionRepository,
                  ObjectMapper objectMapper,
                  Executor asyncExecutor,
                  ScheduledExecutorService streamScheduler) {
        this.ramMcpServer = ramMcpServer;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.asyncExecutor = asyncExecutor;
        this.streamScheduler = streamScheduler;
    }

    // ---------------------------------------------------------------------
    // POST /sessions
    // ---------------------------------------------------------------------

    public record StartSessionRequest(String rawInput, String projectPath, String userId) {}

    public record StartSessionResponse(String sessionId) {}

    @PostMapping("/sessions")
    public ApiResponse<StartSessionResponse> startSession(@RequestBody StartSessionRequest request) {
        if (request == null || request.rawInput() == null || request.rawInput().isBlank()) {
            return ApiResponse.error(400, "rawInput is required");
        }
        String handle = UUID.randomUUID().toString();
        String userId = (request.userId() == null || request.userId().isBlank())
                ? "anonymous" : request.userId();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("raw_input", request.rawInput());
        args.put("user_id", userId);
        args.put("mode", "interactive");
        if (request.projectPath() != null) {
            args.put("project_path", request.projectPath());
        }

        CompletableFuture.runAsync(() -> dispatchAnalyze(handle, args), asyncExecutor);

        return ApiResponse.success(new StartSessionResponse(handle));
    }

    private void dispatchAnalyze(String handle, Map<String, Object> args) {
        try {
            McpResponse resp = ramMcpServer.invoke("analyze_requirement", args);
            if (resp != null && resp.ok()) {
                Object sid = resp.result().get("session_id");
                if (sid instanceof Number n) {
                    sessionIdMap.put(handle, n.longValue());
                }
            } else {
                log.warn("analyze_requirement failed for handle {}: {}", handle,
                        resp == null ? "null response" : resp.error());
            }
        } catch (Exception e) {
            log.error("analyze_requirement threw for handle {}", handle, e);
        }
    }

    // ---------------------------------------------------------------------
    // GET /sessions/{sid}/stream
    // ---------------------------------------------------------------------

    @GetMapping("/sessions/{sid}/stream")
    public SseEmitter stream(@PathVariable("sid") String handle) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicLong lastSeq = new AtomicLong(0L);
        AtomicLong waitTicks = new AtomicLong(0L);

        Runnable tick = () -> {
            try {
                if (Boolean.TRUE.equals(abortedSessions.get(handle))) {
                    sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                            "RUN_ABORTED", Map.of("reason", "user_requested")));
                    emitter.complete();
                    return;
                }
                Long backendId = sessionIdMap.get(handle);
                if (backendId == null) {
                    // backend session not yet allocated; keep waiting up to ~60s
                    if (waitTicks.incrementAndGet() > 120L) {
                        emitter.completeWithError(
                                new IllegalStateException("session not started: " + handle));
                    }
                    return;
                }

                List<AgentEvent> events = eventRepository.findBySessionId(backendId);
                for (AgentEvent ev : events) {
                    if (ev.getSeq() <= lastSeq.get()) {
                        continue;
                    }
                    sendEvent(emitter, toSseMap(ev));
                    lastSeq.set(ev.getSeq());

                    if (ev.getType() == EventType.CLARIFY_REQ) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "CLARIFY_REQUIRED", parseJsonPayload(ev.getPayload())));
                        emitter.complete();
                        return;
                    }
                }

                Optional<AgentSession> session = sessionRepository.findById(backendId);
                if (session.isPresent()) {
                    SessionStatus status = session.get().getStatus();
                    if (status == SessionStatus.DONE) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_COMPLETED", Map.of()));
                        emitter.complete();
                    } else if (status == SessionStatus.FAILED) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_FAILED", Map.of()));
                        emitter.complete();
                    } else if (status == SessionStatus.ABORTED) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_ABORTED", Map.of()));
                        emitter.complete();
                    }
                }
            } catch (Exception e) {
                log.warn("SSE tick failed for handle {}", handle, e);
                emitter.completeWithError(e);
            }
        };

        var future = streamScheduler.scheduleAtFixedRate(tick, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(t -> future.cancel(true));
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().data(payload));
        } catch (IOException e) {
            throw new IllegalStateException("SSE send failed", e);
        }
    }

    private Map<String, Object> toSseMap(AgentEvent ev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", ev.getSeq());
        out.put("type", ev.getType() == null ? null : ev.getType().name());
        out.put("payload", parseJsonPayload(ev.getPayload()));
        return out;
    }

    private Map<String, Object> syntheticEvent(long seq, String type, Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", seq);
        out.put("type", type);
        out.put("payload", payload == null ? Map.of() : payload);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return Map.of("value", parsed);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/clarify
    // ---------------------------------------------------------------------

    public record ClarifyRequest(Map<String, Object> answers) {}

    public record ClarifyResponse(boolean accepted, long nextSeq) {}

    @PostMapping("/sessions/{sid}/clarify")
    public ApiResponse<ClarifyResponse> clarify(@PathVariable("sid") String handle,
                                                @RequestBody ClarifyRequest request) {
        Long backendId = sessionIdMap.get(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        Map<String, Object> answers = request == null || request.answers() == null
                ? Map.of() : request.answers();
        Map<String, Object> args = Map.of("session_id", backendId, "answers", answers);
        McpResponse resp = ramMcpServer.invoke("submit_clarification", args);
        if (resp == null || !resp.ok()) {
            return ApiResponse.error(500, resp == null ? "no response" : resp.error());
        }
        long nextSeq = eventRepository.findMaxSeq(backendId);
        return ApiResponse.success(new ClarifyResponse(true, nextSeq));
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/resume
    // ---------------------------------------------------------------------

    public record ResumeResponse(boolean resumed) {}

    @PostMapping("/sessions/{sid}/resume")
    public ApiResponse<ResumeResponse> resume(@PathVariable("sid") String handle) {
        Long backendId = sessionIdMap.get(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        McpResponse resp = ramMcpServer.invoke("resume_session", Map.of("session_id", backendId));
        if (resp == null || !resp.ok()) {
            return ApiResponse.error(500, resp == null ? "no response" : resp.error());
        }
        return ApiResponse.success(new ResumeResponse(true));
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/abort
    // ---------------------------------------------------------------------

    public record AbortResponse(boolean aborted) {}

    @PostMapping("/sessions/{sid}/abort")
    public ApiResponse<AbortResponse> abort(@PathVariable("sid") String handle) {
        Long backendId = sessionIdMap.get(handle);
        if (backendId == null) {
            // mark abort flag anyway so a streaming client gets the signal
            abortedSessions.put(handle, Boolean.TRUE);
            return ApiResponse.success(new AbortResponse(true));
        }
        abortedSessions.put(handle, Boolean.TRUE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "user_requested");
        payload.put("type", "RUN_ABORTED");
        String key = "abort-" + backendId + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(backendId)
                .type(EventType.ERROR)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        try {
            eventRepository.append(ev);
        } catch (Exception e) {
            log.warn("append RUN_ABORTED event failed for {}", backendId, e);
        }
        sessionRepository.updateStatus(backendId, SessionStatus.ABORTED);
        return ApiResponse.success(new AbortResponse(true));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Test-only seam to pre-populate the UUID-to-backend mapping. */
    void registerSessionMapping(String handle, long backendId) {
        sessionIdMap.put(handle, backendId);
    }
}
