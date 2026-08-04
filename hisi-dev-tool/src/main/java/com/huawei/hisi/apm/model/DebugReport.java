package com.huawei.hisi.apm.model;

import java.util.List;

/**
 * Immutable model classes representing a debug report generated from APM span data.
 * <p>
 * A {@link Report} assembles a hierarchical span tree, identifies performance
 * hotspots (top-5 slowest leaf spans), and collects error points for a single
 * trace within an APM session.
 */
public final class DebugReport {

    private DebugReport() {
        // Utility class — no instantiation
    }

    public record Report(
            String sessionId,
            String traceId,
            String entryPoint,
            boolean success,
            long totalDurationMs,
            List<SpanNode> spanTree,
            List<Hotspot> hotspots,
            List<ErrorPoint> errors,
            int totalSpanCount,
            int matchedSpanCount
    ) {}

    public record SpanNode(
            String spanId,
            String parentSpanId,
            String operationName,
            String className,
            String methodName,
            String serviceName,
            String spanKind,
            long durationMs,
            String statusCode,
            String statusMessage,
            String kgNodeId,
            int kgMatchLevel,
            List<SpanNode> children
    ) {}

    public record Hotspot(
            String spanId,
            String operationName,
            String className,
            String methodName,
            long durationMs,
            double percentOfTotal,
            String kgNodeId
    ) {}

    public record ErrorPoint(
            String spanId,
            String operationName,
            String className,
            String methodName,
            String statusMessage,
            String exceptionType,
            String exceptionMessage,
            String kgNodeId
    ) {}
}
