package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.mcp.McpResponse;
import com.huawei.hisi.ram.mcp.RamMcpServer;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.nodes.TechPlanNode;
import com.huawei.hisi.service.ReportExportService;
import com.huawei.hisi.workflow.InputsHasher;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REST + SSE entry point for the Requirement Analysis Master (RAM) Phase-1 UI.
 *
 * <p>Endpoints (all under {@code /api/ram}):
 * <ul>
 *   <li>{@code POST /sessions} – pre-creates a session row, returns the UUID
 *       handle, then runs {@code analyze_requirement} asynchronously against
 *       the pre-allocated id.</li>
 *   <li>{@code GET /sessions} – list recent sessions for history/switching.</li>
 *   <li>{@code GET /sessions/{sid}} – rejoin info: status / current seq /
 *       clarifyPending flag.</li>
 *   <li>{@code GET /sessions/{sid}/stream} – Server-Sent-Events tail of
 *       {@code agent_event} rows for that session. Accepts an optional
 *       {@code ?afterSeq=N} query param to start polling beyond a seq.</li>
 *   <li>{@code GET /sessions/{sid}/events} – replay all events for session recovery.</li>
 *   <li>{@code POST /sessions/{sid}/clarify} – submits clarification answers.</li>
 *   <li>{@code POST /sessions/{sid}/resume} – resumes a parked session.</li>
 *   <li>{@code POST /sessions/{sid}/abort} – signals abort, appends an
 *       {@code ERROR} event tagged {@code RUN_ABORTED}.</li>
 *   <li>{@code POST /sessions/{sid}/nodes/tech-plan} – manually triggers
 *       TechPlanNode execution (not part of the auto DAG pipeline); returns
 *       202 Accepted, runs asynchronously, and emits a CHECKPOINT SSE event
 *       on completion.</li>
 *   <li>{@code POST /sessions/{sid}/rerun-from/{nodeName}} – force re-execute from node.</li>
 *   <li>{@code GET /sessions/{sid}/clarify-rounds} – list all clarify Q&A rounds.</li>
 *   <li>{@code POST /sessions/{sid}/rerun-from-round/{roundNo}} – rerun from clarify round.</li>
 *   <li>{@code GET /health} – backend liveness + startedAt for restart detection.</li>
 * </ul>
 *
 * <p>The frontend-facing {@code sessionId} is a UUID. The backend long
 * {@link AgentSession#getId()} is allocated synchronously in
 * {@link #startSession} so the UUID->id mapping in {@link SessionMappingService} is
 * always populated <em>before</em> the async DAG dispatch starts.
 */
@RestController
@RequestMapping("/api/ram")
public class RamController {

    private static final Logger log = LoggerFactory.getLogger(RamController.class);

    private static final long SSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    @Value("${ram.sse.pool-size:2}")
    private int ssePoolSize;

    @Value("${ram.sse.poll-interval-ms:500}")
    private long ssePollIntervalMs;

    private final RamMcpServer ramMcpServer;
    private final AgentEventRepository eventRepository;
    private final AgentSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final TechPlanNode techPlanNode;
    private final SessionMappingService sessionMappingService;
    private final ReportExportService reportExportService;

    private final long startedAt = System.currentTimeMillis();

    private final java.util.concurrent.Executor asyncExecutor;
    private final ScheduledExecutorService streamScheduler;
    private final ExecutorService ownedAsyncExecutor;
    private final ScheduledExecutorService ownedScheduler;

    /** Aborted UUID handles (bounded LRU set). */
    private final Set<String> abortedSessions = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 10_000;
                }
            }));

    @org.springframework.beans.factory.annotation.Autowired
    public RamController(RamMcpServer ramMcpServer,
                         AgentEventRepository eventRepository,
                         AgentSessionRepository sessionRepository,
                         ObjectMapper objectMapper,
                         TechPlanNode techPlanNode,
                         SessionMappingService sessionMappingService,
                         ReportExportService reportExportService) {
        // Bounded thread pool to prevent resource exhaustion under concurrent load
        ExecutorService async = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "ram-controller-async");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(ssePoolSize, r -> {
            Thread t = new Thread(r, "ram-sse-poll");
            t.setDaemon(true);
            return t;
        });
        this.ramMcpServer = ramMcpServer;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.techPlanNode = techPlanNode;
        this.sessionMappingService = sessionMappingService;
        this.reportExportService = reportExportService;
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
                  TechPlanNode techPlanNode,
                  SessionMappingService sessionMappingService,
                  ReportExportService reportExportService,
                  java.util.concurrent.Executor asyncExecutor,
                  ScheduledExecutorService streamScheduler) {
        this.ramMcpServer = ramMcpServer;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.techPlanNode = techPlanNode;
        this.sessionMappingService = sessionMappingService;
        this.reportExportService = reportExportService;
        this.asyncExecutor = asyncExecutor;
        this.streamScheduler = streamScheduler;
        this.ownedAsyncExecutor = null;
        this.ownedScheduler = null;
        // Set default SSE poll interval for tests (since @Value won't inject)
        this.ssePollIntervalMs = 500L;
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

    public record StartSessionRequest(String rawInput, String projectPath,
                                       java.util.List<String> projectPaths, String userId) {}

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

        // Build project_paths from both single and multi-select fields
        java.util.List<String> allPaths = new java.util.ArrayList<>();
        if (request.projectPath() != null && !request.projectPath().isBlank()) {
            allPaths.add(request.projectPath());
        }
        if (request.projectPaths() != null) {
            for (String p : request.projectPaths()) {
                if (p != null && !p.isBlank() && !allPaths.contains(p)) {
                    allPaths.add(p);
                }
            }
        }

        // Pre-create the agent_session row so the UUID->id mapping is in place
        // BEFORE the async analyze_requirement runs. This lets the SSE stream
        // attach immediately and emit events live instead of all at the end.
        AgentSession seeded = sessionRepository.save(AgentSession.newRunning(userId, SessionType.DEMAND));
        long backendId = seeded.getId();
        // Persist UUID, intent, project_paths to DB so they survive restart
        seeded.setUuid(handle);
        seeded.setIntent(request.rawInput().length() > 2000 ? request.rawInput().substring(0, 2000) : request.rawInput());
        if (!allPaths.isEmpty()) {
            try { seeded.setProjectPaths(objectMapper.writeValueAsString(allPaths)); } catch (Exception ignored) {}
        }
        sessionRepository.update(seeded);
        sessionMappingService.register(handle, backendId);
        log.info("[RAM][POST /sessions] seeded session handle={} backendId={} userId={} projectPath={}",
                handle, backendId, userId, request.projectPath());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("raw_input", request.rawInput());
        args.put("user_id", userId);
        args.put("mode", "interactive");
        args.put("session_id", backendId);
        if (!allPaths.isEmpty()) {
            args.put("project_path", allPaths.get(0)); // backward compat
            args.put("project_paths", allPaths);
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

    public record SessionInfoResponse(String status, long currentSeq,
                                       boolean clarifyPending, boolean hitlPending,
                                       String hitlNodeName) {}

    @GetMapping("/sessions/{sid}")
    public ApiResponse<SessionInfoResponse> sessionInfo(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
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
        boolean hitlPending = false;
        String hitlNodeName = null;
        for (AgentEvent ev : eventRepository.findBySessionId(backendId)) {
            if (ev.getType() == EventType.CLARIFY_REQ) {
                clarifyPending = true;
            } else if (ev.getType() == EventType.CLARIFY_RES) {
                clarifyPending = false;
            } else if (ev.getType() == EventType.HITL_REQ) {
                hitlPending = true;
                Map<String, Object> payload = parseJsonPayload(
                        ev.getPayload() == null ? "" : ev.getPayload());
                hitlNodeName = payload.get("nodeName") instanceof String s ? s : null;
            } else if (ev.getType() == EventType.HITL_RES) {
                hitlPending = false;
                hitlNodeName = null;
            }
        }
        return ApiResponse.success(new SessionInfoResponse(
                status == null ? null : status.name(), currentSeq,
                clarifyPending, hitlPending, hitlNodeName));
    }

    // ---------------------------------------------------------------------
    // GET /sessions/{sid}/stream
    // ---------------------------------------------------------------------

    @GetMapping(value = "/sessions/{sid}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable("sid") String handle,
                             @RequestParam(value = "afterSeq", required = false) Long afterSeq,
                             HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicLong lastSeq = new AtomicLong(afterSeq == null ? 0L : Math.max(0L, afterSeq));
        AtomicLong waitTicks = new AtomicLong(0L);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        Runnable tick = () -> {
            try {
                // Send SSE heartbeat (comment) to keep the connection alive
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IllegalStateException | IOException e) {
                    throw new ClientGoneException(e);
                }

                if (abortedSessions.contains(handle)) {
                    sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                            "RUN_ABORTED", Map.of("reason", "user_requested")));
                    log.info("[SSE] handle={} aborted -> closing stream", handle);
                    emitter.complete();
                    return;
                }
                Long backendId = sessionMappingService.resolveBackendId(handle);
                if (backendId == null) {
                    if (waitTicks.incrementAndGet() > 120L) {
                        emitter.completeWithError(
                                new IllegalStateException("session not started: " + handle));
                    } else if (waitTicks.get() % 20 == 1) {
                        log.debug("[SSE] handle={} waiting for session mapping (tick={})", handle, waitTicks.get());
                    }
                    return;
                }

                List<AgentEvent> events = eventRepository.findBySessionId(backendId);
                int newCount = 0;
                for (AgentEvent ev : events) {
                    if (ev.getSeq() <= lastSeq.get()) {
                        continue;
                    }
                    newCount++;
                    sendEvent(emitter, toSseMap(ev));
                    lastSeq.set(ev.getSeq());

                    if (ev.getType() == EventType.CLARIFY_REQ) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "CLARIFY_REQUIRED", parseJsonPayload(ev.getPayload())));
                        log.info("[SSE] handle={} CLARIFY_REQ -> closing stream", handle);
                        emitter.complete();
                        return;
                    }
                    if (ev.getType() == EventType.HITL_REQ) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "HITL_REQUIRED", parseJsonPayload(ev.getPayload())));
                        log.info("[SSE] handle={} HITL_REQ -> closing stream", handle);
                        emitter.complete();
                        return;
                    }
                }

                if (newCount > 0) {
                    log.debug("[SSE] handle={} sent {} new events, lastSeq={}", handle, newCount, lastSeq.get());
                }

                Optional<AgentSession> session = sessionRepository.findById(backendId);
                if (session.isPresent()) {
                    SessionStatus status = session.get().getStatus();
                    if (status == SessionStatus.DONE) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_COMPLETED", Map.of()));
                        log.info("[SSE] handle={} session DONE -> closing stream", handle);
                        emitter.complete();
                    } else if (status == SessionStatus.FAILED) {
                        Map<String, Object> errPayload = lastErrorPayload(backendId);
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_FAILED", errPayload));
                        log.info("[SSE] handle={} session FAILED -> closing stream", handle);
                        emitter.complete();
                    } else if (status == SessionStatus.ABORTED) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "RUN_ABORTED", Map.of()));
                        log.info("[SSE] handle={} session ABORTED -> closing stream", handle);
                        emitter.complete();
                    }
                }
            } catch (ClientGoneException e) {
                // Client disconnected — cancel the scheduled polling immediately.
                log.info("[SSE] client gone for handle {} lastSeq={} reason={}",
                        handle, lastSeq.get(), e.getCause() != null ? e.getCause().getClass().getSimpleName() : "unknown");
                ScheduledFuture<?> f = futureRef.get();
                if (f != null) {
                    f.cancel(true);
                }
            } catch (Exception e) {
                log.warn("[SSE] tick failed for handle {} lastSeq={} error={}",
                        handle, lastSeq.get(), e.toString());
                ScheduledFuture<?> f = futureRef.get();
                if (f != null) {
                    f.cancel(true);
                }
                emitter.completeWithError(e);
            }
        };

        ScheduledFuture<?> future = streamScheduler.scheduleAtFixedRate(tick, 0L, ssePollIntervalMs, TimeUnit.MILLISECONDS);
        futureRef.set(future);
        log.info("[SSE] stream opened handle={} afterSeq={}", handle, afterSeq);
        emitter.onCompletion(() -> {
            log.info("[SSE] stream completed handle={} lastSeq={}", handle, lastSeq.get());
            future.cancel(true);
        });
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

    public record ClarifyResponse(boolean accepted, long nextSeq,
                                      String status, Map<String, Object> hitlPayload) {}

    @PostMapping("/sessions/{sid}/clarify")
    public ApiResponse<ClarifyResponse> clarify(@PathVariable("sid") String handle,
                                                @RequestBody ClarifyRequest request) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        Map<String, Object> answers = request == null || request.answers() == null
                ? Map.of() : request.answers();

        // Record the current max seq BEFORE dispatching async — the frontend uses
        // this as its SSE afterSeq so it receives all events from the new run.
        long nextSeq = eventRepository.findMaxSeq(backendId);

        // Async dispatch: like startSession and confirm, the orchestrator runs
        // in the background.  The frontend picks up CLARIFY_REQ / HITL_REQ /
        // CHECKPOINT / RUN_COMPLETED via SSE — no need to block the HTTP thread.
        Map<String, Object> args = Map.of("session_id", backendId, "answers", answers);
        CompletableFuture.runAsync(() -> {
            try {
                McpResponse resp = ramMcpServer.invoke("submit_clarification", args);
                if (resp == null || !resp.ok()) {
                    log.error("[RAM][clarify] async dispatch failed handle={} error={}",
                            handle, resp == null ? "null" : resp.error());
                }
            } catch (Exception e) {
                log.error("[RAM][clarify] async dispatch threw handle={} error={}",
                        handle, e.getMessage(), e);
            }
        }, asyncExecutor);

        return ApiResponse.success(new ClarifyResponse(true, nextSeq, null, null));
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/resume
    // ---------------------------------------------------------------------

    public record ResumeResponse(boolean resumed) {}

    @PostMapping("/sessions/{sid}/resume")
    public ApiResponse<ResumeResponse> resume(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
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
    // POST /sessions/{sid}/confirm — inter-node HITL confirmation
    // ---------------------------------------------------------------------

    public record ConfirmRequest(String nodeName, String action, String feedback,
                                  Map<String, Object> editedOutput) {}

    public record ConfirmResponse(boolean accepted, long nextSeq) {}

    @PostMapping("/sessions/{sid}/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable("sid") String handle,
                                                 @RequestBody ConfirmRequest request) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        String action = request.action() == null ? "approve" : request.action();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("session_id", backendId);
        args.put("node_name", request.nodeName());
        args.put("action", action);
        if (request.feedback() != null) {
            args.put("feedback", request.feedback());
        }
        if (request.editedOutput() != null) {
            args.put("edited_output", request.editedOutput());
        }

        // Async dispatch: the DAG continues to the next node (LLM call) after
        // confirmation, so we cannot block the HTTP response on it.
        CompletableFuture.runAsync(() -> {
            try {
                ramMcpServer.invoke("submit_confirmation", args);
            } catch (Exception e) {
                log.error("[RAM][confirm] async dispatch failed handle={} nodeName={} error={}",
                        handle, request.nodeName(), e.getMessage(), e);
            }
        }, asyncExecutor);

        long nextSeq = eventRepository.findMaxSeq(backendId);
        return ApiResponse.success(new ConfirmResponse(true, nextSeq));
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/nodes/tech-plan — manual TechPlan execution
    // ---------------------------------------------------------------------

    public record TechPlanExecuteResponse(long nextSeq) {}

    @PostMapping("/sessions/{sid}/nodes/tech-plan")
    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<TechPlanExecuteResponse> executeTechPlan(
            @PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        Optional<AgentSession> sessionOpt = sessionRepository.findById(backendId);
        if (sessionOpt.isEmpty()) {
            return ApiResponse.error(404, "session row missing: " + backendId);
        }

        // Load prior checkpoint outputs for impact + implement, and the
        // original intent from the initial session input.
        Map<String, Object> impactOutput = findLatestCheckpointOutput(backendId, "impact");
        Map<String, Object> implementOutput = findLatestCheckpointOutput(backendId, "implement");
        String intent = findSessionIntent(backendId);

        // Build TechPlanNode input from prior outputs (key names must match
        // what TechPlanNode.execute() reads: impact, implement, intent, projectPath)
        Map<String, Object> techPlanInput = new LinkedHashMap<>();
        if (impactOutput != null) {
            techPlanInput.put("impact", impactOutput);
        }
        if (implementOutput != null) {
            techPlanInput.put("implement", implementOutput);
        }
        if (intent != null) {
            techPlanInput.put("intent", intent);
        }
        log.info("[RAM][tech-plan] loaded inputs: impact={} implement={} intent={}",
                impactOutput != null ? impactOutput.keySet() : "null",
                implementOutput != null ? implementOutput.keySet() : "null",
                intent != null ? "present" : "null");
        // Carry project path from the initial input if available
        Map<String, Object> initialInput = findSessionInitialInput(backendId);
        Object projectPath = initialInput.get("project_path");
        if (projectPath == null) {
            // Try projectHints (list) — take the first entry
            Object hints = initialInput.get("projectHints");
            if (hints instanceof java.util.List<?> list && !list.isEmpty()
                    && list.get(0) instanceof String s) {
                projectPath = s;
            }
        }
        if (projectPath instanceof String s && !s.isBlank()) {
            techPlanInput.put("projectPath", s);
        }

        long nextSeq = eventRepository.findMaxSeq(backendId);

        // Set session to RUNNING so SSE stays open and delivers the CHECKPOINT
        sessionOpt.get().setStatus(SessionStatus.RUNNING);
        sessionRepository.update(sessionOpt.get());

        // Async dispatch — same pattern as clarify/confirm endpoints.
        // TechPlanNode calls Claude with KG+FS tools (up to 10 rounds),
        // then the DagExecutor appends a CHECKPOINT event.
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[RAM][tech-plan] start handle={} backendId={} input.keys={}",
                        handle, backendId, techPlanInput.keySet());
                Map<String, Object> output = techPlanNode.execute(techPlanInput);
                Map<String, Object> safeOutput = output == null ? Map.of() : output;
                String inputsHash = InputsHasher.hash(techPlanInput);
                appendTechPlanCheckpoint(backendId, safeOutput, inputsHash);
                // Restore session to DONE after successful execution
                sessionRepository.updateStatus(backendId, SessionStatus.DONE);
                log.info("[RAM][tech-plan] done handle={} output.keys={}",
                        handle, safeOutput.keySet());
            } catch (Exception e) {
                log.error("[RAM][tech-plan] async dispatch failed handle={} error={}",
                        handle, e.getMessage(), e);
                appendTechPlanError(backendId, e);
                sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
            }
        }, asyncExecutor);

        return ApiResponse.success(new TechPlanExecuteResponse(nextSeq));
    }

    /**
     * Finds the most recent CHECKPOINT output for a given node name by scanning
     * session events in reverse order.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findLatestCheckpointOutput(long backendId, String nodeName) {
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CHECKPOINT) continue;
            Map<String, Object> payload = parseJsonPayload(ev.getPayload());
            if (payload == null) continue;
            if (!nodeName.equals(payload.get("nodeName"))) continue;
            Object out = payload.get("output");
            if (out instanceof Map<?, ?> m) {
                Map<String, Object> result = new LinkedHashMap<>();
                m.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
            return Map.of();
        }
        return null;
    }

    /**
     * Reconstructs the original user intent (requirement description) from
     * the session's initial input event.
     */
    private String findSessionIntent(long backendId) {
        Map<String, Object> initialInput = findSessionInitialInput(backendId);
        Object raw = initialInput.get("userRequirement");
        return raw instanceof String s ? s : null;
    }

    /**
     * Reconstructs the initial input map for a session from its USER_MSG event.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findSessionInitialInput(long backendId) {
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        for (AgentEvent ev : events) {
            if (ev.getType() == EventType.USER_MSG) {
                Map<String, Object> payload = parseJsonPayload(ev.getPayload());
                if (payload != null && payload.get("initialInput") instanceof Map<?, ?> init) {
                    return (Map<String, Object>) init;
                }
            }
        }
        return Map.of();
    }

    private void appendTechPlanCheckpoint(long backendId,
                                           Map<String, Object> output,
                                           String inputsHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "tech_plan");
        payload.put("inputsHash", inputsHash);
        payload.put("output", output);
        // Use timestamp in key to avoid idempotency collision on rerun:
        // same inputs can produce different LLM outputs, so old events must not be reused.
        String key = "ckpt-" + backendId + "-tech_plan-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(backendId)
                .type(EventType.CHECKPOINT)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .inputsHash(inputsHash)
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        eventRepository.append(ev);
    }

    private void appendTechPlanError(long backendId, Throwable t) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "tech_plan");
        payload.put("error", String.valueOf(t.getMessage()));
        payload.put("type", t.getClass().getName());
        String key = "error-" + backendId + "-tech_plan-" + System.nanoTime();
        AgentEvent ev = AgentEvent.builder()
                .sessionId(backendId)
                .type(EventType.ERROR)
                .payload(toJson(payload))
                .idempotencyKey(key)
                .circuitState("OK")
                .validatorStatus("FAIL")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        try {
            eventRepository.append(ev);
        } catch (RuntimeException e) {
            log.warn("appendTechPlanError failed for backendId={}", backendId, e);
        }
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/abort
    // ---------------------------------------------------------------------

    public record AbortResponse(boolean aborted) {}

    @PostMapping("/sessions/{sid}/abort")
    public ApiResponse<AbortResponse> abort(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
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
                .payload(toJson(payload))
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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize payload to JSON detail={}",
                    e.getOriginalMessage());
            return "{}";
        }
    }

    /** Test-only seam to pre-populate the UUID-to-backend mapping. */
    void registerSessionMapping(String handle, long backendId) {
        sessionMappingService.registerForTest(handle, backendId);
    }

    /** Test-only seam: visible to unit tests for invoking the SSE polling Runnable. */
    Set<String> abortedSessionsForTest() {
        return new LinkedHashSet<>(abortedSessions);
    }

    // ---------------------------------------------------------------------
    // GET /health — backend liveness + startedAt for restart detection
    // ---------------------------------------------------------------------

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of("status", "UP", "startedAt", startedAt));
    }

    // ---------------------------------------------------------------------
    // GET /sessions — list recent sessions for history/switching
    // ---------------------------------------------------------------------

    public record SessionSummary(String sessionId, String status, String currentNode,
                                  String intent, String projectPaths,
                                  String sessionType,
                                  long createdAt, long updatedAt) {}

    @GetMapping("/sessions")
    public ApiResponse<List<SessionSummary>> listSessions(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "sessionType", required = false) String sessionType) {
        List<AgentSession> sessions;
        if (sessionType != null && !sessionType.isBlank()) {
            sessions = sessionRepository.listRecentBySessionType(sessionType, Math.min(limit, 200));
        } else {
            sessions = sessionRepository.listRecentExcludingUserId("merge-analysis", Math.min(limit, 200));
        }
        List<SessionSummary> summaries = sessions.stream()
                .map(s -> new SessionSummary(
                        s.getUuid(),
                        s.getStatus() == null ? null : s.getStatus().name(),
                        s.getCurrentNode(),
                        s.getIntent(),
                        s.getProjectPaths(),
                        s.getSessionType() == null ? SessionType.DEMAND.name() : s.getSessionType().name(),
                        s.getCreatedAt() * 1000L,
                        s.getUpdatedAt() * 1000L))
                .toList();
        return ApiResponse.success(summaries);
    }

    // ---------------------------------------------------------------------
    // GET /sessions/{sid}/events — replay all events for session recovery
    // ---------------------------------------------------------------------

    @GetMapping("/sessions/{sid}/events")
    public ApiResponse<List<Map<String, Object>>> sessionEvents(
            @PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found");
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        List<Map<String, Object>> result = events.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seq", e.getSeq());
                    m.put("type", e.getType() == null ? null : e.getType().name());
                    m.put("payload", parsePayload(e.getPayload()));
                    m.put("createdAt", e.getCreatedAt() * 1000L);
                    return m;
                })
                .toList();
        return ApiResponse.success(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/rerun-from/{nodeName} — force re-execute from node
    // ---------------------------------------------------------------------

    @PostMapping("/sessions/{sid}/rerun-from/{nodeName}")
    public ApiResponse<Map<String, Object>> rerunFromNode(
            @PathVariable("sid") String handle,
            @PathVariable("nodeName") String nodeName) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found");

        AgentSession session = sessionRepository.findById(backendId).orElse(null);
        if (session == null) return ApiResponse.error(404, "session row missing");

        session.setRerunFromNode(nodeName);
        session.setStatus(SessionStatus.RUNNING);
        sessionRepository.update(session);
        log.info("[RAM][rerun-from] handle={} node={} — flag set, status=RUNNING, dispatching resume", handle, nodeName);

        // Append NODES_CLEARED event so frontend + page-refresh correctly reset downstream statuses
        List<String> clearedNodes = computeRamClearedNodes(nodeName);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("clearedNodes", clearedNodes);
            AgentEvent ev = AgentEvent.builder()
                    .sessionId(backendId)
                    .type(EventType.NODES_CLEARED)
                    .payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey("nodes-cleared-" + backendId + "-" + nodeName + "-" + System.nanoTime())
                    .circuitState("OK")
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build();
            eventRepository.append(ev);
        } catch (Exception e) {
            log.warn("[RAM][rerun-from] Failed to append NODES_CLEARED event: {}", e.getMessage());
        }

        // Record current max seq so frontend can use as afterSeq
        long nextSeq = eventRepository.findMaxSeq(backendId);

        CompletableFuture.runAsync(() -> {
            try {
                ramMcpServer.invoke("resume_session", Map.of("session_id", backendId));
            } catch (Exception e) {
                log.error("[RAM][rerun-from] async dispatch failed handle={} node={} error={}",
                        handle, nodeName, e.getMessage(), e);
            }
        }, asyncExecutor);

        return ApiResponse.success(Map.of("rerunFromNode", nodeName, "dispatched", true, "nextSeq", nextSeq));
    }

    private static List<String> computeRamClearedNodes(String fromNode) {
        String[] order = {"clarify", "impact", "implement", "verify", "tech_plan"};
        int idx = Arrays.asList(order).indexOf(fromNode);
        if (idx < 0) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = idx; i < order.length; i++) result.add(order[i]);
        return result;
    }

    // ---------------------------------------------------------------------
    // GET /sessions/{sid}/clarify-rounds — list all clarify Q&A rounds
    // ---------------------------------------------------------------------

    public record ClarifyRoundSummary(int roundNo, List<String> questions, Map<String, Object> answers) {}

    @GetMapping("/sessions/{sid}/clarify-rounds")
    public ApiResponse<List<ClarifyRoundSummary>> listClarifyRounds(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found");

        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        List<ClarifyRoundSummary> rounds = new ArrayList<>();
        List<String> pendingQuestions = null;
        Integer pendingRoundNo = null;

        for (AgentEvent ev : events) {
            if (ev.getType() == EventType.CLARIFY_REQ) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = parseJsonPayload(ev.getPayload());
                if (payload != null && payload.get("questions") instanceof List<?> qs) {
                    pendingQuestions = qs.stream()
                            .filter(q -> q instanceof String)
                            .map(q -> (String) q)
                            .toList();
                    pendingRoundNo = ev.getClarifyRoundNo();
                }
            } else if (ev.getType() == EventType.CLARIFY_RES && pendingQuestions != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = parseJsonPayload(ev.getPayload());
                Map<String, Object> answers = Map.of();
                if (payload != null && payload.get("answers") instanceof Map<?, ?> ans) {
                    answers = (Map<String, Object>) ans;
                }
                rounds.add(new ClarifyRoundSummary(
                        pendingRoundNo != null ? pendingRoundNo : rounds.size() + 1,
                        List.copyOf(pendingQuestions),
                        answers));
                pendingQuestions = null;
                pendingRoundNo = null;
            }
        }
        return ApiResponse.success(rounds);
    }

    // ---------------------------------------------------------------------
    // POST /sessions/{sid}/rerun-from-round/{roundNo} — rerun from clarify round
    // ---------------------------------------------------------------------

    @PostMapping("/sessions/{sid}/rerun-from-round/{roundNo}")
    public ApiResponse<Map<String, Object>> rerunFromRound(
            @PathVariable("sid") String handle,
            @PathVariable("roundNo") int roundNo) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found");

        AgentSession session = sessionRepository.findById(backendId).orElse(null);
        if (session == null) return ApiResponse.error(404, "session row missing");

        // From a specific clarify round, rerun impact + downstream nodes
        // (preserve all clarify history, just re-execute the analysis pipeline)
        session.setRerunFromNode("impact");
        session.setStatus(SessionStatus.RUNNING);
        sessionRepository.update(session);
        log.info("[RAM][rerun-from-round] handle={} roundNo={} — setting rerunFromNode=impact, status=RUNNING",
                handle, roundNo);

        // Append NODES_CLEARED event for impact + downstream
        List<String> clearedNodes = List.of("impact", "implement", "verify", "tech_plan");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("clearedNodes", clearedNodes);
            AgentEvent ev = AgentEvent.builder()
                    .sessionId(backendId)
                    .type(EventType.NODES_CLEARED)
                    .payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey("nodes-cleared-" + backendId + "-round-" + roundNo + "-" + System.nanoTime())
                    .circuitState("OK")
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build();
            eventRepository.append(ev);
        } catch (Exception e) {
            log.warn("[RAM][rerun-from-round] Failed to append NODES_CLEARED event: {}", e.getMessage());
        }

        long nextSeq = eventRepository.findMaxSeq(backendId);

        CompletableFuture.runAsync(() -> {
            try {
                ramMcpServer.invoke("resume_session", Map.of("session_id", backendId));
            } catch (Exception e) {
                log.error("[RAM][rerun-from-round] async dispatch failed handle={} roundNo={} error={}",
                        handle, roundNo, e.getMessage(), e);
            }
        }, asyncExecutor);

        return ApiResponse.success(Map.of(
                "rerunFromRound", roundNo,
                "rerunFromNode", "impact",
                "dispatched", true,
                "nextSeq", nextSeq));

    }
    // ---------------------------------------------------------------------
    // GET /sessions/{sid}/export/md — export session as Markdown
    // ---------------------------------------------------------------------

    @GetMapping("/sessions/{sid}/export/md")
    public ResponseEntity<byte[]> exportSessionAsMd(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            String mdContent = reportExportService.exportRamSessionAsMd(handle);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_MARKDOWN);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("ram-session-" + handle + ".md")
                    .build());
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(mdContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            log.warn("[RAM][export-md] session not found: handle={}", handle);
            return ResponseEntity.notFound().build();
        }
    }
}