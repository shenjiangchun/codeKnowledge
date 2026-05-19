package com.huawei.hisi.apm.controller;

import com.huawei.hisi.apm.model.OtlpTraceData;
import com.huawei.hisi.apm.service.SpanIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives OTLP/HTTP JSON trace exports from the OpenTelemetry Java Agent.
 * <p>
 * The agent is configured with {@code OTEL_EXPORTER_OTLP_PROTOCOL=http/json}
 * and posts to {@code {endpoint}/v1/traces}.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class OtlpReceiverController {

    private final SpanIngestionService spanIngestionService;

    /**
     * Receives OTLP/HTTP JSON trace data from the OpenTelemetry Java Agent.
     */
    @PostMapping(value = "/v1/traces", consumes = "application/json")
    public ResponseEntity<Void> receiveTraces(
            @RequestBody OtlpTraceData.ExportTraceServiceRequest traceData) {
        try {
            spanIngestionService.ingest(traceData);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[OTLP Receiver] Failed to process trace data", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
