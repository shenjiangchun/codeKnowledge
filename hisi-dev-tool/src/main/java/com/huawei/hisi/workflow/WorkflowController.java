package com.huawei.hisi.workflow;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unified workflow API — a single entry point for all workflow types.
 *
 * <p>Endpoints (all under {@code /api/v2/workflow}, see {@link com.huawei.hisi.workflow.v2.WorkflowV2Controller}):
 * <ul>
 *   <li>{@code GET /definitions} – list registered workflow definitions.</li>
 *   <li>{@code GET /nodes} – list available node types.</li>
 *   <li>{@code POST /start} – start a workflow session.</li>
 *   <li>{@code GET /sessions/{sid}/stream} – SSE event stream.</li>
 *   <li>{@code GET /sessions/{sid}/status} – session status.</li>
 *   <li>{@code GET /sessions/{sid}/report} – session report (latest checkpoint).</li>
 *   <li>{@code GET /sessions/{sid}/events} – all session events.</li>
 *   <li>{@code POST /sessions/{sid}/clarify} – submit clarification answers.</li>
 *   <li>{@code POST /sessions/{sid}/confirm} – HITL confirmation.</li>
 *   <li>{@code POST /sessions/{sid}/rerun-from/{nodeName}} – rerun from a node.</li>
 *   <li>{@code POST /sessions/{sid}/abort} – abort the session.</li>
 * </ul>
 *
 * <p>不再单独注册为控制器：/api/workflow 前缀已退役（前端只走 /api/v2/workflow），
 * 本类作为 {@link com.huawei.hisi.workflow.v2.WorkflowV2Controller} 的基类提供同一套端点实现。
 */
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    protected final WorkflowRegistry registry;
    protected final SessionMappingService sessionMappingService;
    protected final AgentSessionRepository sessionRepository;
    protected final AgentEventRepository eventRepository;
    protected final DagExecutor dagExecutor;

    @Value("${ram.sse.timeout-ms:300000}")
    private long sseTimeoutMs = 300_000;

    @Value("${ram.sse.poll-interval-ms:3000}")
    private long ssePollIntervalMs = 3_000;

    private final Set<String> abortedSessions = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "workflow-sse");
        t.setDaemon(true);
        return t;
    });

    public WorkflowController(WorkflowRegistry registry,
                              SessionMappingService sessionMappingService,
                              AgentSessionRepository sessionRepository,
                              AgentEventRepository eventRepository,
                              DagExecutor dagExecutor) {
        this.registry = registry;
        this.sessionMappingService = sessionMappingService;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.dagExecutor = dagExecutor;
    }

    // ──────────────────────────── Request / Response types ────────────────────────────

    public record StartRequest(String workflowType, Map<String, Object> input) {}
    public record StartResponse(String sessionId) {}
    public record StatusResponse(String status, String currentNode) {}
    public record ReportResponse(String status, Map<String, Object> report) {}
    public record ClarifyRequest(Map<String, Object> answers) {}
    public record ConfirmRequest(String action, String feedback, Map<String, Object> editedOutput) {}
    public record NodeInfo(String name, String agentId) {}

    // ──────────────────────────── Start endpoint ────────────────────────────

    /**
     * 通用工作流启动端点（移植自 RamController 启动路径）：
     * Registry 校验 type → 种 agent_session（UUID→id 映射先行注册）→ 异步执行 DAG → 返回 sessionId。
     * 前端经 {@code GET /sessions/{sid}/stream} 订阅进度。
     */
    @PostMapping("/start")
    public ApiResponse<StartResponse> start(@RequestBody StartRequest request) {
        if (request == null || request.workflowType() == null || request.workflowType().isBlank()) {
            return ApiResponse.error(400, "workflowType is required");
        }
        WorkflowDefinition def = registry.getWorkflow(request.workflowType());
        if (def == null) {
            return ApiResponse.error(400, "unknown workflowType: " + request.workflowType());
        }
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        if (!(input.get("projectPaths") instanceof List<?> paths) || paths.isEmpty()) {
            return ApiResponse.error(400, "input.projectPaths is required");
        }

        // 种会话行：UUID→id 映射在异步执行前注册，SSE 可立即挂接
        String handle = UUID.randomUUID().toString();
        AgentSession seeded = sessionRepository.save(AgentSession.newRunning("anonymous", SessionType.WORKFLOW));
        long backendId = seeded.getId();
        seeded.setUuid(handle);
        sessionRepository.update(seeded);
        sessionMappingService.register(handle, backendId);
        log.info("[Workflow][start] type={} handle={} backendId={} projectPaths={}",
                request.workflowType(), handle, backendId, paths);

        List<DagNode> nodes = registry.resolveNodes(def.nodeNames());
        ExecutionPolicy policy = ExecutionPolicy.fromMetadata(def.metadata());
        CompletableFuture.runAsync(() -> dagExecutor.run(nodes, backendId, input, policy))
                .exceptionally(ex -> {
                    log.error("[Workflow][start] DAG execution failed type={} backendId={}",
                            request.workflowType(), backendId, ex);
                    sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
                    return null;
                });
        return ApiResponse.success(new StartResponse(handle));
    }

    // ──────────────────────────── Definition endpoints ────────────────────────────

    @GetMapping("/definitions")
    public ApiResponse<List<WorkflowDefinition>> listDefinitions() {
        return ApiResponse.success(registry.listWorkflows());
    }

    @GetMapping("/nodes")
    public ApiResponse<Map<String, NodeInfo>> listNodes() {
        Map<String, NodeInfo> result = new LinkedHashMap<>();
        registry.getAvailableNodes().forEach((name, node) ->
                result.put(name, new NodeInfo(name, node.agentId())));
        return ApiResponse.success(result);
    }

    // ──────────────────────────── Session endpoints ────────────────────────────

    @GetMapping("/sessions/{sid}/status")
    public ApiResponse<StatusResponse> getStatus(@PathVariable("sid") String handle) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found: " + handle);

        Optional<AgentSession> session = sessionRepository.findById(backendId);
        if (session.isEmpty()) return ApiResponse.error(404, "session not found");

        String status = session.get().getStatus() != null
                ? session.get().getStatus().name() : "UNKNOWN";
        String currentNode = session.get().getCurrentNode();
        return ApiResponse.success(new StatusResponse(status, currentNode));
    }

    @GetMapping("/sessions/{sid}/report")
    public ApiResponse<ReportResponse> getReport(@PathVariable("sid") String handle,
                                                  @RequestParam(value = "nodeName", required = false) String nodeName) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found: " + handle);

        Optional<AgentSession> session = sessionRepository.findById(backendId);
        if (session.isEmpty()) return ApiResponse.error(404, "session not found");

        String status = session.get().getStatus() != null
                ? session.get().getStatus().name() : "UNKNOWN";

        // If nodeName specified, find that node's checkpoint; otherwise find the latest
        String targetNode = (nodeName != null && !nodeName.isBlank()) ? nodeName : null;
        Map<String, Object> report = findCheckpointOutput(backendId, targetNode);

        return ApiResponse.success(new ReportResponse(status, report));
    }

    @GetMapping("/sessions/{sid}/events")
    public ApiResponse<List<Map<String, Object>>> getEvents(@PathVariable("sid") String handle) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found: " + handle);

        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        List<Map<String, Object>> result = events.stream()
                .map(this::toEventMap)
                .toList();
        return ApiResponse.success(result);
    }

    // ──────────────────────────── SSE stream ────────────────────────────

    @GetMapping(value = "/sessions/{sid}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable("sid") String handle,
                             @RequestParam(value = "afterSeq", required = false) Long afterSeq,
                             HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        AtomicLong lastSeq = new AtomicLong(afterSeq == null ? 0L : Math.max(0L, afterSeq));
        AtomicLong waitTicks = new AtomicLong(0L);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        Runnable tick = () -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));

                if (abortedSessions.contains(handle)) {
                    sendSseEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                            "RUN_ABORTED", Map.of("reason", "user_requested")));
                    emitter.complete();
                    return;
                }

                Long backendId = sessionMappingService.resolveBackendId(handle);
                if (backendId == null) {
                    if (waitTicks.incrementAndGet() > 120L) {
                        emitter.completeWithError(new IllegalStateException("session not started: " + handle));
                    }
                    return;
                }

                List<AgentEvent> events = eventRepository.findBySessionId(backendId);
                for (AgentEvent ev : events) {
                    if (ev.getSeq() <= lastSeq.get()) continue;
                    sendSseEvent(emitter, toEventMap(ev));
                    lastSeq.set(ev.getSeq());

                    if (ev.getType() == EventType.CLARIFY_REQ) {
                        sendSseEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "CLARIFY_REQUIRED", parsePayload(ev.getPayload())));
                        emitter.complete();
                        return;
                    }
                    if (ev.getType() == EventType.HITL_REQ) {
                        sendSseEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(),
                                "HITL_REQUIRED", parsePayload(ev.getPayload())));
                        emitter.complete();
                        return;
                    }
                }

                Optional<AgentSession> session = sessionRepository.findById(backendId);
                if (session.isPresent()) {
                    SessionStatus status = session.get().getStatus();
                    if (status == SessionStatus.DONE) {
                        sendSseEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(), "RUN_COMPLETED", Map.of()));
                        emitter.complete();
                    } else if (status == SessionStatus.FAILED) {
                        sendSseEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(), "RUN_FAILED", Map.of()));
                        emitter.complete();
                    } else if (status == SessionStatus.ABORTED) {
                        sendSseEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(), "RUN_ABORTED", Map.of()));
                        emitter.complete();
                    }
                }
            } catch (IllegalStateException | IOException e) {
                ScheduledFuture<?> f = futureRef.get();
                if (f != null) f.cancel(true);
            } catch (Exception e) {
                if (causedByDisconnectedClient(e)) {
                    // 浏览器关页面、刷新或断网后连接已断：推送不出去是正常现象，停掉轮询即可，不是告警
                    ScheduledFuture<?> f = futureRef.get();
                    if (f != null) f.cancel(true);
                    log.debug("[SSE] client disconnected handle={}", handle);
                    return;
                }
                log.warn("[SSE] tick failed for handle={} error={}", handle, e.toString());
            }
        };

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(tick, 0, ssePollIntervalMs, TimeUnit.MILLISECONDS);
        futureRef.set(future);

        emitter.onTimeout(() -> { future.cancel(true); emitter.complete(); });
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onError(e -> future.cancel(true));

        return emitter;
    }

    /** 异常链里是否有「响应已经写不回去」（Spring 包装的 AsyncRequestNotUsableException 或底层的 IO 异常）。 */
    static boolean causedByDisconnectedClient(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException
                    || cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    // ──────────────────────────── Lifecycle endpoints ────────────────────────────

    @PostMapping("/sessions/{sid}/abort")
    public ApiResponse<Void> abort(@PathVariable("sid") String handle) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) return ApiResponse.error(404, "session not found");

        abortedSessions.add(handle);
        sessionRepository.updateStatus(backendId, SessionStatus.ABORTED);
        return ApiResponse.success(null);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    protected Long resolveBackendId(String handle) {
        return sessionMappingService.resolveBackendId(handle);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> result = new LinkedHashMap<>();
                m.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
            return Map.of("value", parsed);
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    private Map<String, Object> toEventMap(AgentEvent ev) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", ev.getSeq());
        map.put("type", ev.getType() != null ? ev.getType().name() : "UNKNOWN");
        map.put("payload", parsePayload(ev.getPayload()));
        if (ev.getClarifyRoundNo() != null) map.put("clarifyRoundNo", ev.getClarifyRoundNo());
        return map;
    }

    private Map<String, Object> syntheticEvent(long seq, String type, Map<String, Object> payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", seq);
        map.put("type", type);
        map.put("payload", payload);
        return map;
    }

    private void sendSseEvent(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Find the latest CHECKPOINT output for a specific node, or the latest checkpoint overall.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findCheckpointOutput(long backendId, String nodeName) {
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CHECKPOINT) continue;
            Map<String, Object> payload = parsePayload(ev.getPayload());
            if (payload == null) continue;
            if (nodeName != null && !nodeName.equals(payload.get("nodeName"))) continue;
            Object out = payload.get("output");
            if (out instanceof Map<?, ?> m) {
                Map<String, Object> result = new LinkedHashMap<>();
                m.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
            return Map.of();
        }
        return Map.of();
    }
}
