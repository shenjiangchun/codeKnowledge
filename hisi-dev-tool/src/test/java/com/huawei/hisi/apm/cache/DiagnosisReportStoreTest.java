package com.huawei.hisi.apm.cache;

import com.huawei.hisi.apm.model.ApmErrorCode;
import com.huawei.hisi.apm.model.DiagnoseReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosisReportStoreTest {

    private DiagnosisReportStore store;

    @BeforeEach
    void setUp() {
        store = new DiagnosisReportStore(new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("createPending stores a PENDING report retrievable by id")
    void createPending_andFind() {
        DiagnoseReport saved = store.createPending("r1", "trace-1", "/p");

        assertThat(saved.getStatus()).isEqualTo(DiagnoseReport.Status.PENDING);
        assertThat(saved.getStartedAt()).isNotNull();

        Optional<DiagnoseReport> found = store.findById("r1");
        assertThat(found).isPresent();
        assertThat(found.get().getReportId()).isEqualTo("r1");
        assertThat(found.get().getTraceId()).isEqualTo("trace-1");
        assertThat(found.get().getProjectPath()).isEqualTo("/p");
        assertThat(found.get().getStatus()).isEqualTo(DiagnoseReport.Status.PENDING);
    }

    @Test
    @DisplayName("transition follows PENDING -> RUNNING -> DONE")
    void transition_pendingRunningDone() {
        store.createPending("r2", "t", "/p");
        store.transition("r2", DiagnoseReport.Status.RUNNING);
        DiagnoseReport done = store.transition("r2", DiagnoseReport.Status.DONE);

        assertThat(done.getStatus()).isEqualTo(DiagnoseReport.Status.DONE);
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(done.getElapsedMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("markDone sets rootCause, confidence, evidence, finishedAt, elapsedMs")
    void markDone_setsFields() throws InterruptedException {
        store.createPending("r3", "t", "/p");
        store.transition("r3", DiagnoseReport.Status.RUNNING);
        Thread.sleep(2);

        List<DiagnoseReport.EvidenceAnchor> evidence = List.of(
            new DiagnoseReport.EvidenceAnchor("kg_method", "com.A", "doIt", "A.java", 10, null, "snippet")
        );
        DiagnoseReport done = store.markDone("r3", "## Root cause", 0.85, evidence);

        assertThat(done.getStatus()).isEqualTo(DiagnoseReport.Status.DONE);
        assertThat(done.getRootCauseMarkdown()).isEqualTo("## Root cause");
        assertThat(done.getConfidence()).isEqualTo(0.85);
        assertThat(done.getEvidence()).hasSize(1);
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(done.getElapsedMs()).isNotNull().isGreaterThan(0L);
    }

    @Test
    @DisplayName("markFailed sets errorCode, errorMessage, finishedAt")
    void markFailed_setsFields() {
        store.createPending("r4", "t", "/p");
        store.transition("r4", DiagnoseReport.Status.RUNNING);

        DiagnoseReport failed = store.markFailed("r4", ApmErrorCode.LLM_TIMEOUT, "timeout after 60s");

        assertThat(failed.getStatus()).isEqualTo(DiagnoseReport.Status.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo(4002);
        assertThat(failed.getErrorMessage()).isEqualTo("timeout after 60s");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(failed.getElapsedMs()).isNotNull();
    }

    @Test
    @DisplayName("invalid transition DONE -> PENDING throws IllegalStateException")
    void invalidTransition_throws() {
        store.createPending("r5", "t", "/p");
        store.transition("r5", DiagnoseReport.Status.RUNNING);
        store.transition("r5", DiagnoseReport.Status.DONE);

        assertThatThrownBy(() -> store.transition("r5", DiagnoseReport.Status.PENDING))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("terminal state cannot transition further")
    void terminalState_cannotTransition() {
        store.createPending("r6", "t", "/p");
        store.transition("r6", DiagnoseReport.Status.RUNNING);
        store.markDone("r6", "md", 0.9, List.of());

        assertThatThrownBy(() -> store.transition("r6", DiagnoseReport.Status.RUNNING))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PENDING -> CANCELLED is valid and finalises")
    void cancelledFromPending() {
        store.createPending("r7", "t", "/p");
        DiagnoseReport cancelled = store.transition("r7", DiagnoseReport.Status.CANCELLED);

        assertThat(cancelled.getStatus()).isEqualTo(DiagnoseReport.Status.CANCELLED);
        assertThat(cancelled.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("findById returns empty Optional when missing")
    void findById_missing() {
        assertThat(store.findById("missing")).isEmpty();
    }

    @Test
    @DisplayName("transition on missing report throws NoSuchElementException")
    void transition_missing_throws() {
        assertThatThrownBy(() -> store.transition("missing", DiagnoseReport.Status.RUNNING))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("markDoneOrLowConfidence with LOW_CONFIDENCE populates results and finalises")
    void markLowConfidence_setsFields() {
        store.createPending("r-low", "t", "/p");
        store.transition("r-low", DiagnoseReport.Status.RUNNING);

        List<DiagnoseReport.EvidenceAnchor> evidence = List.of(
            new DiagnoseReport.EvidenceAnchor("kg_method", "com.A", "doIt", "A.java", 10, null, "snip")
        );
        DiagnoseReport low = store.markDoneOrLowConfidence("r-low",
            DiagnoseReport.Status.LOW_CONFIDENCE, "## tentative", 0.25, evidence);

        assertThat(low.getStatus()).isEqualTo(DiagnoseReport.Status.LOW_CONFIDENCE);
        assertThat(low.getRootCauseMarkdown()).isEqualTo("## tentative");
        assertThat(low.getConfidence()).isEqualTo(0.25);
        assertThat(low.getEvidence()).hasSize(1);
        assertThat(low.getFinishedAt()).isNotNull();
        assertThat(low.getElapsedMs()).isNotNull();
    }

    @Test
    @DisplayName("markDoneOrLowConfidence rejects non-success terminal statuses")
    void markDoneOrLowConfidence_rejectsInvalidTerminal() {
        store.createPending("r-bad", "t", "/p");
        store.transition("r-bad", DiagnoseReport.Status.RUNNING);

        assertThatThrownBy(() -> store.markDoneOrLowConfidence("r-bad",
            DiagnoseReport.Status.FAILED, "x", 0.5, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
