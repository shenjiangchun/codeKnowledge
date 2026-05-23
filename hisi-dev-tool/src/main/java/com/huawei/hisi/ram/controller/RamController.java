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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST + SSE entry point for the Requirement Analysis Master (RAM) Phase-1 UI.
 *
 * <p>Endpoints (all under {@code /api/ram}):
 * <ul>
 *   <li>{@code POST /sessions} – pre-creates a session row, returns the UUID
 *       handle, then runs {@code analyze_requirement} asynchronously against
 *       the pre-allocated id.</li>
 *   <li>{@code GET /sessions/{sid}} – rejoin info: status / current seq /
 *       clarifyPending flag.</li>
 *   <li>{@code GET /sessions/{sid}/stream} – Server-Sent-Events tail of
 *       {@code agent_event} rows for that session. Accepts an optional
 *       {@code ?afterSeq=N} query param to start polling beyond a seq.</li>
 *   <li>{@code POST /sessions/{sid}/clarify} – submits clarification answers.</li>
 *   <li>{@code POST /sessions/{sid}/resume} – resumes a parked session.</li>
 *   <li>{@code POST /sessions/{sid}/abort} – signals abort, appends an
 *       {@code ERROR} event tagged {@code RUN_ABORTED}.</li>
 * </ul>
 *
 * <p>The frontend-facing {@code sessionId} is a UUID. The backend long
 * {@link AgentSession#getId()} is allocated synchronously in
 * {@link #startSession} so the UUID->id mapping in {@link #sessionIdMap} is
 * always populated <em>before</em> the async DAG dispatch starts.
 */
@RestController
@RequestMapping("/api/ram")
public class RamController {

    private static final Logger log = LoggerFactory.getLogger(RamController.class);

    private static final long SSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long POLL_INTERVAL_MS = 500L;
    private static final int MAX_SESSION_MAPPINGS = 10_000;

    private final RamMcpServer ramMcpServer;
    private final AgentEventRepository eventRepository;
    private final AgentSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    private final java.util.concurrent.Executor asyncExecutor;
    private final ScheduledExecutorService streamScheduler;
    private final ExecutorService ownedAsyncExecutor;
    private final ScheduledExecutorService ownedScheduler;

    /** Maps the frontend UUID handle to the backend long session id (LRU, capped). */
    private final Map<String, Long> sessionIdMap = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_SESSION_MAPPINGS;
                }
            });
    /** Aborted UUID handles (bounded LRU set). */
    private final Set<String> abortedSessions = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_SESSION_MAPPINGS;
                }
            }));

    @org.springframework.beans.factory.annotation.Autowired
    public RamController(RamMcpServer ramMcpServer,
                         AgentEventRepository eventRepository,
                         AgentSessionRepository sessionRepository,
                         ObjectMapper objectMapper) {
        ExecutorService async = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ram-controller-async");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ram-sse-poll");
            t.setDaemon(true);
            return t;
        });
        this.ramMcpServer = ramMcpServer;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.asyncExecutor = async;
        this.streamScheduler = sched;
        this.ownedAsyncExecutor = async;
        this.ownedScheduler = sched;
    }

    /** Constructor for tests so executors can be swapped for synchronous variants. */
    RamController(RamMcpServer ramMcpServer,
                  AgentEventRepository eventRepository,
                  AgentSessionRepository sessionRepository,
                  ObjectMapper objectMapper,
                  java.util.concurrent.Executor asyncExecutor,
                  ScheduledExecutorService streamScheduler) {
        this.ramMcpServer = ramMcpServer;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.asyncExecutor = asyncExecutor;
        this.streamScheduler = streamScheduler;
        this.ownedAsyncExecutor = null;
        this.ownedScheduler = null;
    }

    @PreDestroy
    void shutdownExecutors() {
        shutdownGracefully(ownedAsyncExecutor, "ram-controller-async");
        shutdownGracefully(ownedScheduler, "ram-sse-poll");
    }

    private static void shutdownGracefully(ExecutorService exec, String name) {
        if (exec == null) {
            return;
        }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
            log.warn("Interrupted while shutting down {} executor", name);
        }
    }

    // ---------------------------------------------------------------------
    // POST /sessions
    // ---------------------------------------------------------------------

    public record StartSessionRequest(String rawInput, String projectPath, String userId) {}

    public record StartSessionResponse(String sessionId) {}

    @PostMapping("/sessions")
    public ApiResponse<StartSessionResponse> startSession(@RequestBody StartSessionRequest request) {
        log.info("[RAM][POST /sessions] entry request={}", request);
        if (request == null || request.rawInput() == null || request.rawInput().isBlank()) {
            log.warn("[RAM][POST /sessions] rejecting: rawInput is blank");
            return ApiResponse.error(400, "rawInput is required");
        }
        String handle = UUID.randomUUID().toString();
        String userId = (request.userId() == null || request.userId().isBlank())
                ? "anonymous" : request.userId();

        // Pre-create the agent_session row so the UUID->id mapping is in place
        // BEFORE the async analyze_requirement runs. This lets the SSE stream
        // attach immediately and emit events live instead of all at the end.
        AgentSession seeded = sessionRepository.save(AgentSession.newRunning(userId));
        long backendId = seeded.getId();
        sessionIdMap.put(handle, backendId);
        log.info("[RAM][POST /sessions] seeded session handle={} backendId={} userId={} projectPath={}",
                handle, backendId, userId, request.projectPath());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("raw_input", request.rawInput());
        args.put("user_id", userId);
        args.put("mode", "interactive");
        args.put("session_id", backendId);
        if (request.projectPath() != null) {
            args.put("project_path", request.projectPath());
        }

        CompletableFuture.runAsync(() -> dispatchAnalyze(handle, args), asyncExecutor);

        return ApiResponse.success(new StartSessionResponse(handle));
    }

    private void dispatchAnalyze(String handle, Map<String, Object> args) {
        log.info("[RAM][dispatchAnalyze] start handle={} args.keys={} sid={}",
                handle, args.keySet(), args.get("session_id"));
        try {
            McpResponse resp = ramMcpServer.invoke("analyze_requirement", args);
            if (resp == null) {
                log.error("[RAM][dispatchAnalyze] handle={} got null McpResponse", handle);
            } else if (!resp.ok()) {
                log.warn("[RAM][dispatchAnalyze] handle={} analyze_requirement returned NOT OK error={}",
                        handle, resp.error());
            } else {
                log.info("[RAM][dispatchAnalyze] handle={} analyze_requirement OK result.keys={}",
                        handle, resp.result() == null ? "null" : resp.result().keySet());
            }
        } catch (Exception e) {
            log.error("[RAM][dispatchAnalyze] handle={} threw {}: {}",
                    handle, e.getClass().getName(), e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // GET /sessions/{sid} — rejoin
    // ---------------------------------------------------------------------

    public record SessionInfoResponse(String status, long currentSeq, boolean clarifyPending) {}

    @GetMapping("/sessions/{sid}")
    public ApiResponse<SessionInfoResponse> sessionInfo(@PathVariable("sid") String handle) {
        Long backendId = sessionIdMap.get(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        Optional<AgentSession> session = sessionRepository.findById(backendId);
        if (session.isEmpty()) {
            return ApiResponse.error(404, "session row missing: " + backendId);
        }
        SessionStatus status = session.get().getStatus();
        long currentSeq = eventRepository.findMaxSeq(backendId);
        boolean clarifyPending = false;
        for (AgentEvent ev : eventRepository.findBySessionId(backendId)) {
            if (ev.getType() == EventType.CLARIFY_REQ) {
                clarifyPending = true;
            } else if (ev.getType() == EventType.CLARIFY_RES) {
                clarifyPending = false;
            }
        }
        return ApiResponse.success(new SessionInfoResponse(
                status == null ? null : status.name(), currentSeq, clarifyPending));
    }

    // ---------------------------------------------------------------------
    // GET /sessions/{sid}/stream
    // ---------------------------------------------------------------------

    @GetMapping("/sessions/{sid}/stream")
    public SseEmitter stream(@PathVariable("sid") String handle,
                             @RequestParam(value = "afterSeq", required = false) Long afterSeq) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicLong lastSeq = new AtomicLong(afterSeq == null ? 0L : Math.max(0L, afterSeq));
        AtomicLong waitTicks = new AtomicLong(0L);

        Runnable tick = () -> {
            try {
                if (abortedSessions.contains(handle)) {
                    sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                            "RUN_ABORTED", Map.of("reason", "user_requested")));
                    emitter.complete();
                    return;
                }
                Long backendId = sessionIdMap.get(handle);
                if (backendId == null) {
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
                        Map<String, Object> errPayload = lastErrorPayload(backendId);
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_FAILED", errPayload));
                        emitter.complete();
                    } else if (status == SessionStatus.ABORTED) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_ABORTED", Map.of()));
                        emitter.complete();
                    }
                }
            } catch (ClientGoneException e) {
                // Client disconnected — drop quietly and stop polling.
                log.debug("SSE client gone for handle {}: {}", handle, e.getCause().getMessage());
                try { emitter.complete(); } catch (Exception ignored) { /* already done */ }
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
            // ClientAbortException ("Connection reset by peer", "Broken pipe") means the
            // browser/tab went away. Not a server fault — emitter is already dead, log
            // at DEBUG and bail out silently so the scheduler stops re-firing.
            throw new ClientGoneException(e);
        } catch (IllegalStateException e) {
            // emitter.send after complete() — same as client gone.
            throw new ClientGoneException(e);
        }
    }

    /** Marker exception for "client already disconnected"; suppresses WARN noise. */
    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }

    private Map<String, Object> toSseMap(AgentEvent ev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", ev.getSeq());
        out.put("type", ev.getType() == null ? null : ev.getType().name());
        out.put("payload", parseJsonPayload(ev.getPayload()));
        return out;
    }

    /** Find the most recent ERROR payload for a session so RUN_FAILED carries the cause. */
    private Map<String, Object> lastErrorPayload(long backendId) {
        try {
            List<AgentEvent> events = eventRepository.findBySessionId(backendId);
            for (int i = events.size() - 1; i >= 0; i--) {
                AgentEvent ev = events.get(i);
                if (ev.getType() == EventType.ERROR) {
                    Map<String, Object> payload = parseJsonPayload(ev.getPayload());
                    Map<String, Object> out = new LinkedHashMap<>(payload);
                    out.putIfAbsent("sourceSeq", ev.getSeq());
                    return out;
                }
            }
        } catch (RuntimeException e) {
            log.warn("lastErrorPayload lookup failed for sid={}", backendId, e);
        }
        return Map.of("message", "session marked FAILED but no ERROR event recorded");
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
            log.warn("Failed to parse stored event payload as JSON; returning raw. detail={}",
                    e.getOriginalMessage());
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
        // accepted=true reflects the success branch only — the error branch above
        // short-circuits with ApiResponse.error so the caller never sees accepted=false.
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
            abortedSessions.add(handle);
            return ApiResponse.success(new AbortResponse(true));
        }
        abortedSessions.add(handle);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "user_requested");
        payload.put("type", "RUN_ABORTED");
        String key = "abort-" + backendId + "-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(backendId)
                .type(EventType.ERROR)
                .payload(toJson(payload, handle))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        try {
            eventRepository.append(ev);
        } catch (RuntimeException e) {
            log.warn("append RUN_ABORTED event failed for sid={} handle={}", backendId, handle, e);
        }
        sessionRepository.updateStatus(backendId, SessionStatus.ABORTED);
        return ApiResponse.success(new AbortResponse(true));
    }

    private String toJson(Map<String, Object> payload, String handle) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize abort payload to JSON for handle={} detail={}",
                    handle, e.getOriginalMessage());
            return "{}";
        }
    }

    /** Test-only seam to pre-populate the UUID-to-backend mapping. */
    void registerSessionMapping(String handle, long backendId) {
        sessionIdMap.put(handle, backendId);
    }

    /** Test-only seam: visible to unit tests for invoking the SSE polling Runnable. */
    Set<String> abortedSessionsForTest() {
        return new LinkedHashSet<>(abortedSessions);
    }
}
