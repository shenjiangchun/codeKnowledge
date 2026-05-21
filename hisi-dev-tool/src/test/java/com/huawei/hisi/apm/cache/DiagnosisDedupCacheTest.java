package com.huawei.hisi.apm.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosisDedupCacheTest {

    private DiagnosisDedupCache dedup;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dedup = new DiagnosisDedupCache(meterRegistry);
    }

    @Test
    @DisplayName("getExistingReportId returns empty when no entry present")
    void getExistingReportIdReturnsEmptyWhenAbsent() {
        Optional<String> result = dedup.getExistingReportId("trace-a", "/proj/a");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("registerOrGet inserts and returns the new id on first call")
    void registerOrGetInsertsOnFirstCall() {
        String returned = dedup.registerOrGet("trace-a", "/proj/a", "report-1");

        assertThat(returned).isEqualTo("report-1");
        assertThat(dedup.getExistingReportId("trace-a", "/proj/a"))
                .contains("report-1");
        assertThat(dedup.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("registerOrGet returns the original id on second call (atomic dedup)")
    void registerOrGetReturnsOriginalOnSecondCall() {
        String first = dedup.registerOrGet("trace-a", "/proj/a", "report-1");
        String second = dedup.registerOrGet("trace-a", "/proj/a", "report-2");

        assertThat(first).isEqualTo("report-1");
        assertThat(second).isEqualTo("report-1");
        assertThat(dedup.getExistingReportId("trace-a", "/proj/a"))
                .contains("report-1");
        assertThat(dedup.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalidate removes the mapping so subsequent lookups return empty")
    void invalidateRemovesMapping() {
        dedup.registerOrGet("trace-a", "/proj/a", "report-1");
        assertThat(dedup.getExistingReportId("trace-a", "/proj/a")).isPresent();

        dedup.invalidate("trace-a", "/proj/a");

        assertThat(dedup.getExistingReportId("trace-a", "/proj/a")).isEmpty();
        assertThat(dedup.size()).isZero();
    }

    @Test
    @DisplayName("different (trace, project) pairs are stored independently")
    void differentPairsAreIndependent() {
        dedup.registerOrGet("trace-a", "/proj/a", "report-1");
        dedup.registerOrGet("trace-a", "/proj/b", "report-2");
        dedup.registerOrGet("trace-b", "/proj/a", "report-3");

        assertThat(dedup.getExistingReportId("trace-a", "/proj/a")).contains("report-1");
        assertThat(dedup.getExistingReportId("trace-a", "/proj/b")).contains("report-2");
        assertThat(dedup.getExistingReportId("trace-b", "/proj/a")).contains("report-3");
        assertThat(dedup.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("null arguments throw NullPointerException")
    void nullArgumentsThrow() {
        assertThatThrownBy(() -> dedup.getExistingReportId(null, "/proj/a"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dedup.getExistingReportId("trace-a", null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> dedup.registerOrGet(null, "/proj/a", "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dedup.registerOrGet("trace-a", null, "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dedup.registerOrGet("trace-a", "/proj/a", null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> dedup.invalidate(null, "/proj/a"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dedup.invalidate("trace-a", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("size reflects the number of dedup entries")
    void sizeReflectsEntryCount() {
        assertThat(dedup.size()).isZero();

        dedup.registerOrGet("trace-a", "/proj/a", "r1");
        assertThat(dedup.size()).isEqualTo(1);

        dedup.registerOrGet("trace-b", "/proj/a", "r2");
        assertThat(dedup.size()).isEqualTo(2);

        // Duplicate key does not grow the cache
        dedup.registerOrGet("trace-a", "/proj/a", "r-other");
        assertThat(dedup.size()).isEqualTo(2);

        dedup.invalidate("trace-a", "/proj/a");
        assertThat(dedup.size()).isEqualTo(1);
    }
}
