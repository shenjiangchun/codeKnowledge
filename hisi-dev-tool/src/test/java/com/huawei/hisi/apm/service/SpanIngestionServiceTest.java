package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.cache.ExceptionSpanIndex;
import com.huawei.hisi.apm.handler.ApmWebSocketHandler;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.OtlpTraceData;
import com.huawei.hisi.apm.repository.ApmSpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpanIngestionServiceTest {

    @Mock
    private ApmSpanRepository spanRepository;

    @Mock
    private SpanToKgMapper spanToKgMapper;

    @Mock
    private ApmWebSocketHandler webSocketHandler;

    @Mock
    private ExceptionSpanIndex exceptionSpanIndex;

    private SpanIngestionService service;

    @BeforeEach
    void setUp() {
        service = new SpanIngestionService(
                spanRepository, spanToKgMapper, webSocketHandler, exceptionSpanIndex);
    }

    // ── Helper builders ───────────────────────────────────────────

    /**
     * Create a span with mutable attributes for silent_catch testing.
     */
    private static ApmSpanEntity buildSpan(String statusCode, String spanKind,
                                            String operationName,
                                            Map<String, String> attributes) {
        // Ensure attributes map is mutable (as the real code produces)
        Map<String, String> mutableAttrs = (attributes != null)
                ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();

        return ApmSpanEntity.builder()
                .sessionId("session-1")
                .traceId("trace-1")
                .spanId("span-" + System.nanoTime())
                .parentSpanId("")
                .serviceName("test-service")
                .operationName(operationName)
                .spanKind(spanKind)
                .startTimeNs(1000L)
                .endTimeNs(2000L)
                .statusCode(statusCode)
                .statusMessage(null)
                .attributes(mutableAttrs)
                .resourceAttributes(Map.of())
                .kgNodeId(null)
                .kgMatchLevel(3)
                .build();
    }

    // ── Silent Catch Detection ────────────────────────────────────

    @Nested
    @DisplayName("Silent Catch Detection")
    class SilentCatchDetection {

        @Test
        @DisplayName("marks silent_catch when exception.type present and status is not ERROR")
        void silentCatch_detectedWhenExceptionTypePresent_andStatusNotError() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "java.lang.NullPointerException");
            attrs.put("exception.message", "value was null");

            ApmSpanEntity span = buildSpan("OK", "INTERNAL", "UserService.getUser", attrs);

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isTrue();
        }

        @Test
        @DisplayName("does not mark when status is ERROR (exception was propagated)")
        void silentCatch_notDetectedWhenStatusIsError() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "java.lang.RuntimeException");

            ApmSpanEntity span = buildSpan("ERROR", "INTERNAL", "UserService.getUser", attrs);

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isFalse();
        }

        @Test
        @DisplayName("does not mark when no exception.type attribute exists")
        void silentCatch_notDetectedWithoutExceptionType() {
            ApmSpanEntity span = buildSpan("OK", "INTERNAL", "UserService.getUser",
                    Map.of("http.method", "GET"));

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isFalse();
        }

        @Test
        @DisplayName("UNSET status also triggers detection when exception.type present")
        void silentCatch_detectedOnUnsetStatus() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "java.io.IOException");

            ApmSpanEntity span = buildSpan("UNSET", "SERVER", "FileService.read", attrs);

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isTrue();
        }

        // ── Exemption tests ──

        @Test
        @DisplayName("exempt: HTTP handler span (operationName starts with 'HTTP ')")
        void silentCatch_exemptForHttpSpan() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "java.lang.IllegalArgumentException");

            ApmSpanEntity span = buildSpan("OK", "SERVER", "HTTP GET /api/users", attrs);

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isFalse();
        }

        @Test
        @DisplayName("exempt: DB query span (operationName starts with SELECT/INSERT/UPDATE/DELETE)")
        void silentCatch_exemptForDbSpan() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "org.postgresql.util.PSQLException");

            // Test SELECT
            ApmSpanEntity selectSpan = buildSpan("OK", "CLIENT", "SELECT * FROM users", attrs);
            assertThat(SpanIngestionService.isSilentCatchSuspected(selectSpan)).isFalse();

            // Test INSERT
            ApmSpanEntity insertSpan = buildSpan("OK", "CLIENT", "INSERT INTO orders", attrs);
            assertThat(SpanIngestionService.isSilentCatchSuspected(insertSpan)).isFalse();

            // Test UPDATE
            ApmSpanEntity updateSpan = buildSpan("OK", "CLIENT", "UPDATE users SET", attrs);
            assertThat(SpanIngestionService.isSilentCatchSuspected(updateSpan)).isFalse();

            // Test DELETE
            ApmSpanEntity deleteSpan = buildSpan("OK", "CLIENT", "DELETE FROM users", attrs);
            assertThat(SpanIngestionService.isSilentCatchSuspected(deleteSpan)).isFalse();
        }

        @Test
        @DisplayName("exempt: CLIENT span kind")
        void silentCatch_exemptForClientKind() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "java.net.ConnectException");

            ApmSpanEntity span = buildSpan("OK", "CLIENT", "FeignClient.call", attrs);

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isFalse();
        }

        @Test
        @DisplayName("exempt: span with db.system attribute")
        void silentCatch_exemptForDbSystemAttribute() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "org.h2.jdbc.JdbcSQLException");
            attrs.put("db.system", "postgresql");

            ApmSpanEntity span = buildSpan("OK", "INTERNAL", "JdbcTemplate.query", attrs);

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isFalse();
        }

        @Test
        @DisplayName("exempt: span with messaging.system attribute")
        void silentCatch_exemptForMessagingSpan() {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", "org.apache.kafka.common.errors.TimeoutException");
            attrs.put("messaging.system", "kafka");

            ApmSpanEntity span = buildSpan("OK", "PRODUCER", "KafkaProducer.send", attrs);

            assertThat(SpanIngestionService.isSilentCatchSuspected(span)).isFalse();
        }
    }

    // ── Exception Span Indexing ───────────────────────────────────

    @Nested
    @DisplayName("Exception Span Indexing")
    class ExceptionSpanIndexing {

        @Test
        @DisplayName("all ingested spans are fed to ExceptionSpanIndex")
        void exceptionSpans_fedToIndex() {
            // Register session so ingestion proceeds
            service.registerSession("test-service", "session-1", "/project");

            // Build two spans: one ERROR, one OK
            ApmSpanEntity errorSpan = buildSpan("ERROR", "INTERNAL", "OrderService.create",
                    Map.of("exception.type", "RuntimeException"));
            ApmSpanEntity okSpan = buildSpan("OK", "INTERNAL", "OrderService.list",
                    Map.of("http.method", "GET"));

            // Build OTLP trace data wrapping these two spans
            OtlpTraceData.ExportTraceServiceRequest request = buildOtlpRequest(
                    "test-service", List.of(
                            buildOtlpSpan(errorSpan),
                            buildOtlpSpan(okSpan)
                    ));

            service.ingest(request);

            // ExceptionSpanIndex.index() should have been called for BOTH spans
            // (the index itself filters internally)
            verify(exceptionSpanIndex, times(2)).index(any(ApmSpanEntity.class));
        }
    }

    // ── OTLP test data builders ───────────────────────────────────

    private static OtlpTraceData.ExportTraceServiceRequest buildOtlpRequest(
            String serviceName, List<OtlpTraceData.Span> spans) {

        OtlpTraceData.KeyValue serviceKv = new OtlpTraceData.KeyValue(
                "service.name",
                new OtlpTraceData.AnyValue(serviceName, null, null, null));

        OtlpTraceData.Resource resource = new OtlpTraceData.Resource(List.of(serviceKv));

        OtlpTraceData.ScopeSpans scopeSpans = new OtlpTraceData.ScopeSpans(spans);

        OtlpTraceData.ResourceSpans resourceSpans = new OtlpTraceData.ResourceSpans(
                resource, List.of(scopeSpans));

        return new OtlpTraceData.ExportTraceServiceRequest(List.of(resourceSpans));
    }

    private static OtlpTraceData.Span buildOtlpSpan(ApmSpanEntity entity) {
        // Convert attributes back to OTLP KeyValue list
        List<OtlpTraceData.KeyValue> attrKvs = entity.getAttributes().entrySet().stream()
                .map(e -> new OtlpTraceData.KeyValue(
                        e.getKey(),
                        new OtlpTraceData.AnyValue(e.getValue(), null, null, null)))
                .toList();

        // Map status code string back to integer
        int statusCodeInt = switch (entity.getStatusCode()) {
            case "OK" -> 1;
            case "ERROR" -> 2;
            default -> 0; // UNSET
        };

        // Map span kind string back to integer
        int kindInt = switch (entity.getSpanKind()) {
            case "INTERNAL" -> 1;
            case "SERVER" -> 2;
            case "CLIENT" -> 3;
            case "PRODUCER" -> 4;
            case "CONSUMER" -> 5;
            default -> 0;
        };

        // Span record parameter order: traceId, spanId, parentSpanId, name, kind,
        // startTimeUnixNano, endTimeUnixNano, status, attributes, events
        return new OtlpTraceData.Span(
                entity.getTraceId(),
                entity.getSpanId(),
                entity.getParentSpanId(),
                entity.getOperationName(),
                kindInt,
                String.valueOf(entity.getStartTimeNs()),
                String.valueOf(entity.getEndTimeNs()),
                new OtlpTraceData.Status(statusCodeInt, entity.getStatusMessage()),
                attrKvs,
                null // events
        );
    }
}
