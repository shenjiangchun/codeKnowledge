package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.phase2v2.Phase2V2Orchestrator;
import com.huawei.hisi.ram.phase2v2.model.Phase2V2Report;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    public record Phase2V2StartRequest(
        String sessionId,   // Phase1 session ID
        String question     // User's follow-up question
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
            @RequestBody Phase2V2StartRequest request) {

        log.info("[Phase2V2] POST /start request={}", request);

        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error(400, "sessionId (Phase1) is required");
        }
        if (request.question() == null || request.question().isBlank()) {
            return ApiResponse.error(400, "question is required");
        }

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

        // Async execution with 5-minute timeout
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[Phase2V2] Starting orchestration for v2SessionId={}", v2SessionId);
                Phase2V2Report report = orchestrator.orchestrate(
                        request.sessionId(), request.question(), projectPath);

                // TODO: Store report in AgentEvent checkpoint

                log.info("[Phase2V2] Completed for v2SessionId={}, status={}",
                        v2SessionId, report.status());
            } catch (Exception e) {
                log.error("[Phase2V2] Failed for v2SessionId={}: {}",
                        v2SessionId, e.getMessage(), e);
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
        // TODO: 实现状态查询
        return ApiResponse.success(new Phase2V2StatusResponse(
                "RUNNING",
                new Progress(3, 1, "chain-xxx", 60)
        ));
    }

    /**
     * Get layered report.
     * GET /api/ram/status/phase2/v2/{sid}/report
     */
    @GetMapping("/{sid}/report")
    public ApiResponse<Phase2V2Report> getReport(@PathVariable("sid") String sessionId) {
        // TODO: 实现报告查询
        return ApiResponse.error(404, "Report not ready yet");
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