package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.phase2v2.Phase2V2Orchestrator;
import com.huawei.hisi.ram.phase2v2.model.Phase2V2Report;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for Phase2 V2 multi-agent orchestration analysis.
 */
@Slf4j
@RestController
@RequestMapping("/api/ram/status/phase2/v2")
@RequiredArgsConstructor
public class RamPhase2V2Controller {

    private final Phase2V2Orchestrator orchestrator;
    private final SessionMappingService sessionMappingService;
    private final AgentSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "phase2-v2-async");
        t.setDaemon(true);
        return t;
    });

    // In-memory storage for V2 reports (TODO: migrate to AgentEvent checkpoint)
    private final Map<String, Phase2V2Report> reportStore = new ConcurrentHashMap<>();
    private final Map<String, V2ExecutionState> stateStore = new ConcurrentHashMap<>();

    private record V2ExecutionState(
        String status,
        int chainsTotal,
        int chainsCompleted,
        String currentChain,
        long startTime
    ) {}

    @PreDestroy
    public void shutdownExecutor() {
        log.info("[Phase2V2] Shutting down async executor");
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
                log.warn("[Phase2V2] Executor forced shutdown");
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record Phase2V2StartRequest(
        @NotBlank(message = "sessionId is required") String sessionId,
        @NotBlank(message = "question is required") String question
    ) {}

    public record Phase2V2StartResponse(
        String sessionId,
        String status,
        int estimatedChains
    ) {}

    public record Phase2V2StatusResponse(
        String status,
        Progress progress
    ) {}

    public record Progress(
        int chainsTotal,
        int chainsCompleted,
        String currentChain,
        int estimatedTimeRemaining
    ) {}

    /**
     * Start a Phase2 V2 analysis session.
     * POST /api/ram/status/phase2/v2/start
     */
    @PostMapping("/start")
    public ApiResponse<Phase2V2StartResponse> startV2Analysis(
            @RequestBody @Valid Phase2V2StartRequest request) {

        log.info("[Phase2V2] POST /start request={}", request);

        // Resolve parent session to get projectPath
        Long backendId = sessionMappingService.resolveBackendId(request.sessionId());
        if (backendId == null) {
            return ApiResponse.error(404, "parent session not found: " + request.sessionId());
        }

        var parentSession = sessionRepository.findById(backendId);
        if (parentSession.isEmpty()) {
            return ApiResponse.error(404, "parent session row missing");
        }

        // Extract projectPath from parent
        String projectPath = extractProjectPath(parentSession.get());
        if (projectPath == null || projectPath.isBlank()) {
            return ApiResponse.error(400, "parent session has no projectPath");
        }

        // Generate new session UUID
        String v2SessionId = java.util.UUID.randomUUID().toString();

        // Initialize execution state
        stateStore.put(v2SessionId, new V2ExecutionState(
            "RUNNING", 3, 0, "init", System.currentTimeMillis()));

        // Async execution with 5-minute timeout
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[Phase2V2] Starting orchestration for v2SessionId={}", v2SessionId);
                Phase2V2Report report = orchestrator.orchestrate(
                        request.sessionId(), request.question(), projectPath);

                // Check if orchestrator produced real data or empty skeleton
                boolean hasData = report.detailLayer() != null
                        && report.detailLayer().chainCount() > 0;
                String finalStatus = hasData ? "DONE" : "FAILED";

                Phase2V2Report finalReport = new Phase2V2Report(
                    report.summaryLayer(), report.detailLayer(), finalStatus, report.question());
                reportStore.put(v2SessionId, finalReport);
                stateStore.put(v2SessionId, new V2ExecutionState(
                    finalStatus, 3, hasData ? 3 : 0, "done", System.currentTimeMillis()));

                log.info("[Phase2V2] Completed for v2SessionId={}, hasData={}, finalStatus={}",
                        v2SessionId, hasData, finalStatus);
            } catch (Exception e) {
                log.error("[Phase2V2] Failed for v2SessionId={}: {}",
                        v2SessionId, e.getMessage(), e);
                stateStore.put(v2SessionId, new V2ExecutionState(
                    "FAILED", 3, 0, "error", System.currentTimeMillis()));
            }
        }, asyncExecutor)
        .orTimeout(5, TimeUnit.MINUTES);

        // 预估链路数 (后续实现真实估算)
        int estimated = 3;

        return ApiResponse.success(new Phase2V2StartResponse(
                v2SessionId, "RUNNING", estimated));
    }

    /**
     * Get execution status.
     * GET /api/ram/status/phase2/v2/{sid}/status
     */
    @GetMapping("/{sid}/status")
    public ApiResponse<Phase2V2StatusResponse> getStatus(@PathVariable("sid") String sessionId) {
        V2ExecutionState state = stateStore.get(sessionId);
        if (state == null) {
            return ApiResponse.error(404, "session not found: " + sessionId);
        }

        int elapsed = (int) ((System.currentTimeMillis() - state.startTime()) / 1000);
        int estimatedRemaining = Math.max(0, 180 - elapsed); // 3 min estimate

        return ApiResponse.success(new Phase2V2StatusResponse(
                state.status(),
                new Progress(state.chainsTotal(), state.chainsCompleted(),
                    state.currentChain(), estimatedRemaining)));
    }

    /**
     * Get layered report.
     * GET /api/ram/status/phase2/v2/{sid}/report
     */
    @GetMapping("/{sid}/report")
    public ApiResponse<Phase2V2Report> getReport(@PathVariable("sid") String sessionId) {
        V2ExecutionState state = stateStore.get(sessionId);
        if (state == null) {
            return ApiResponse.error(404, "session not found: " + sessionId);
        }

        Phase2V2Report report = reportStore.get(sessionId);
        if (report == null) {
            // Report not yet available - return empty report with status
            return ApiResponse.success(new Phase2V2Report(
                null, // summaryLayer
                null, // detailLayer
                state.status(),
                null  // question
            ));
        }

        return ApiResponse.success(report);
    }

    @SuppressWarnings("unchecked")
    private String extractProjectPath(com.huawei.hisi.ram.model.AgentSession session) {
        // 解析 projectPaths JSON
        String json = session.getProjectPaths();
        if (json == null || json.isBlank()) return null;
        try {
            List<?> paths = objectMapper.readValue(json, List.class);
            return paths.isEmpty() ? null : String.valueOf(paths.get(0));
        } catch (Exception e) {
            log.warn("[Phase2V2] Failed to parse projectPaths: {}", e.getMessage());
            return null;
        }
    }
}