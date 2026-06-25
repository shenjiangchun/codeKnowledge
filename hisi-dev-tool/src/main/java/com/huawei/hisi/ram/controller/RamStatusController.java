package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.nodes.ProjectOverviewNode;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for project status analysis (项目现状分析).
 *
 * <p>Endpoints (all under {@code /api/ram/status}):
 * <ul>
 *   <li>{@code POST /start} – starts a project status analysis session.</li>
 *   <li>{@code GET /{sid}/report} – returns the latest project_overview checkpoint output.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ram/status")
public class RamStatusController {

    private static final Logger log = LoggerFactory.getLogger(RamStatusController.class);

    private final SessionMappingService sessionMappingService;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final ProjectOverviewNode projectOverviewNode;
    private final ExecutorService asyncExecutor;

    @Autowired
    public RamStatusController(SessionMappingService sessionMappingService,
                                AgentSessionRepository sessionRepository,
                                AgentEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                ProjectOverviewNode projectOverviewNode) {
        this.sessionMappingService = sessionMappingService;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.projectOverviewNode = projectOverviewNode;
        this.asyncExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ram-status-async");
            t.setDaemon(true);
            return t;
        });
    }

    public record StatusStartRequest(String projectPath, String mode, String question) {}
    public record StatusStartResponse(String sessionId) {}
    public record StatusReportResponse(String status, Map<String, Object> report) {}

    /**
     * Start a project status analysis session.
     * POST /api/ram/status/start
     *
     * <p>Creates a new session and executes ProjectOverviewNode asynchronously.</p>
     */
    @PostMapping("/start")
    public ApiResponse<StatusStartResponse> startStatusAnalysis(@RequestBody StatusStartRequest request) {
        log.info("[RAM][POST /status/start] request={}", request);
        if (request == null || request.projectPath() == null || request.projectPath().isBlank()) {
            return ApiResponse.error(400, "projectPath is required");
        }

        String handle = UUID.randomUUID().toString();
        String mode = request.mode() != null ? request.mode() : "quick";
        String question = request.question() != null ? request.question() : "";

        // Pre-create session
        AgentSession seeded = sessionRepository.save(AgentSession.newRunning("status-analysis", SessionType.STATUS));
        long backendId = seeded.getId();
        seeded.setUuid(handle);
        seeded.setIntent("项目现状分析: " + (question.isBlank() ? request.projectPath() : question));
        try {
            seeded.setProjectPaths(objectMapper.writeValueAsString(List.of(request.projectPath())));
        } catch (Exception ignored) {}
        sessionRepository.update(seeded);
        sessionMappingService.register(handle, backendId);

        log.info("[RAM][POST /status/start] seeded session handle={} backendId={} projectPath={} mode={}",
                handle, backendId, request.projectPath(), mode);

        // Async execution with 5-minute timeout
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("projectPath", request.projectPath());
                input.put("mode", mode);
                input.put("question", question);

                log.info("[RAM][status] executing ProjectOverviewNode for handle={} backendId={} question={}", handle, backendId, question);
                Map<String, Object> output = projectOverviewNode.execute(input);
                Map<String, Object> safeOutput = output == null ? Map.of() : output;

                // Append CHECKPOINT event
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("nodeName", "project_overview");
                payload.put("output", safeOutput);
                String key = "ckpt-" + backendId + "-project_overview-" + System.nanoTime();
                AgentEvent ev = AgentEvent.builder()
                        .sessionId(backendId)
                        .type(EventType.CHECKPOINT)
                        .payload(toJson(payload))
                        .idempotencyKey(key)
                        .circuitState("OK")
                        .validatorStatus("OK")
                        .createdAt(System.currentTimeMillis() / 1000L)
                        .build();
                eventRepository.append(ev);

                sessionRepository.updateStatus(backendId, SessionStatus.DONE);
                log.info("[RAM][status] done handle={} output.keys={}", handle, safeOutput.keySet());
            } catch (Exception e) {
                log.error("[RAM][status] async dispatch failed handle={} error={}", handle, e.getMessage(), e);
                appendStatusError(backendId, e);
                sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
            }
        }, asyncExecutor)
        .orTimeout(5, TimeUnit.MINUTES)
        .exceptionally(ex -> {
            log.error("[RAM][status] timeout for handle={} backendId={}: {}", handle, backendId, ex.getMessage());
            appendStatusError(backendId, "Analysis timed out after 5 minutes: " + ex.getMessage());
            sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
            return null;
        });

        return ApiResponse.success(new StatusStartResponse(handle));
    }

    /**
     * Get the status analysis report.
     * GET /api/ram/status/{sessionId}/report
     *
     * <p>Returns the latest project_overview CHECKPOINT output.</p>
     */
    @GetMapping("/{sid}/report")
    public ApiResponse<StatusReportResponse> getStatusReport(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }

        Optional<AgentSession> sessionOpt = sessionRepository.findById(backendId);
        if (sessionOpt.isEmpty()) {
            return ApiResponse.error(404, "session not found: " + backendId);
        }

        String statusStr = sessionOpt.get().getStatus() != null
                ? sessionOpt.get().getStatus().name() : "UNKNOWN";

        Map<String, Object> report = findLatestCheckpointOutput(backendId, "project_overview");
        if (report == null) {
            // Return default structure with all expected fields when checkpoint not found
            report = createDefaultStatusReport(statusStr);
        }

        return ApiResponse.success(new StatusReportResponse(statusStr, report));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonPayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
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

    /**
     * Create default status report with all expected frontend fields.
     * Note: Only set success=false for FAILED status; RUNNING should use null
     * to avoid frontend misjudging as failed.
     */
    private Map<String, Object> createDefaultStatusReport(String status) {
        Map<String, Object> report = new LinkedHashMap<>();
        // Only mark success=false for FAILED; RUNNING uses null to avoid red status tag
        report.put("success", "FAILED".equals(status) ? false : null);
        report.put("message", status.equals("RUNNING")
                ? "分析正在执行中，请稍候..."
                : "分析尚未完成或未生成结果");
        report.put("markdown_report", "");
        report.put("question", "");
        report.put("entry_points_summary", "");
        report.put("core_call_chains", List.of());
        report.put("tech_stack", Map.of());
        report.put("recommendations", List.of());
        return report;
    }

    private void appendStatusError(long backendId, Throwable t) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "project_overview");
        payload.put("error", String.valueOf(t.getMessage()));
        payload.put("type", t.getClass().getName());
        String key = "error-" + backendId + "-project_overview-" + System.nanoTime();
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
            log.warn("appendStatusError failed for backendId={}", backendId, e);
        }
    }

    private void appendStatusError(long backendId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "project_overview");
        payload.put("error", message);
        payload.put("type", "TimeoutError");
        String key = "error-" + backendId + "-project_overview-" + System.nanoTime();
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
            log.warn("appendStatusError failed for backendId={}", backendId, e);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize payload to JSON: {}", e.getOriginalMessage());
            return "{}";
        }
    }
}