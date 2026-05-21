package com.huawei.hisi.apm.cache;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionSpanIndexTest {

    private ExceptionSpanIndex index;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        index = new ExceptionSpanIndex(meterRegistry);
    }

    @Test
    @DisplayName("index and retrieve: two error spans for the same traceId are both returned")
    void indexAndRetrieve() {
        ApmSpanEntity span1 = ApmSpanEntity.builder()
                .traceId("trace-1")
                .spanId("span-1")
                .statusCode("ERROR")
                .operationName("POST /orders")
                .build();

        ApmSpanEntity span2 = ApmSpanEntity.builder()
                .traceId("trace-1")
                .spanId("span-2")
                .statusCode("ERROR")
                .operationName("OrderService.create")
                .build();

        index.index(span1);
        index.index(span2);

        List<ApmSpanEntity> result = index.getByTraceId("trace-1");
        assertThat(result).hasSize(2);
        assertThat(result).extracting(ApmSpanEntity::getSpanId)
                .containsExactly("span-1", "span-2");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("skip non-error span: statusCode OK without exception.type is not indexed")
    void skipNonErrorSpan() {
        ApmSpanEntity okSpan = ApmSpanEntity.builder()
                .traceId("trace-2")
                .spanId("span-ok")
                .statusCode("OK")
                .build();

        index.index(okSpan);

        assertThat(index.getByTraceId("trace-2")).isEmpty();
        assertThat(index.hasExceptionSpans("trace-2")).isFalse();
    }

    @Test
    @DisplayName("index span with exception.type attribute even if statusCode is not ERROR")
    void indexSpanWithExceptionTypeAttribute() {
        ApmSpanEntity span = ApmSpanEntity.builder()
                .traceId("trace-3")
                .spanId("span-ex")
                .statusCode("UNSET")
                .attributes(Map.of("exception.type", "java.lang.NullPointerException"))
                .build();

        index.index(span);

        assertThat(index.getByTraceId("trace-3")).hasSize(1);
        assertThat(index.hasExceptionSpans("trace-3")).isTrue();
    }

    @Test
    @DisplayName("returns defensive copy: mutating the returned list does not affect cache")
    void returnsDefensiveCopy() {
        ApmSpanEntity span = ApmSpanEntity.builder()
                .traceId("trace-4")
                .spanId("span-def")
                .statusCode("ERROR")
                .build();

        index.index(span);

        List<ApmSpanEntity> result = index.getByTraceId("trace-4");
        assertThat(result).hasSize(1);

        // Attempting to mutate the returned list should throw
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> result.add(ApmSpanEntity.builder().build())
        );

        // Original cache is unaffected
        assertThat(index.getByTraceId("trace-4")).hasSize(1);
    }

    @Test
    @DisplayName("eviction works: index then evict leaves empty result")
    void evictionWorks() {
        ApmSpanEntity span = ApmSpanEntity.builder()
                .traceId("trace-5")
                .spanId("span-evict")
                .statusCode("ERROR")
                .build();

        index.index(span);
        assertThat(index.hasExceptionSpans("trace-5")).isTrue();

        index.evict("trace-5");
        assertThat(index.getByTraceId("trace-5")).isEmpty();
        assertThat(index.hasExceptionSpans("trace-5")).isFalse();
    }

    @Test
    @DisplayName("Micrometer stats registered for the cache")
    void micrometerStatsRegistered() {
        // After construction, the cache should have registered metrics
        // CaffeineCacheMetrics registers gauges like cache.size
        assertThat(meterRegistry.find("cache.size")
                .tag("cache", "apm.exception.span.index")
                .gauge()).isNotNull();
    }
}
