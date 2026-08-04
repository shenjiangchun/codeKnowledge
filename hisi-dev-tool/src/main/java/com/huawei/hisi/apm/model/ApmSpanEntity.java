package com.huawei.hisi.apm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Internal representation of a single span after parsing the OTLP JSON payload.
 * <p>
 * Used for SQLite storage and internal processing. Timestamps are stored as
 * nanosecond epoch longs (converted from the OTLP string representation in
 * the service layer).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmSpanEntity {

    /** APM session that collected this span. */
    private String sessionId;

    /** W3C trace-id (hex string). */
    private String traceId;

    /** Span identifier (hex string). */
    private String spanId;

    /** Parent span identifier (hex string, empty for root spans). */
    private String parentSpanId;

    /** Logical service name extracted from resource attributes. */
    private String serviceName;

    /** Span name — typically the method or RPC operation name. */
    private String operationName;

    /** Span kind: INTERNAL, SERVER, CLIENT, PRODUCER, CONSUMER. */
    private String spanKind;

    /** Span start time in nanoseconds since Unix epoch. */
    private long startTimeNs;

    /** Span end time in nanoseconds since Unix epoch. */
    private long endTimeNs;

    /** Status code: OK, ERROR, or UNSET. */
    private String statusCode;

    /** Optional human-readable status message. */
    private String statusMessage;

    /** Flattened span attributes as key-value pairs. */
    private Map<String, String> attributes;

    /** Flattened resource-level attributes as key-value pairs. */
    private Map<String, String> resourceAttributes;

    /**
     * Knowledge-graph node ID that this span maps to.
     * Populated by SpanToKgMapper after ingestion.
     */
    private String kgNodeId;

    /**
     * Match confidence level set by SpanToKgMapper:
     * <ul>
     *   <li>0 — exact match</li>
     *   <li>1 — unique match (single candidate)</li>
     *   <li>2 — overloaded (multiple candidates, best-effort pick)</li>
     *   <li>3 — unmatched</li>
     * </ul>
     */
    private int kgMatchLevel;
}
