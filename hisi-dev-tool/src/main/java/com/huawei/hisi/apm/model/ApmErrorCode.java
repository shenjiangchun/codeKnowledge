package com.huawei.hisi.apm.model;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Unified error code enum for the APM diagnose subsystem.
 * <p>
 * Sections are grouped by numeric range so the code itself signals the
 * originating subsystem:
 * <ul>
 *   <li>1xxx — OTel Agent / target process</li>
 *   <li>2xxx — Span ingestion pipeline</li>
 *   <li>3xxx — Diagnose flow orchestration</li>
 *   <li>4xxx — LLM upstream calls</li>
 *   <li>5xxx — Knowledge graph queries</li>
 *   <li>9xxx — Test / fixture helpers</li>
 * </ul>
 */
public enum ApmErrorCode {

    // 1xxx — OTel Agent
    OTEL_AGENT_DOWNLOAD_FAILED(1001, "Failed to download OpenTelemetry Java agent"),
    OTEL_EXTENSION_MISSING(1002, "Required OpenTelemetry extension is missing"),
    OTEL_MDC_BROKEN(1003, "OTel MDC trace-id propagation appears broken"),
    OTEL_NO_SPAN_RECEIVED(1004, "No spans received from target process within timeout"),
    OTEL_PROCESS_DEAD(1005, "Target instrumented process is no longer alive"),

    // 2xxx — Span Ingestion
    INGEST_PARSE_FAILED(2001, "Failed to parse incoming OTLP payload"),
    INGEST_OVERLOAD(2002, "Span ingestion queue overloaded; dropping payload"),

    // 3xxx — Diagnose Flow
    DIAGNOSE_TRACE_NOT_FOUND(3001, "No trace found for the supplied traceId"),
    DIAGNOSE_REQUEST_DUPLICATED(3002, "A diagnose request for this trace is already in flight"),
    DIAGNOSE_TIMEOUT(3003, "Diagnose flow exceeded its time budget"),
    DIAGNOSE_CANCELLED(3004, "Diagnose flow was cancelled by the caller"),
    DIAGNOSE_INTERNAL_ERROR(3500, "Unexpected internal error during diagnose"),

    // 4xxx — LLM
    LLM_RATE_LIMITED(4001, "LLM provider returned rate-limit response"),
    LLM_TIMEOUT(4002, "LLM call timed out"),
    LLM_BUDGET_EXCEEDED(4003, "LLM token/cost budget exceeded for this request"),
    LLM_INVALID_RESPONSE(4004, "LLM response could not be parsed into the expected schema"),
    LLM_UPSTREAM_ERROR(4500, "LLM provider returned an upstream error"),

    // 5xxx — Knowledge Graph
    KG_UNAVAILABLE(5001, "Knowledge graph service is unavailable"),
    KG_QUERY_TIMEOUT(5002, "Knowledge graph query timed out"),
    KG_METHOD_NOT_FOUND(5003, "Method node not found in the knowledge graph"),

    // 9xxx — Test/Fixture
    TEST_FIXTURE_MISSING(9001, "Required test fixture is missing");

    private static final Map<Integer, ApmErrorCode> BY_CODE = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(ApmErrorCode::getCode, Function.identity()));

    private final int code;
    private final String defaultMessage;

    ApmErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** Numeric error code, stable across releases. */
    public int getCode() {
        return code;
    }

    /** Human-readable default message; callers may override at the use site. */
    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * Reverse-lookup an {@link ApmErrorCode} by its numeric value.
     *
     * @param code numeric error code
     * @return matching enum value, or {@link Optional#empty()} if unknown
     */
    public static Optional<ApmErrorCode> fromCode(int code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
