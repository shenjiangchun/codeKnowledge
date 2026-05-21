package com.huawei.hisi.apm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.huawei.hisi.apm.model.OtlpTraceData;
import com.huawei.hisi.apm.service.SpanIngestionService;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives OTLP/HTTP protobuf trace exports from the OpenTelemetry Java Agent.
 *
 * <p>The agent is configured with {@code OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf}
 * (OTel Java Agent 2.x 不再支持 http/json，仅支持 grpc / http/protobuf).
 * Spans arrive POST {@code /v1/traces} with content-type {@code application/x-protobuf}
 * carrying a serialized {@link ExportTraceServiceRequest}.
 *
 * <p>解码策略：用 {@link JsonFormat} 把 protobuf message 转回 OTLP/JSON 字符串，
 * 再交给 Jackson 反序列化为已有的 {@link OtlpTraceData.ExportTraceServiceRequest}
 * POJO，下游 {@link SpanIngestionService} 完全不需要改动。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class OtlpReceiverController {

    private final SpanIngestionService spanIngestionService;
    private final ObjectMapper objectMapper;

    // JsonFormat.Printer 默认输出 camelCase(traceId/resourceSpans/scopeSpans...)，
    // 正好匹配 OtlpTraceData POJO 上的 @JsonProperty。
    // 不要加 .preservingProtoFieldNames() —— 那会输出 snake_case(resource_spans...)，
    // 导致 Jackson 反序列化时所有嵌套字段全部为 null，spans 被静默丢弃。
    // .printingEnumsAsInts() —— protobuf enum(SpanKind/StatusCode) 默认输出名称字符串
    // (如 "SPAN_KIND_SERVER")，但 POJO Span.kind / Status.code 是 Integer，会反序列化失败。
    private static final JsonFormat.Printer PROTO_JSON_PRINTER = JsonFormat.printer()
            .printingEnumsAsInts()
            .omittingInsignificantWhitespace();

    @PostMapping(value = "/v1/traces", consumes = "application/x-protobuf")
    public ResponseEntity<Void> receiveTraces(@RequestBody byte[] body) {
        try {
            log.info("[OTLP Receiver] Received protobuf trace payload ({} bytes)",
                    body == null ? 0 : body.length);
            ExportTraceServiceRequest proto = ExportTraceServiceRequest.parseFrom(body);
            String json = PROTO_JSON_PRINTER.print(proto);
            OtlpTraceData.ExportTraceServiceRequest traceData =
                    objectMapper.readValue(json, OtlpTraceData.ExportTraceServiceRequest.class);
            int rsCount = traceData.resourceSpans() == null ? 0 : traceData.resourceSpans().size();
            log.info("[OTLP Receiver] Parsed {} resourceSpans from payload", rsCount);
            spanIngestionService.ingest(traceData);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[OTLP Receiver] Failed to process protobuf trace data (size={} bytes)",
                    body == null ? 0 : body.length, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 兼容旧客户端（如果仍以 application/json 发送，本端点会同时处理）。
     * 主链路是 protobuf；保留 JSON 仅为向后兼容/调试。
     */
    @PostMapping(value = "/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receiveTracesJson(
            @RequestBody OtlpTraceData.ExportTraceServiceRequest traceData) {
        try {
            spanIngestionService.ingest(traceData);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[OTLP Receiver] Failed to process JSON trace data", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
