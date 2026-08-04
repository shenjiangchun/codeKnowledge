package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.Phase2Context;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.nodes.Phase2AnalysisNode;
import com.huawei.hisi.ram.nodes.Phase2LlmClient;
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
 * REST controller for Phase2 precise location analysis (精确位置分析).
 *
 * <p>Endpoints (all under {@code /api/ram/status/phase2}):
 * <ul>
 *   <li>{@code POST /start} – starts a phase2 analysis session.</li>
 *   <li>{@code GET /{sid}/report} – returns the latest phase2_analysis checkpoint output.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ram/status/phase2")
public class RamPhase2Controller {

    private static final Logger log = LoggerFactory.getLogger(RamPhase2Controller.class);

    private final SessionMappingService sessionMappingService;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Phase2AnalysisNode phase2AnalysisNode;
    private final Phase2LlmClient phase2LlmClient;
    private final ExecutorService asyncExecutor;

    @Autowired
    public RamPhase2Controller(SessionMappingService sessionMappingService,
                                AgentSessionRepository sessionRepository,
                                AgentEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                Phase2AnalysisNode phase2AnalysisNode,
                                Phase2LlmClient phase2LlmClient) {
        this.sessionMappingService = sessionMappingService;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.phase2AnalysisNode = phase2AnalysisNode;
        this.phase2LlmClient = phase2LlmClient;
        this.asyncExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ram-phase2-async");
            t.setDaemon(true);
            return t;
        });
    }

    public record Phase2StartRequest(String sessionId, String question, java.util.List<String> focusAreas) {}
    public record Phase2StartResponse(String phase2SessionId, String status) {}
    public record Phase2ReportResponse(String status, Map<String, Object> report) {}

    /**
     * Start a phase 2 precise location analysis.
     * POST /api/ram/status/phase2/start
     *
     * <p>Creates a new session and executes Phase2AnalysisNode asynchronously,
     * then generates a precise location report via Phase2LlmClient.</p>
     */
    @PostMapping("/start")
    public ApiResponse<Phase2StartResponse> startPhase2Analysis(@RequestBody Phase2StartRequest request) {
        log.info("[RAM][POST /status/phase2/start] request={}", request);
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error(400, "sessionId is required");
        }
        if (request.question() == null || request.question().isBlank()) {
            return ApiResponse.error(400, "question is required");
        }

        // Resolve the parent session to get projectPath
        Long parentBackendId = sessionMappingService.resolveBackendId(request.sessionId());
        if (parentBackendId == null) {
            return ApiResponse.error(404, "parent session not found: " + request.sessionId());
        }
        Optional<AgentSession> parentSession = sessionRepository.findById(parentBackendId);
        if (parentSession.isEmpty()) {
            return ApiResponse.error(404, "parent session row missing: " + parentBackendId);
        }

        // Extract projectPath from parent session
        String projectPath = extractProjectPath(parentSession.get());
        if (projectPath == null || projectPath.isBlank()) {
            return ApiResponse.error(400, "parent session has no projectPath");
        }

        // Create a new phase2 session with a new UUID
        String handle = UUID.randomUUID().toString();
        AgentSession seeded = sessionRepository.save(AgentSession.newRunning("phase2-analysis", SessionType.PHASE2));
        long backendId = seeded.getId();
        seeded.setUuid(handle);
        seeded.setIntent("Phase2精确位置分析: " + request.question());
        try {
            seeded.setProjectPaths(objectMapper.writeValueAsString(List.of(projectPath)));
        } catch (Exception ignored) {}
        sessionRepository.update(seeded);
        sessionMappingService.register(handle, backendId);

        log.info("[RAM][POST /status/phase2/start] seeded session handle={} backendId={} parentSession={} projectPath={} question={}",
                handle, backendId, request.sessionId(), projectPath, request.question());

        // Async execution with 5-minute timeout
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("projectPath", projectPath);
                input.put("question", request.question());
                if (request.focusAreas() != null && !request.focusAreas().isEmpty()) {
                    input.put("focusAreas", request.focusAreas());
                }

                log.info("[RAM][phase2] executing Phase2AnalysisNode for handle={} backendId={} question={}",
                        handle, backendId, request.question());

                // Step 1: KG data collection
                Map<String, Object> kgOutput = phase2AnalysisNode.execute(input);
                if (kgOutput == null || !Boolean.TRUE.equals(kgOutput.get("success"))) {
                    log.error("[RAM][phase2] Phase2AnalysisNode failed for handle={}", handle);
                    appendPhase2Error(backendId, "Phase2AnalysisNode execution failed");
                    sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
                    return;
                }

                // Extract Phase2Context from output
                Object contextObj = kgOutput.get("phase2_context");
                if (!(contextObj instanceof Phase2Context context)) {
                    log.error("[RAM][phase2] Phase2Context missing for handle={}", handle);
                    appendPhase2Error(backendId, "Phase2Context missing from KG output");
                    sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
                    return;
                }

                // Remove Phase2Context from kgOutput before serialization —
                // it uses record-style getters that Jackson can't serialize.
                kgOutput.remove("phase2_context");

                // Step 2: LLM report generation
                log.info("[RAM][phase2] generating LLM report for handle={} backendId={}", handle, backendId);
                Map<String, Object> llmOutput = phase2LlmClient.generate(context, projectPath);
                Map<String, Object> safeOutput = llmOutput == null ? Map.of() : llmOutput;

                // Append CHECKPOINT event
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("nodeName", "phase2_analysis");
                payload.put("output", safeOutput);
                payload.put("kgOutput", kgOutput);
                String key = "ckpt-" + backendId + "-phase2_analysis-" + System.nanoTime();
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
                log.info("[RAM][phase2] done handle={} output.keys={}", handle, safeOutput.keySet());
            } catch (Exception e) {
                log.error("[RAM][phase2] async dispatch failed handle={} error={}", handle, e.getMessage(), e);
                appendPhase2Error(backendId, e);
                sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
            }
        }, asyncExecutor)
        .orTimeout(5, TimeUnit.MINUTES)
        .exceptionally(ex -> {
            log.error("[RAM][phase2] timeout or failure for handle={} backendId={}: {}",
                    handle, backendId, ex.getMessage());
            appendPhase2Error(backendId, "Analysis timed out after 5 minutes: " + ex.getMessage());
            sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
            return null;
        });

        return ApiResponse.success(new Phase2StartResponse(handle, "RUNNING"));
    }

    /**
     * Get the phase2 analysis report.
     * GET /api/ram/status/phase2/{sessionId}/report
     *
     * <p>Returns the latest phase2_analysis CHECKPOINT output.</p>
     */
    @GetMapping("/{sid}/report")
    public ApiResponse<Phase2ReportResponse> getPhase2Report(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }

        Optional<AgentSession> sessionOpt = sessionRepository.findById(backendId);
        if (sessionOpt.isEmpty()) {
            return ApiResponse.error(404, "session not found: " + backendId);
        }

        String status = sessionOpt.get().getStatus() != null
                ? sessionOpt.get().getStatus().name() : "UNKNOWN";

        // Get full payload including both LLM output and KG data
        Map<String, Object> report = findLatestPhase2Checkpoint(backendId);
        if (report == null) {
            report = Map.of("success", false, "message", "分析尚未完成或未生成结果");
        }

        return ApiResponse.success(new Phase2ReportResponse(status, report));
    }

    /**
     * Extract projectPath from parent session's projectPaths field.
     */
    @SuppressWarnings("unchecked")
    private String extractProjectPath(AgentSession session) {
        String projectPathsJson = session.getProjectPaths();
        if (projectPathsJson == null || projectPathsJson.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(projectPathsJson, Object.class);
            if (parsed instanceof java.util.List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
            return String.valueOf(parsed);
        } catch (Exception e) {
            log.warn("[RAM] Failed to parse projectPaths from session {}: {}", session.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Find the latest phase2_analysis checkpoint and merge KG output with LLM output.
     * This is needed because frontend expects KG fields (core_methods, upstream_chains, etc.)
     * which are stored in kgOutput, not in the LLM output.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findLatestPhase2Checkpoint(long backendId) {
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CHECKPOINT) continue;
            Map<String, Object> payload = parseJsonPayload(ev.getPayload());
            if (payload == null) continue;
            if (!"phase2_analysis".equals(payload.get("nodeName"))) continue;

            // Merge output (LLM) and kgOutput (KG data)
            Map<String, Object> result = new LinkedHashMap<>();

            // Add KG output fields first (these are what frontend expects for detailed data)
            Object kgOut = payload.get("kgOutput");
            if (kgOut instanceof Map<?, ?> kg) {
                kg.forEach((k, v) -> result.put(String.valueOf(k), v));
            }

            // Add/override with LLM output (for markdown_report, analysis_summary, etc.)
            Object out = payload.get("output");
            if (out instanceof Map<?, ?> llm) {
                llm.forEach((k, v) -> result.put(String.valueOf(k), v));
            }

            return result;
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

    private void appendPhase2Error(long backendId, Throwable t) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "phase2_analysis");
        payload.put("error", String.valueOf(t.getMessage()));
        payload.put("type", t.getClass().getName());
        String key = "error-" + backendId + "-phase2_analysis-" + System.nanoTime();
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
            log.warn("appendPhase2Error failed for backendId={}", backendId, e);
        }
    }

    private void appendPhase2Error(long backendId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "phase2_analysis");
        payload.put("error", message);
        payload.put("type", "ExecutionError");
        String key = "error-" + backendId + "-phase2_analysis-" + System.nanoTime();
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
            log.warn("appendPhase2Error failed for backendId={}", backendId, e);
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