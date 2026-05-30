package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DebugReport;
import com.huawei.hisi.apm.repository.ApmSessionRepository;
import com.huawei.hisi.apm.repository.ApmSpanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates {@link DebugReport.Report} instances from stored APM span data.
 * <p>
 * The service builds a hierarchical span tree, identifies the top-5 performance
 * hotspots by duration, and collects error points for diagnostic analysis.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DebugReportService {

    private static final int MAX_HOTSPOTS = 5;
    private static final long NANOS_PER_MS = 1_000_000L;

    private final ApmSpanRepository spanRepository;
    private final ApmSessionRepository sessionRepository;

    /**
     * Generate a debug report for a given session.
     * <p>
     * Trace-selection heuristic (in order):
     * <ol>
     *   <li>Prefer the LATEST trace whose root span is SERVER-kind (the actual
     *       HTTP request — Spring Servlet auto-instrumentation creates SERVER
     *       spans for controllers).</li>
     *   <li>Otherwise, prefer the latest trace by root start time
     *       (rules out long-lived startup / Neo4j-init traces that began at
     *       process boot).</li>
     * </ol>
     * Picking by "most spans" was wrong — Neo4j vector-index initialization
     * produces 8+ spans and would beat a clean 2-span /api/git/status request.
     */
    public DebugReport.Report generateReport(String sessionId) {
        List<ApmSpanEntity> allSpans = spanRepository.findBySessionId(sessionId);
        if (allSpans.isEmpty()) {
            log.info("[DebugReport] No spans found for session: {}", sessionId);
            return emptyReport(sessionId, null);
        }

        // Group by traceId.
        Map<String, List<ApmSpanEntity>> byTrace = allSpans.stream()
                .collect(Collectors.groupingBy(ApmSpanEntity::getTraceId));

        // For each trace, find its root span (or earliest span as fallback).
        // Then prefer SERVER-kind root, breaking ties by latest start time.
        List<ApmSpanEntity> selectedSpans = byTrace.values().stream()
                .max(Comparator
                        .comparingInt((List<ApmSpanEntity> spans) -> isHttpEntryTrace(spans) ? 1 : 0)
                        .thenComparingLong(this::traceStartTimeNs))
                .orElse(List.of());

        String traceId = selectedSpans.isEmpty() ? null : selectedSpans.get(0).getTraceId();
        log.info("[DebugReport] Selected trace {} ({} spans) for session {} out of {} candidate traces",
                traceId, selectedSpans.size(), sessionId, byTrace.size());
        return buildReport(sessionId, traceId, selectedSpans);
    }

    /** A trace is "HTTP entry" if any root span has SERVER kind. */
    private static boolean isHttpEntryTrace(List<ApmSpanEntity> spans) {
        return spans.stream()
                .filter(s -> isRootSpan(s, spans))
                .anyMatch(s -> "SERVER".equalsIgnoreCase(s.getSpanKind()));
    }

    /** Trace start time = the earliest startTimeNs across its spans. */
    private long traceStartTimeNs(List<ApmSpanEntity> spans) {
        return spans.stream()
                .mapToLong(ApmSpanEntity::getStartTimeNs)
                .min()
                .orElse(0L);
    }

    /**
     * Generate a debug report for a specific trace.
     */
    public DebugReport.Report generateTraceReport(String traceId) {
        List<ApmSpanEntity> spans = spanRepository.findByTraceId(traceId);
        if (spans.isEmpty()) {
            log.info("[DebugReport] No spans found for trace: {}", traceId);
            return emptyReport(null, traceId);
        }

        String sessionId = spans.get(0).getSessionId();
        return buildReport(sessionId, traceId, spans);
    }

    // ── Report assembly ──────────────────────────────────────────────

    private DebugReport.Report buildReport(String sessionId, String traceId,
                                           List<ApmSpanEntity> spans) {
        List<DebugReport.SpanNode> spanTree = buildSpanTree(spans);

        // Find root span — first node with no parent in the dataset
        ApmSpanEntity rootSpan = spans.stream()
                .filter(s -> isRootSpan(s, spans))
                .findFirst()
                .orElse(spans.get(0));

        long totalDurationMs = toMs(rootSpan.getEndTimeNs() - rootSpan.getStartTimeNs());
        boolean success = !"ERROR".equals(rootSpan.getStatusCode());

        int matchedCount = (int) spans.stream()
                .filter(s -> s.getKgNodeId() != null)
                .count();

        return new DebugReport.Report(
                sessionId,
                traceId,
                rootSpan.getOperationName(),
                success,
                totalDurationMs,
                spanTree,
                findHotspots(spans, totalDurationMs),
                findErrors(spans),
                spans.size(),
                matchedCount
        );
    }

    private static boolean isRootSpan(ApmSpanEntity span, List<ApmSpanEntity> allSpans) {
        String parentId = span.getParentSpanId();
        if (parentId == null || parentId.isEmpty()) {
            return true;
        }
        // Also root if the parent span is not in this dataset
        return allSpans.stream()
                .noneMatch(s -> parentId.equals(s.getSpanId()));
    }

    // ── Span tree construction ───────────────────────────────────────

    private List<DebugReport.SpanNode> buildSpanTree(List<ApmSpanEntity> spans) {
        // Convert all entities to SpanNode (initially with empty children)
        Map<String, DebugReport.SpanNode> nodeMap = new LinkedHashMap<>();
        Map<String, List<DebugReport.SpanNode>> childrenMap = new LinkedHashMap<>();

        for (ApmSpanEntity span : spans) {
            DebugReport.SpanNode node = toSpanNode(span);
            nodeMap.put(span.getSpanId(), node);
            childrenMap.put(span.getSpanId(), new ArrayList<>());
        }

        // Build parent-child relationships
        Set<String> childIds = new HashSet<>();
        for (ApmSpanEntity span : spans) {
            String parentId = span.getParentSpanId();
            if (parentId != null && !parentId.isEmpty() && childrenMap.containsKey(parentId)) {
                childrenMap.get(parentId).add(nodeMap.get(span.getSpanId()));
                childIds.add(span.getSpanId());
            }
        }

        // Rebuild nodes with their resolved children lists
        Map<String, DebugReport.SpanNode> resolvedNodes = new LinkedHashMap<>();
        // Process bottom-up by reversing span order (spans are ordered by start_time ASC)
        List<ApmSpanEntity> reversed = new ArrayList<>(spans);
        Collections.reverse(reversed);

        for (ApmSpanEntity span : reversed) {
            List<DebugReport.SpanNode> children = childrenMap.get(span.getSpanId()).stream()
                    .map(child -> resolvedNodes.getOrDefault(child.spanId(), child))
                    .toList();

            DebugReport.SpanNode original = nodeMap.get(span.getSpanId());
            DebugReport.SpanNode resolved = new DebugReport.SpanNode(
                    original.spanId(),
                    original.parentSpanId(),
                    original.operationName(),
                    original.className(),
                    original.methodName(),
                    original.serviceName(),
                    original.spanKind(),
                    original.durationMs(),
                    original.statusCode(),
                    original.statusMessage(),
                    original.kgNodeId(),
                    original.kgMatchLevel(),
                    List.copyOf(children)
            );
            resolvedNodes.put(span.getSpanId(), resolved);
        }

        // Return root nodes (those not appearing as children)
        return spans.stream()
                .map(s -> s.getSpanId())
                .filter(id -> !childIds.contains(id))
                .map(resolvedNodes::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private DebugReport.SpanNode toSpanNode(ApmSpanEntity span) {
        Map<String, String> attrs = span.getAttributes();
        String className = safeGet(attrs, "code.namespace");
        String methodName = safeGet(attrs, "code.function");
        long durationMs = toMs(span.getEndTimeNs() - span.getStartTimeNs());

        return new DebugReport.SpanNode(
                span.getSpanId(),
                span.getParentSpanId(),
                span.getOperationName(),
                className,
                methodName,
                span.getServiceName(),
                span.getSpanKind(),
                durationMs,
                span.getStatusCode(),
                span.getStatusMessage(),
                span.getKgNodeId(),
                span.getKgMatchLevel(),
                List.of() // children populated later in buildSpanTree
        );
    }

    // ── Hotspot detection ────────────────────────────────────────────

    private List<DebugReport.Hotspot> findHotspots(List<ApmSpanEntity> spans, long totalDurationMs) {
        long safeTotalMs = Math.max(totalDurationMs, 1L);

        return spans.stream()
                .sorted(Comparator.comparingLong(this::spanDurationMs).reversed())
                .limit(MAX_HOTSPOTS)
                .map(span -> toHotspot(span, safeTotalMs))
                .toList();
    }

    private DebugReport.Hotspot toHotspot(ApmSpanEntity span, long totalDurationMs) {
        Map<String, String> attrs = span.getAttributes();
        long durationMs = spanDurationMs(span);
        double percentOfTotal = (durationMs * 100.0) / totalDurationMs;

        return new DebugReport.Hotspot(
                span.getSpanId(),
                span.getOperationName(),
                safeGet(attrs, "code.namespace"),
                safeGet(attrs, "code.function"),
                durationMs,
                percentOfTotal,
                span.getKgNodeId()
        );
    }

    // ── Error collection ─────────────────────────────────────────────

    private List<DebugReport.ErrorPoint> findErrors(List<ApmSpanEntity> spans) {
        return spans.stream()
                .filter(s -> "ERROR".equals(s.getStatusCode()))
                .map(this::toErrorPoint)
                .toList();
    }

    private DebugReport.ErrorPoint toErrorPoint(ApmSpanEntity span) {
        Map<String, String> attrs = span.getAttributes();

        return new DebugReport.ErrorPoint(
                span.getSpanId(),
                span.getOperationName(),
                safeGet(attrs, "code.namespace"),
                safeGet(attrs, "code.function"),
                span.getStatusMessage(),
                safeGet(attrs, "exception.type"),
                safeGet(attrs, "exception.message"),
                span.getKgNodeId()
        );
    }

    // ── Utilities ────────────────────────────────────────────────────

    private static long toMs(long nanos) {
        return Math.max(nanos / NANOS_PER_MS, 0L);
    }

    private long spanDurationMs(ApmSpanEntity span) {
        return toMs(span.getEndTimeNs() - span.getStartTimeNs());
    }

    private static String safeGet(Map<String, String> map, String key) {
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    private static DebugReport.Report emptyReport(String sessionId, String traceId) {
        return new DebugReport.Report(
                sessionId,
                traceId,
                null,
                true,
                0L,
                List.of(),
                List.of(),
                List.of(),
                0,
                0
        );
    }
}
