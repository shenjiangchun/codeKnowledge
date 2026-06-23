package com.huawei.hisi.mergeanalysis.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.service.DiffExtractService;
import com.huawei.hisi.mergeanalysis.service.MergeAnalysisService;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.service.ReportExportService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/merge-analysis")
@Slf4j
@DependsOn("ramSchemaInitializer")
public class MergeAnalysisController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long POLL_INTERVAL_MS = 500L;

    private final DiffExtractService diffExtractService;
    private final MergeAnalysisService mergeAnalysisService;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final ReportExportService reportExportService;

    private final Map<String, Long> sessionIdMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService streamScheduler = Executors.newScheduledThreadPool(2);
    private final long startedAt = System.currentTimeMillis();

    private static final int MAX_SESSION_MAPPINGS = 10_000;

    public MergeAnalysisController(DiffExtractService diffExtractService,
                                   MergeAnalysisService mergeAnalysisService,
                                   AgentSessionRepository sessionRepository,
                                   AgentEventRepository eventRepository,
                                   ObjectMapper objectMapper,
                                   ReportExportService reportExportService) {
        this.diffExtractService = diffExtractService;
        this.mergeAnalysisService = mergeAnalysisService;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.reportExportService = reportExportService;
    }
    @PreDestroy
    void shutdown() {
        streamScheduler.shutdownNow();
    }

    @PostConstruct
    void recoverSessionMappings() {
        try {
            for (AgentSession s : sessionRepository.listRecentByUserId("merge-analysis", MAX_SESSION_MAPPINGS)) {
                if (s.getUuid() != null && !s.getUuid().isBlank()) {
                    sessionIdMap.put(s.getUuid(), s.getId());
                }
            }
            log.info("[MergeAnalysis] Recovered {} session mappings from DB", sessionIdMap.size());
        } catch (Exception e) {
            log.warn("[MergeAnalysis] Failed to recover session mappings", e);
        }
    }

    private Long resolveBackendId(String handle) {
        Long id = sessionIdMap.get(handle);
        if (id != null) return id;
        Optional<AgentSession> found = sessionRepository.findByUuid(handle);
        if (found.isPresent()) {
            id = found.get().getId();
            sessionIdMap.put(handle, id);
            return id;
        }
        return null;
    }

    public record DiffRequest(String projectPath, String sourceBranch, String targetBranch) {}
    public record StartRequest(String projectPath, String sourceBranch, String targetBranch) {}
    public record StartResponse(String sessionHandle) {}
    public record SessionInfo(String status, String currentNode, long lastSeq) {}

    @GetMapping("/branches")
    public ApiResponse<List<String>> listBranches(@RequestParam String projectPath) {
        List<String> branches = diffExtractService.listBranches(projectPath);
        return ApiResponse.success(branches);
    }

    @PostMapping("/diff")
    public ApiResponse<DiffResult> getDiff(@RequestBody DiffRequest request) {
        DiffResult result = diffExtractService.extractDiff(
                request.projectPath(), request.sourceBranch(), request.targetBranch());
        return ApiResponse.success(result);
    }

    @PostMapping("/sessions")
    public ApiResponse<StartResponse> startSession(@RequestBody StartRequest request) {
        String handle = UUID.randomUUID().toString();
        long id = mergeAnalysisService.createSession(
                request.projectPath(), request.sourceBranch(), request.targetBranch());
        sessionIdMap.put(handle, id);
        // Store uuid/intent for history list and restart recovery
        // (projectPaths/sourceBranch/targetBranch already set in createSession)
        sessionRepository.findById(id).ifPresent(s -> {
            s.setUuid(handle);
            s.setIntent("合入分析: " + request.sourceBranch() + " → " + request.targetBranch());
            sessionRepository.update(s);
        });
        mergeAnalysisService.runAnalysis(id, request.projectPath(), request.sourceBranch(), request.targetBranch());
        return ApiResponse.success(new StartResponse(handle));
    }

    @GetMapping("/sessions/{sid}")
    public ApiResponse<SessionInfo> getSession(@PathVariable("sid") String handle) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "Session not found");
        }
        Optional<AgentSession> session = sessionRepository.findById(backendId);
        if (session.isEmpty()) {
            return ApiResponse.error(404, "Session not found");
        }
        AgentSession s = session.get();
        long lastSeq = eventRepository.findMaxSeq(backendId);
        return ApiResponse.success(new SessionInfo(s.getStatus().name(), s.getCurrentNode(), lastSeq));
    }

    @PostMapping("/sessions/{sid}/rerun-from/{nodeName}")
    public ApiResponse<Map<String, Object>> rerunFromNode(
            @PathVariable("sid") String handle,
            @PathVariable("nodeName") String nodeName) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "Session not found");
        }
        Optional<AgentSession> sessionOpt = sessionRepository.findById(backendId);
        if (sessionOpt.isEmpty()) {
            return ApiResponse.error(404, "Session not found");
        }

        AgentSession session = sessionOpt.get();
        session.setRerunFromNode(nodeName);
        session.setStatus(SessionStatus.RUNNING);
        sessionRepository.update(session);

        // Append NODES_CLEARED event
        List<String> clearedNodes = computeMergeClearedNodes(nodeName);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("clearedNodes", clearedNodes);
            AgentEvent ev = AgentEvent.builder()
                    .sessionId(backendId)
                    .type(com.huawei.hisi.ram.model.EventType.NODES_CLEARED)
                    .payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey("nodes-cleared-" + backendId + "-" + nodeName + "-" + System.nanoTime())
                    .circuitState("OK")
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build();
            eventRepository.append(ev);
        } catch (Exception e) {
            log.warn("[MergeAnalysis] Failed to append NODES_CLEARED event: {}", e.getMessage());
        }

        long nextSeq = eventRepository.findMaxSeq(backendId);

        mergeAnalysisService.rerunFromNode(backendId, nodeName);

        return ApiResponse.success(Map.of(
                "rerunFromNode", nodeName,
                "dispatched", true,
                "nextSeq", nextSeq));
    }

    private static List<String> computeMergeClearedNodes(String fromNode) {
        String[] order = {"diff_extract", "impact_analysis", "test_scope"};
        int idx = Arrays.asList(order).indexOf(fromNode);
        if (idx < 0) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = idx; i < order.length; i++) result.add(order[i]);
        return result;
    }

    @GetMapping("/sessions/{sid}/stream")
    public SseEmitter stream(@PathVariable("sid") String handle,
                             @RequestParam(value = "afterSeq", required = false) Long afterSeq) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicLong lastSeq = new AtomicLong(afterSeq == null ? 0L : Math.max(0L, afterSeq));

        Runnable tick = () -> {
            try {
                Long backendId = resolveBackendId(handle);
                if (backendId == null) {
                    return;
                }

                List<AgentEvent> events = eventRepository.findBySessionId(backendId);
                for (AgentEvent ev : events) {
                    if (ev.getSeq() <= lastSeq.get()) {
                        continue;
                    }
                    sendEvent(emitter, toSseMap(ev));
                    lastSeq.set(ev.getSeq());
                }

                Optional<AgentSession> session = sessionRepository.findById(backendId);
                if (session.isPresent()) {
                    SessionStatus status = session.get().getStatus();
                    if (status == SessionStatus.DONE) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(), "RUN_COMPLETED", Map.of()));
                        emitter.complete();
                    } else if (status == SessionStatus.FAILED) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(), "RUN_FAILED", Map.of()));
                        emitter.complete();
                    }
                }
            } catch (ClientGoneException e) {
                log.debug("SSE client gone for merge-analysis handle {}", handle);
                try { emitter.complete(); } catch (Exception ignored) {}
            } catch (Exception e) {
                log.warn("SSE tick failed for merge-analysis handle {}", handle, e);
                emitter.completeWithError(e);
            }
        };

        var future = streamScheduler.scheduleAtFixedRate(tick, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(t -> future.cancel(true));
        return emitter;
    }

    // ──────────────── History & Events ────────────────

    public record SessionSummary(
            String sessionId, String status, String currentNode,
            String intent, String projectPaths,
            String sourceBranch, String targetBranch,
            long createdAt, long updatedAt) {}

    @GetMapping("/sessions")
    public ApiResponse<List<SessionSummary>> listSessions(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<SessionSummary> out = new ArrayList<>();
        for (AgentSession s : sessionRepository.listRecentByUserId("merge-analysis", limit)) {
            String intentText = s.getIntent();
            if (intentText != null && intentText.length() > 80) {
                intentText = intentText.substring(0, 80) + "...";
            }
            out.add(new SessionSummary(
                    s.getUuid(),
                    s.getStatus() != null ? s.getStatus().name() : null,
                    s.getCurrentNode(),
                    intentText,
                    s.getProjectPaths(),
                    s.getSourceBranch(),
                    s.getTargetBranch(),
                    s.getCreatedAt() * 1000L,
                    s.getUpdatedAt() * 1000L));
        }
        return ApiResponse.success(out);
    }

    @GetMapping("/sessions/{sid}/events")
    public ApiResponse<List<Map<String, Object>>> listEvents(@PathVariable("sid") String handle) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "Session not found");
        }
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentEvent ev : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", ev.getSeq());
            row.put("type", ev.getType() == null ? null : ev.getType().name());
            row.put("payload", parseJsonPayload(ev.getPayload()));
            row.put("createdAt", ev.getCreatedAt() * 1000L);
            out.add(row);
        }
        return ApiResponse.success(out);
    }

    // ──────────────── SSE helpers ────────────────

    private void sendEvent(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().data(payload));
        } catch (IOException e) {
            throw new ClientGoneException(e);
        } catch (IllegalStateException e) {
            throw new ClientGoneException(e);
        }
    }

    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) { super(cause); }
    }

    private Map<String, Object> toSseMap(AgentEvent ev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", ev.getSeq());
        out.put("type", ev.getType() == null ? null : ev.getType().name());
        out.put("payload", parseJsonPayload(ev.getPayload()));
        return out;
    }

    private Map<String, Object> syntheticEvent(long seq, String type, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", seq);
        out.put("type", type);
        out.put("payload", data);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("raw", payload);
        }
    }

    // ──────────────── Export ────────────────

    /**
     * 导出合入分析报告为 Markdown 文件
     *
     * @param handle 会话 UUID
     * @return Markdown 文件字节流
     */
    @GetMapping("/sessions/{sid}/export/md")
    public ResponseEntity<byte[]> exportReportMd(@PathVariable("sid") String handle) {
        Long backendId = resolveBackendId(handle);
        if (backendId == null) {
            return ResponseEntity.status(404)
                    .body(("Session not found: " + handle).getBytes(StandardCharsets.UTF_8));
        }

        try {
            String mdContent = reportExportService.exportMergeReportAsMd(handle);
            String filename = "merge-report-" + handle + ".md";
            
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/markdown"))
                    .body(mdContent.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }
}
