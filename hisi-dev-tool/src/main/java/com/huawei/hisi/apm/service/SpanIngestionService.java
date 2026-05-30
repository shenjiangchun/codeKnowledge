package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.cache.ExceptionSpanIndex;
import com.huawei.hisi.apm.handler.ApmWebSocketHandler;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.OtlpTraceData;
import com.huawei.hisi.apm.repository.ApmSpanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flattens the nested OTLP trace structure into {@link ApmSpanEntity} instances,
 * resolves the owning APM session, enriches with KG mappings, and pushes to
 * WebSocket for live visualization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpanIngestionService {

    private final ApmSpanRepository spanRepository;
    private final SpanToKgMapper spanToKgMapper;
    private final ApmWebSocketHandler webSocketHandler;
    private final ExceptionSpanIndex exceptionSpanIndex;

    private static final Map<Integer, String> SPAN_KIND_MAP = Map.of(
            0, "UNSPECIFIED",
            1, "INTERNAL",
            2, "SERVER",
            3, "CLIENT",
            4, "PRODUCER",
            5, "CONSUMER"
    );

    private static final Map<Integer, String> STATUS_CODE_MAP = Map.of(
            0, "UNSET",
            1, "OK",
            2, "ERROR"
    );

    /**
     * In-memory mapping from service name to active session ID.
     * Populated by the session lifecycle manager when sessions start/stop.
     */
    private final Map<String, String> serviceSessionMap = new ConcurrentHashMap<>();

    /**
     * In-memory mapping from service name to project path.
     * Used to resolve the project path for KG span matching.
     */
    private final Map<String, String> serviceProjectMap = new ConcurrentHashMap<>();

    /**
     * Ingests an OTLP trace export request: flattens spans, resolves sessions,
     * and batch-inserts into storage.
     */
    public void ingest(OtlpTraceData.ExportTraceServiceRequest traceData) {
        if (traceData == null || traceData.resourceSpans() == null) {
            return;
        }

        List<ApmSpanEntity> allSpans = new ArrayList<>();

        for (OtlpTraceData.ResourceSpans rs : traceData.resourceSpans()) {
            Map<String, String> resourceAttrs = extractAttributes(
                    rs.resource() != null ? rs.resource().attributes() : null);
            String serviceName = resourceAttrs.getOrDefault("service.name", "unknown");

            String sessionId = resolveSessionId(serviceName);
            if (sessionId == null) {
                log.warn("[Ingestion] No active session for service: '{}' (registered services: {})",
                        serviceName, serviceSessionMap.keySet());
                continue;
            }

            if (rs.scopeSpans() == null) {
                continue;
            }

            for (OtlpTraceData.ScopeSpans ss : rs.scopeSpans()) {
                if (ss.spans() == null) {
                    continue;
                }
                for (OtlpTraceData.Span span : ss.spans()) {
                    ApmSpanEntity entity = convertSpan(span, sessionId, serviceName, resourceAttrs);
                    allSpans.add(entity);
                }
            }
        }

        if (allSpans.isEmpty()) {
            return;
        }

        spanRepository.batchInsert(allSpans);
        log.info("[Ingestion] Stored {} spans", allSpans.size());

        // Detect and mark silent_catch spans (before KG enrichment so markers are visible)
        markSilentCatchSpans(allSpans);

        // Feed spans to exception index (best-effort, the index filters internally)
        indexExceptionSpans(allSpans);

        // Enrich spans with KG mapping (best-effort, non-blocking)
        enrichWithKgMapping(allSpans);

        // Push spans to WebSocket for live trace view (grouped by sessionId)
        pushSpansToWebSocket(allSpans);
    }

    // ── Session registration ───────────────────────────────────────

    /**
     * Register a mapping from service name to session ID and project path.
     * Called when an APM session transitions to READY/RUNNING.
     */
    public void registerSession(String serviceName, String sessionId, String projectPath) {
        serviceSessionMap.put(serviceName, sessionId);
        if (projectPath != null) {
            serviceProjectMap.put(serviceName, projectPath);
        }
        log.info("[Ingestion] Registered session: {} -> {} (project: {})", serviceName, sessionId, projectPath);
    }

    /**
     * Remove the mapping for a service name.
     * Called when an APM session completes or errors out.
     */
    public void unregisterSession(String serviceName) {
        serviceSessionMap.remove(serviceName);
        serviceProjectMap.remove(serviceName);
        log.info("[Ingestion] Unregistered session for service: {}", serviceName);
    }

    // ── Internal helpers ───────────────────────────────────────────

    /**
     * Mark spans suspected of silently catching exceptions.
     * <p>
     * A span is suspected when it has an {@code "exception.type"} attribute
     * (OTel auto-instrumentation saw an exception) but its status is not
     * {@code "ERROR"} — meaning the exception was caught and suppressed.
     * <p>
     * Exempt spans include HTTP handlers, DB queries, CLIENT-kind spans,
     * and spans with {@code db.system} or {@code messaging.system} attributes.
     * <p>
     * Adds a synthetic {@code "hisi.silent_catch" = "suspected"} attribute
     * and persists the updated attributes back to storage.
     *
     * @param spans the full list of ingested spans (modified in-place)
     */
    private void markSilentCatchSpans(List<ApmSpanEntity> spans) {
        try {
            int count = 0;
            for (ApmSpanEntity span : spans) {
                if (isSilentCatchSuspected(span)) {
                    Map<String, String> attrs = span.getAttributes();
                    // Always copy to a mutable LinkedHashMap to handle potentially
                    // immutable maps (e.g. Collections.emptyMap()).
                    Map<String, String> mutableAttrs = (attrs == null)
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(attrs);
                    mutableAttrs.put("hisi.silent_catch", "suspected");
                    span.setAttributes(mutableAttrs);

                    spanRepository.updateAttributes(
                            span.getSpanId(), span.getSessionId(), span.getAttributes());
                    count++;
                }
            }
            if (count > 0) {
                log.info("[Ingestion] Marked {} silent_catch spans out of {}", count, spans.size());
            }
        } catch (Exception e) {
            log.warn("[Ingestion] Silent catch detection failed: {}", e.getMessage());
        }
    }

    /**
     * Determine whether a span is suspected of silently catching an exception.
     * <p>
     * Positive when ALL of:
     * <ol>
     *   <li>{@code statusCode} is NOT {@code "ERROR"}</li>
     *   <li>Span attributes contain {@code "exception.type"}</li>
     * </ol>
     * <p>
     * Exempt when ANY of:
     * <ol>
     *   <li>{@code operationName} starts with {@code "HTTP "}</li>
     *   <li>{@code operationName} starts with {@code "SELECT"}, {@code "INSERT"},
     *       {@code "UPDATE"}, or {@code "DELETE"}</li>
     *   <li>{@code spanKind} is {@code "CLIENT"}</li>
     *   <li>Attributes contain {@code "db.system"}</li>
     *   <li>Attributes contain {@code "messaging.system"}</li>
     * </ol>
     *
     * @param span the span to evaluate
     * @return {@code true} if the span is suspected of silent exception catching
     */
    static boolean isSilentCatchSuspected(ApmSpanEntity span) {
        // Must NOT be ERROR status
        if ("ERROR".equals(span.getStatusCode())) {
            return false;
        }

        // Must have exception.type attribute
        Map<String, String> attrs = span.getAttributes();
        if (attrs == null || !attrs.containsKey("exception.type")) {
            return false;
        }

        // Exemption 1: HTTP handler span
        String opName = span.getOperationName();
        if (opName != null && opName.startsWith("HTTP ")) {
            return false;
        }

        // Exemption 2: DB query span (operation name prefix)
        if (opName != null && (opName.startsWith("SELECT") || opName.startsWith("INSERT")
                || opName.startsWith("UPDATE") || opName.startsWith("DELETE"))) {
            return false;
        }

        // Exemption 3: CLIENT span kind
        if ("CLIENT".equals(span.getSpanKind())) {
            return false;
        }

        // Exemption 4: db.system attribute
        if (attrs.containsKey("db.system")) {
            return false;
        }

        // Exemption 5: messaging.system attribute
        if (attrs.containsKey("messaging.system")) {
            return false;
        }

        return true;
    }

    /**
     * Feed all ingested spans to the {@link ExceptionSpanIndex}.
     * The index filters internally (only indexes ERROR or exception.type spans),
     * so all spans are passed without pre-filtering.
     * <p>
     * Best-effort: failures are logged and do not block the ingestion pipeline.
     *
     * @param spans the full list of ingested spans
     */
    private void indexExceptionSpans(List<ApmSpanEntity> spans) {
        try {
            for (ApmSpanEntity span : spans) {
                exceptionSpanIndex.index(span);
            }
        } catch (Exception e) {
            log.warn("[Ingestion] Exception span indexing failed: {}", e.getMessage());
        }
    }

    /**
     * Enrich spans with KG MethodNode mapping.
     * Runs best-effort: failures are logged and do not block span storage.
     */
    private void enrichWithKgMapping(List<ApmSpanEntity> spans) {
        int matched = 0;
        for (ApmSpanEntity span : spans) {
            String projectPath = serviceProjectMap.get(span.getServiceName());
            if (projectPath == null) {
                continue;
            }

            try {
                Optional<SpanToKgMapper.MatchResult> result = spanToKgMapper.match(
                        span.getAttributes(), span.getOperationName(), projectPath);

                if (result.isPresent()) {
                    SpanToKgMapper.MatchResult match = result.get();
                    span.setKgNodeId(match.nodeId());
                    span.setKgMatchLevel(match.matchLevel());

                    // Persist the KG mapping back to SQLite
                    spanRepository.updateKgMapping(
                            span.getSpanId(), span.getSessionId(),
                            match.nodeId(), match.matchLevel());
                    matched++;
                }
            } catch (Exception e) {
                log.debug("[Ingestion] KG match failed for span {}: {}", span.getSpanId(), e.getMessage());
            }
        }

        if (matched > 0) {
            log.debug("[Ingestion] KG enriched {}/{} spans", matched, spans.size());
        }
    }

    /**
     * Push ingested spans to WebSocket for live trace visualization.
     * Groups spans by sessionId and sends each batch to the connected client.
     */
    private void pushSpansToWebSocket(List<ApmSpanEntity> spans) {
        // Group spans by sessionId to batch per client
        Map<String, List<ApmSpanEntity>> bySession = new LinkedHashMap<>();
        for (ApmSpanEntity span : spans) {
            bySession.computeIfAbsent(span.getSessionId(), k -> new ArrayList<>()).add(span);
        }

        for (Map.Entry<String, List<ApmSpanEntity>> entry : bySession.entrySet()) {
            String sessionId = entry.getKey();
            boolean connected = webSocketHandler.isConnected(sessionId);
            log.info("[Ingestion] Pushing {} spans to session {} (wsConnected={})",
                    entry.getValue().size(), sessionId, connected);
            if (connected) {
                try {
                    webSocketHandler.pushSpans(sessionId, entry.getValue());
                } catch (Exception e) {
                    log.warn("[Ingestion] WebSocket push failed for session {}: {}", sessionId, e.getMessage());
                }
            }
        }
    }

    private String resolveSessionId(String serviceName) {
        return serviceSessionMap.get(serviceName);
    }

    private ApmSpanEntity convertSpan(OtlpTraceData.Span span, String sessionId,
                                       String serviceName, Map<String, String> resourceAttrs) {
        Map<String, String> attrs = new LinkedHashMap<>(extractAttributes(span.attributes()));

        // Promote exception event attributes onto the span so downstream
        // consumers (ExceptionSpanIndex, PromptBuilder) can read exception.type
        // / .message / .stacktrace without walking the events list. OTel
        // semantics always carry exception details in an event named
        // "exception" (one per thrown exception). We take the LAST exception
        // event so the most-recent throw wins.
        if (span.events() != null) {
            for (OtlpTraceData.Event ev : span.events()) {
                if (ev != null && "exception".equals(ev.name()) && ev.attributes() != null) {
                    Map<String, String> evAttrs = extractAttributes(ev.attributes());
                    attrs.putAll(evAttrs);
                }
            }
        }

        String statusCode = "UNSET";
        String statusMessage = null;
        if (span.status() != null) {
            Integer code = span.status().code();
            statusCode = code == null ? "UNSET" : STATUS_CODE_MAP.getOrDefault(code, "UNSET");
            statusMessage = span.status().message();
        }

        Integer kind = span.kind();
        String spanKind = kind == null ? "UNSPECIFIED" : SPAN_KIND_MAP.getOrDefault(kind, "UNSPECIFIED");

        return ApmSpanEntity.builder()
                .sessionId(sessionId)
                .traceId(span.traceId())
                .spanId(span.spanId())
                .parentSpanId(span.parentSpanId())
                .serviceName(serviceName)
                .operationName(span.name() != null ? span.name() : "unknown")
                .spanKind(spanKind)
                .startTimeNs(parseNanos(span.startTimeUnixNano()))
                .endTimeNs(parseNanos(span.endTimeUnixNano()))
                .statusCode(statusCode)
                .statusMessage(statusMessage)
                .attributes(attrs)
                .resourceAttributes(resourceAttrs)
                .kgNodeId(null)
                .kgMatchLevel(3) // unmatched by default
                .build();
    }

    /**
     * Extracts OTLP key-value attributes into a simple String map.
     * Handles stringValue, intValue, and boolValue; other types are skipped.
     */
    static Map<String, String> extractAttributes(List<OtlpTraceData.KeyValue> kvList) {
        if (kvList == null || kvList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (OtlpTraceData.KeyValue kv : kvList) {
            if (kv.key() == null || kv.value() == null) {
                continue;
            }
            String val = kv.value().stringValue();
            if (val == null && kv.value().intValue() != null) {
                val = kv.value().intValue();
            }
            if (val == null && kv.value().boolValue() != null) {
                val = String.valueOf(kv.value().boolValue());
            }
            if (val != null) {
                result.put(kv.key(), val);
            }
        }
        return result;
    }

    /**
     * Parses a nanosecond timestamp string (as used in OTLP JSON) to a long.
     * Returns 0 for null, empty, or malformed input.
     */
    static long parseNanos(String nanoString) {
        if (nanoString == null || nanoString.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(nanoString);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
