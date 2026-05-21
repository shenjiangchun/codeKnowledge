package com.huawei.hisi.apm.model;

import java.util.Map;

/**
 * Request and response DTOs for APM debug endpoints.
 * <p>
 * All types are immutable Java records grouped in a single utility class
 * to keep the model layer compact.
 */
public final class ApmRequest {

    private ApmRequest() {
        // utility class — no instances
    }

    /**
     * Request to launch a target JVM process with the OTel agent attached.
     *
     * @param projectPath absolute path to the project root
     * @param targetPort  port for the target application (0 = auto-assign)
     * @param serviceName optional service name; auto-derived from project directory if null
     * @param entryNodeId optional KG nodeId of the entry method; when present the
     *                    backend builds {@code OTEL_INSTRUMENTATION_METHODS_INCLUDE}
     *                    from the KG callee tree to capture method-level spans
     */
    public record LaunchRequest(
        String projectPath,
        int targetPort,
        String serviceName,
        String entryNodeId
    ) {}

    /**
     * Request to execute an HTTP call against the running target process.
     *
     * @param sessionId active APM session identifier
     * @param method    HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param path      request path (e.g. /api/users)
     * @param body      optional request body (nullable)
     * @param headers   optional request headers (nullable)
     */
    public record ExecuteRequest(
        String sessionId,
        String method,
        String path,
        String body,
        Map<String, String> headers
    ) {}

    /**
     * Request to stop a running APM session.
     *
     * @param sessionId the session to stop
     */
    public record StopRequest(
        String sessionId
    ) {}

    /**
     * Result returned after a successful launch.
     *
     * @param sessionId   the newly created session identifier
     * @param serviceName resolved service name
     * @param targetPort  resolved target port
     * @param status      initial session status
     */
    public record LaunchResult(
        String sessionId,
        String serviceName,
        int targetPort,
        String status
    ) {}

    /**
     * Result of executing an HTTP request against the target process.
     *
     * @param sessionId       owning session
     * @param httpStatus      HTTP status code from the target
     * @param responseHeaders selected response headers
     * @param responseBody    response body text
     * @param durationMs      round-trip time in milliseconds
     */
    public record ExecuteResult(
        String sessionId,
        int httpStatus,
        Map<String, String> responseHeaders,
        String responseBody,
        long durationMs
    ) {}
}
