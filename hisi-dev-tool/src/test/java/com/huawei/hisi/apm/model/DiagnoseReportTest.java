package com.huawei.hisi.apm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiagnoseReport} and its nested {@code EvidenceAnchor} record.
 */
class DiagnoseReportTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("pending(...) factory returns PENDING status with non-null startedAt")
    void pending_factory_returnsPendingReport() {
        DiagnoseReport r = DiagnoseReport.pending("rid-1", "trace-1", "C:/proj").build();
        assertThat(r.getStatus()).isEqualTo(DiagnoseReport.Status.PENDING);
        assertThat(r.getReportId()).isEqualTo("rid-1");
        assertThat(r.getTraceId()).isEqualTo("trace-1");
        assertThat(r.getProjectPath()).isEqualTo("C:/proj");
        assertThat(r.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("EvidenceAnchor round-trips through Jackson")
    void evidenceAnchor_jacksonRoundTrip() throws Exception {
        DiagnoseReport.EvidenceAnchor a = new DiagnoseReport.EvidenceAnchor(
            "exception_stack", "com.Foo", "bar", "src/Foo.java", 42, "span-1", "throw new RTE();");
        String json = mapper.writeValueAsString(a);
        DiagnoseReport.EvidenceAnchor back = mapper.readValue(json, DiagnoseReport.EvidenceAnchor.class);
        assertThat(back).isEqualTo(a);
    }

    @Test
    @DisplayName("Full report round-trips through Jackson")
    void report_jacksonRoundTrip() throws Exception {
        DiagnoseReport r = DiagnoseReport.builder()
            .reportId("rid")
            .traceId("trace")
            .projectPath("C:/proj")
            .status(DiagnoseReport.Status.DONE)
            .errorCode(3001)
            .errorMessage("not found")
            .confidence(0.85)
            .rootCauseMarkdown("# RCA")
            .evidence(List.of(new DiagnoseReport.EvidenceAnchor(
                "slow_span", "C", "m", "f.java", 1, "s", "snippet")))
            .startedAt(Instant.parse("2025-01-01T00:00:00Z"))
            .finishedAt(Instant.parse("2025-01-01T00:00:01Z"))
            .elapsedMs(1000L)
            .build();
        String json = mapper.writeValueAsString(r);
        DiagnoseReport back = mapper.readValue(json, DiagnoseReport.class);
        assertThat(back).isEqualTo(r);
    }

    @Test
    @DisplayName("Status enum contains all spec values")
    void status_enum_hasAllValues() {
        assertThat(DiagnoseReport.Status.values()).contains(
            DiagnoseReport.Status.PENDING,
            DiagnoseReport.Status.RUNNING,
            DiagnoseReport.Status.DONE,
            DiagnoseReport.Status.FAILED,
            DiagnoseReport.Status.CANCELLED,
            DiagnoseReport.Status.TIMEOUT,
            DiagnoseReport.Status.LOW_CONFIDENCE);
    }
}
