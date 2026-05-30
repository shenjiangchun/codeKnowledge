package com.huawei.hisi.apm.controller;

import com.huawei.hisi.apm.model.ApmRequest.ExecuteRequest;
import com.huawei.hisi.apm.model.ApmRequest.ExecuteResult;
import com.huawei.hisi.apm.model.ApmRequest.LaunchRequest;
import com.huawei.hisi.apm.model.ApmRequest.LaunchResult;
import com.huawei.hisi.apm.model.ApmRequest.StopRequest;
import com.huawei.hisi.apm.model.ApmSession;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DebugReport;
import com.huawei.hisi.apm.service.ApmDebugService;
import com.huawei.hisi.apm.service.SpanIngestionService;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for APM debug session management.
 * <p>
 * Provides endpoints to launch target processes with the OTel agent,
 * execute HTTP requests against them, collect traces, and stop sessions.
 * All business logic is delegated to {@link ApmDebugService}.
 */
@RestController
@RequestMapping("/api/apm")
@RequiredArgsConstructor
@Slf4j
public class ApmController {

    private final ApmDebugService apmDebugService;
    private final SpanIngestionService spanIngestionService;

    /**
     * Launch a target process and create a new APM debug session.
     * <p>
     * POST /api/apm/launch
     */
    @PostMapping("/launch")
    public ApiResponse<LaunchResult> launch(@RequestBody LaunchRequest request) {
        try {
            LaunchResult result = apmDebugService.launch(request);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            log.warn("[ApmController] Launch rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("[ApmController] Launch conflict: {}", e.getMessage());
            return ApiResponse.error(409, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmController] Launch failed", e);
            return ApiResponse.error("Failed to launch process: " + e.getMessage());
        }
    }

    /**
     * Send an HTTP request to the target process and return the response.
     * <p>
     * POST /api/apm/execute
     */
    @PostMapping("/execute")
    public ApiResponse<ExecuteResult> execute(@RequestBody ExecuteRequest request) {
        try {
            ExecuteResult result = apmDebugService.execute(request);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            log.warn("[ApmController] Execute rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("[ApmController] Execute state error: {}", e.getMessage());
            return ApiResponse.error(409, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmController] Execute failed", e);
            return ApiResponse.error("Failed to execute request: " + e.getMessage());
        }
    }

    /**
     * Stop a running target process and finalize the session.
     * <p>
     * POST /api/apm/stop
     */
    @PostMapping("/stop")
    public ApiResponse<Map<String, String>> stop(@RequestBody StopRequest request) {
        try {
            apmDebugService.stop(request.sessionId());
            return ApiResponse.success(Map.of("sessionId", request.sessionId(), "status", "COMPLETED"));
        } catch (IllegalArgumentException e) {
            log.warn("[ApmController] Stop rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmController] Stop failed for session {}", request.sessionId(), e);
            return ApiResponse.error("Failed to stop session: " + e.getMessage());
        }
    }

    /**
     * List active and recent APM sessions.
     * <p>
     * GET /api/apm/sessions?limit=20
     */
    @GetMapping("/sessions")
    public ApiResponse<List<ApmSession>> listSessions(
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<ApmSession> sessions = apmDebugService.listSessions(limit);
            return ApiResponse.success(sessions);
        } catch (Exception e) {
            log.error("[ApmController] Failed to list sessions", e);
            return ApiResponse.error("Failed to list sessions");
        }
    }

    /**
     * Get details for a specific session.
     * <p>
     * GET /api/apm/session/{id}
     */
    @GetMapping("/session/{id}")
    public ApiResponse<ApmSession> getSession(@PathVariable String id) {
        try {
            ApmSession session = apmDebugService.getSession(id);
            return ApiResponse.success(session);
        } catch (IllegalArgumentException e) {
            log.warn("[ApmController] Session not found: {}", id);
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmController] Failed to get session {}", id, e);
            return ApiResponse.error("Failed to get session");
        }
    }

    /**
     * Get all spans collected for a session.
     * <p>
     * GET /api/apm/spans/{sessionId}
     */
    @GetMapping("/spans/{sessionId}")
    public ApiResponse<List<ApmSpanEntity>> getSpans(@PathVariable String sessionId) {
        try {
            List<ApmSpanEntity> spans = apmDebugService.getSpans(sessionId);
            return ApiResponse.success(spans);
        } catch (Exception e) {
            log.error("[ApmController] Failed to get spans for session {}", sessionId, e);
            return ApiResponse.error("Failed to get spans");
        }
    }

    /**
     * Get the span tree for a specific trace.
     * <p>
     * GET /api/apm/trace/{traceId}
     */
    @GetMapping("/trace/{traceId}")
    public ApiResponse<List<ApmSpanEntity>> getTrace(@PathVariable String traceId) {
        try {
            List<ApmSpanEntity> spans = apmDebugService.getTrace(traceId);
            return ApiResponse.success(spans);
        } catch (Exception e) {
            log.error("[ApmController] Failed to get trace {}", traceId, e);
            return ApiResponse.error("Failed to get trace");
        }
    }

    /**
     * Get the execution report for a session.
     * <p>
     * GET /api/apm/report/{sessionId}
     */
    @GetMapping("/report/{sessionId}")
    public ApiResponse<DebugReport.Report> getReport(@PathVariable String sessionId) {
        try {
            DebugReport.Report report = apmDebugService.getReport(sessionId);
            return ApiResponse.success(report);
        } catch (IllegalArgumentException e) {
            log.warn("[ApmController] Report session not found: {}", sessionId);
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("[ApmController] Failed to get report for session {}", sessionId, e);
            return ApiResponse.error("Failed to get report");
        }
    }

    /**
     * Get recent process stdout lines for a session.
     * <p>
     * GET /api/apm/process-output/{sessionId}?maxLines=100
     */
    @GetMapping("/process-output/{sessionId}")
    public ApiResponse<List<String>> getProcessOutput(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "100") int maxLines) {
        try {
            List<String> output = apmDebugService.getProcessOutput(sessionId, maxLines);
            return ApiResponse.success(output);
        } catch (Exception e) {
            log.error("[ApmController] Failed to get process output for session {}", sessionId, e);
            return ApiResponse.error("Failed to get process output");
        }
    }


    @PostMapping("/dev/register-session")
    public ApiResponse<Map<String, String>> devRegisterSession(@RequestBody Map<String, String> body) {
        String serviceName = body.get("serviceName");
        String sessionId = body.getOrDefault("sessionId", "dev-" + serviceName);
        String projectPath = body.get("projectPath");
        if (serviceName == null || serviceName.isBlank()) {
            return ApiResponse.error(400, "serviceName required");
        }
        spanIngestionService.registerSession(serviceName, sessionId, projectPath);
        return ApiResponse.success(Map.of(
                "serviceName", serviceName,
                "sessionId", sessionId,
                "projectPath", projectPath == null ? "" : projectPath));
    }
}
