package com.huawei.hisi.apm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Java records mirroring the OTLP/HTTP JSON trace payload structure.
 * <p>
 * The OTel Java Agent sends spans to {@code /v1/traces} as JSON.
 * Only fields needed by HiSi DevTool are modeled here; unknown fields
 * are silently ignored via {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
public final class OtlpTraceData {

    private OtlpTraceData() {
        // Utility class — no instantiation
    }

    // ── Top-level envelope ──────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportTraceServiceRequest(
            @JsonProperty("resourceSpans") List<ResourceSpans> resourceSpans
    ) {}

    // ── Resource layer ──────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceSpans(
            @JsonProperty("resource") Resource resource,
            @JsonProperty("scopeSpans") List<ScopeSpans> scopeSpans
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resource(
            @JsonProperty("attributes") List<KeyValue> attributes
    ) {}

    // ── Scope / Spans ───────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScopeSpans(
            @JsonProperty("spans") List<Span> spans
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Span(
            @JsonProperty("traceId") String traceId,
            @JsonProperty("spanId") String spanId,
            @JsonProperty("parentSpanId") String parentSpanId,
            @JsonProperty("name") String name,
            @JsonProperty("kind") Integer kind,
            @JsonProperty("startTimeUnixNano") String startTimeUnixNano,
            @JsonProperty("endTimeUnixNano") String endTimeUnixNano,
            @JsonProperty("status") Status status,
            @JsonProperty("attributes") List<KeyValue> attributes,
            @JsonProperty("events") List<Event> events
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
            @JsonProperty("code") Integer code,
            @JsonProperty("message") String message
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(
            @JsonProperty("name") String name,
            @JsonProperty("timeUnixNano") String timeUnixNano,
            @JsonProperty("attributes") List<KeyValue> attributes
    ) {}

    // ── Key-Value with polymorphic value ────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyValue(
            @JsonProperty("key") String key,
            @JsonProperty("value") AnyValue value
    ) {}

    /**
     * OTLP AnyValue — only one of the nullable fields is populated per instance.
     * <p>
     * Using a simple POJO-style record with nullable fields rather than
     * Jackson polymorphic deserialization, since the JSON structure uses
     * distinct field names ({@code stringValue}, {@code intValue}, etc.)
     * rather than a discriminator.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnyValue(
            @JsonProperty("stringValue") String stringValue,
            @JsonProperty("intValue") String intValue,
            @JsonProperty("boolValue") Boolean boolValue,
            @JsonProperty("arrayValue") ArrayValue arrayValue
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArrayValue(
            @JsonProperty("values") List<AnyValue> values
    ) {}
}
