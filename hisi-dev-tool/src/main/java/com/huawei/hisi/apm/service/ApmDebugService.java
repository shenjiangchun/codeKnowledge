package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.handler.ApmWebSocketHandler;
import com.huawei.hisi.apm.model.DebugReport;
import com.huawei.hisi.apm.model.ApmRequest.ExecuteRequest;
import com.huawei.hisi.apm.model.ApmRequest.ExecuteResult;
import com.huawei.hisi.apm.model.ApmRequest.LaunchRequest;
import com.huawei.hisi.apm.model.ApmRequest.LaunchResult;
import com.huawei.hisi.apm.model.ApmSession;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.TargetProcessInfo;
import com.huawei.hisi.apm.repository.ApmSessionRepository;
import com.huawei.hisi.apm.repository.ApmSpanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Central orchestration service for the APM debug workflow.
 * <p>
 * Coordinates the lifecycle of debug sessions: launch a target JVM with the
 * OTel agent, execute HTTP requests against it, and stop the process when done.
 * All business logic lives here; the controller is a thin delegation layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApmDebugService {

    private final ApmSessionRepository sessionRepository;
    private final ApmSpanRepository spanRepository;
    private final TargetProcessManager targetProcessManager;
    private final SpanIngestionService spanIngestionService;
    private final SpanToKgMapper spanToKgMapper;
    private final ApmWebSocketHandler webSocketHandler;
    private final DebugReportService debugReportService;
    private final KgMethodIncludeBuilder kgMethodIncludeBuilder;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // ── Launch ─────────────────────────────────────────────────────

    /**
     * Launch a target process, create a session, and begin trace collection.
     *
     * @param request launch parameters
     * @return session info after the process has been started
     * @throws IllegalArgumentException if project path is invalid
     * @throws IllegalStateException    if an active session already exists for the project
     * @throws IOException              if the process cannot be started
     */
    public LaunchResult launch(LaunchRequest request) throws IOException {
        String projectPath = request.projectPath();
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath is required");
        }

        Path projectDir = Paths.get(projectPath);
        if (!Files.isDirectory(projectDir)) {
            throw new IllegalArgumentException("projectPath does not exist: " + projectPath);
        }

        // Reject if an active session already exists for this project
        sessionRepository.findActiveByProjectPath(projectPath).ifPresent(existing -> {
            throw new IllegalStateException(
                    "Active session already exists for project: " + existing.getId());
        });

        String sessionId = UUID.randomUUID().toString();
        String serviceName = request.serviceName() != null && !request.serviceName().isBlank()
                ? request.serviceName()
                : extractServiceName(projectPath);
        int targetPort = request.targetPort();

        // Create session record
        ApmSession session = ApmSession.builder()
                .id(sessionId)
                .projectPath(projectPath)
                .serviceName(serviceName)
                .targetPort(targetPort)
                .status("CREATED")
                .build();
        sessionRepository.insert(session);
        log.info("[ApmDebug] Session {} created for project {}", sessionId, projectPath);

        // Initialize KG method index for this project
        try {
            spanToKgMapper.initializeProject(projectPath);
        } catch (Exception e) {
            log.warn("[ApmDebug] Failed to initialize KG index for {}: {}", projectPath, e.getMessage());
        }

        // Build the status callback
        Consumer<TargetProcessInfo> callback = buildStatusCallback(sessionId, serviceName);

        // Per-line log consumer pushes each stdout line over WebSocket in real time
        Consumer<String> logConsumer = line -> webSocketHandler.pushEvent(sessionId, "PROCESS_LOG", Map.of(
                "sessionId", sessionId,
                "line", line
        ));

        // Build OTEL_INSTRUMENTATION_METHODS_INCLUDE according to the selected
        // instrumentation mode. Default: PRECISE when entryNodeId is provided,
        // FULL_PROJECT otherwise (broad project-wide capture, no pre-selection).
        String methodsInclude = "";
        com.huawei.hisi.apm.model.ApmRequest.InstrumentationMode mode = request.instrumentationMode();
        if (mode == null) {
            mode = (request.entryNodeId() != null && !request.entryNodeId().isBlank())
                    ? com.huawei.hisi.apm.model.ApmRequest.InstrumentationMode.PRECISE
                    : com.huawei.hisi.apm.model.ApmRequest.InstrumentationMode.FULL_PROJECT;
        }
        try {
            switch (mode) {
                case PRECISE -> methodsInclude = kgMethodIncludeBuilder.build(request.entryNodeId());
                case FULL_PROJECT -> methodsInclude = kgMethodIncludeBuilder.buildFullProject(projectPath, false);
                case NONE -> methodsInclude = "";
            }
        } catch (Exception e) {
            log.warn("[ApmDebug] Failed to build OTel methods include (mode={}): {}", mode, e.getMessage());
        }
        log.info("[ApmDebug] Session {} mode={} include length={}", sessionId, mode,
                methodsInclude == null ? 0 : methodsInclude.length());

        // Launch target process
        TargetProcessInfo processInfo = targetProcessManager.launch(
                sessionId, projectPath, serviceName, targetPort, callback, logConsumer, methodsInclude);

        int resolvedPort = processInfo.getTargetPort();
        // Update session with the resolved port if auto-assigned
        if (targetPort != resolvedPort) {
            session.setTargetPort(resolvedPort);
            sessionRepository.updateTargetPort(sessionId, resolvedPort);
        }

        return new LaunchResult(sessionId, serviceName, resolvedPort, processInfo.getStatus());
    }

    // ── Execute ────────────────────────────────────────────────────

    /**
     * Send an HTTP request to the target process and return the response.
     *
     * @param request execute parameters including method, path, body, and headers
     * @return captured response info
     * @throws IllegalArgumentException if session is missing or not ready
     * @throws IOException              if the HTTP call fails
     */
    public ExecuteResult execute(ExecuteRequest request) throws IOException {
        String sessionId = request.sessionId();
        ApmSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (!"READY".equals(session.getStatus()) && !"RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException(
                    "Session is not ready for execution. Current status: " + session.getStatus());
        }

        sessionRepository.updateStatus(sessionId, "RUNNING");

        int port = session.getTargetPort();
        String url = "http://localhost:" + port + request.path();
        String method = request.method() != null ? request.method().toUpperCase() : "GET";

        // Build OkHttp request
        Request.Builder reqBuilder = new Request.Builder().url(url);

        // Add custom headers
        if (request.headers() != null) {
            request.headers().forEach(reqBuilder::addHeader);
        }

        // Set method and body
        RequestBody body = null;
        if (request.body() != null && !request.body().isBlank()) {
            body = RequestBody.create(request.body(), JSON_MEDIA_TYPE);
        }

        switch (method) {
            case "POST" -> reqBuilder.post(body != null ? body : RequestBody.create("", null));
            case "PUT" -> reqBuilder.put(body != null ? body : RequestBody.create("", null));
            case "DELETE" -> {
                if (body != null) {
                    reqBuilder.delete(body);
                } else {
                    reqBuilder.delete();
                }
            }
            case "PATCH" -> reqBuilder.patch(body != null ? body : RequestBody.create("", null));
            default -> reqBuilder.get();
        }

        long startMs = System.currentTimeMillis();
        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            long durationMs = System.currentTimeMillis() - startMs;

            // Capture response headers
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (String name : response.headers().names()) {
                responseHeaders.put(name, response.header(name));
            }

            String responseBody = response.body() != null ? response.body().string() : "";

            log.info("[ApmDebug] Execute {} {} -> {} ({}ms)", method, request.path(),
                    response.code(), durationMs);

            webSocketHandler.pushEvent(sessionId, "EXECUTION_COMPLETE", Map.of(
                    "sessionId", sessionId,
                    "httpStatus", response.code(),
                    "durationMs", durationMs
            ));

            return new ExecuteResult(sessionId, response.code(), responseHeaders, responseBody, durationMs);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("[ApmDebug] Execute failed: {} {} ({}ms)", method, request.path(), durationMs, e);

            webSocketHandler.pushEvent(sessionId, "EXECUTION_ERROR", Map.of(
                    "sessionId", sessionId,
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));

            throw e;
        }
    }

    // ── Stop ───────────────────────────────────────────────────────

    /**
     * Stop a target process and finalize the session.
     *
     * @param sessionId the session to stop
     * @throws IllegalArgumentException if the session does not exist
     */
    public void stop(String sessionId) {
        ApmSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        log.info("[ApmDebug] Stopping session {}", sessionId);

        // Shut down the target process
        targetProcessManager.shutdown(sessionId);

        // Unregister from ingestion pipeline
        spanIngestionService.unregisterSession(session.getServiceName());

        // Clear KG index
        try {
            spanToKgMapper.clearProject(session.getProjectPath());
        } catch (Exception e) {
            log.warn("[ApmDebug] Failed to clear KG index for {}: {}", session.getProjectPath(), e.getMessage());
        }

        // Finalize session
        sessionRepository.finish(sessionId, "COMPLETED");

        webSocketHandler.pushEvent(sessionId, "PROCESS_STOPPED", Map.of(
                "sessionId", sessionId,
                "status", "COMPLETED"
        ));

        log.info("[ApmDebug] Session {} completed", sessionId);
    }

    // ── Query methods ──────────────────────────────────────────────

    /**
     * List recent sessions, most recent first.
     *
     * @param limit maximum number of sessions to return
     * @return list of recent sessions
     */
    public List<ApmSession> listSessions(int limit) {
        return sessionRepository.findRecent(limit);
    }

    /**
     * Get a session by ID.
     *
     * @param sessionId the session identifier
     * @return the session
     * @throws IllegalArgumentException if not found
     */
    public ApmSession getSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    /**
     * Get all spans collected for a session, ordered by start time.
     *
     * @param sessionId the session identifier
     * @return list of span entities
     */
    public List<ApmSpanEntity> getSpans(String sessionId) {
        return spanRepository.findBySessionId(sessionId);
    }

    /**
     * Get all spans for a specific trace, ordered by start time.
     *
     * @param traceId the W3C trace identifier
     * @return list of span entities forming the trace tree
     */
    public List<ApmSpanEntity> getTrace(String traceId) {
        return spanRepository.findByTraceId(traceId);
    }

    /**
     * Get recent process stdout lines for a session.
     *
     * @param sessionId the session identifier
     * @param maxLines  maximum number of lines to return
     * @return list of output lines (most recent last)
     */
    public List<String> getProcessOutput(String sessionId, int maxLines) {
        return targetProcessManager.getOutputLines(sessionId, maxLines);
    }

    /**
     * Generate a full debug report for a session, including span tree, hotspots, and errors.
     *
     * @param sessionId the session identifier
     * @return the debug report
     */
    public DebugReport.Report getReport(String sessionId) {
        return debugReportService.generateReport(sessionId);
    }

    /**
     * Generate a debug report for a specific trace.
     *
     * @param traceId the W3C trace identifier
     * @return the debug report for that trace
     */
    public DebugReport.Report getTraceReport(String traceId) {
        return debugReportService.generateTraceReport(traceId);
    }

    // ── Internal helpers ───────────────────────────────────────────

    /**
     * Build a status callback that updates session state, registers with
     * the ingestion pipeline, and pushes WebSocket events.
     */
    private Consumer<TargetProcessInfo> buildStatusCallback(String sessionId, String serviceName) {
        return info -> {
            sessionRepository.updateStatus(sessionId, info.getStatus());

            if ("READY".equals(info.getStatus())) {
                // Resolve projectPath from the session for KG enrichment
                String projectPath = sessionRepository.findById(sessionId)
                        .map(ApmSession::getProjectPath)
                        .orElse(null);
                spanIngestionService.registerSession(serviceName, sessionId, projectPath);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("status", info.getStatus());
            payload.put("port", info.getTargetPort());
            if (info.getExitCode() != null) {
                payload.put("exitCode", info.getExitCode());
            }
            // On terminal failure, include the last log lines so a late-joining
            // WebSocket client can still see what went wrong.
            if ("ERROR".equals(info.getStatus()) || "STOPPED".equals(info.getStatus())) {
                List<String> tail = targetProcessManager.getOutputLines(sessionId, 50);
                if (!tail.isEmpty()) {
                    payload.put("tailLines", tail);
                }
            }
            webSocketHandler.pushEvent(sessionId, "PROCESS_" + info.getStatus(), payload);
        };
    }

    /**
     * Derive a sanitized service name from the project directory name.
     * Replaces non-alphanumeric characters (except hyphens) with hyphens.
     */
    private String extractServiceName(String projectPath) {
        return Paths.get(projectPath).getFileName().toString()
                .replaceAll("[^a-zA-Z0-9-]", "-");
    }
}
