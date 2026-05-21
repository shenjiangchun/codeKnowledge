package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.cache.DiagnosisDedupCache;
import com.huawei.hisi.apm.cache.DiagnosisReportStore;
import com.huawei.hisi.apm.cache.ExceptionSpanIndex;
import com.huawei.hisi.apm.config.ApmDiagnoseProperties;
import com.huawei.hisi.apm.model.ApmErrorCode;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport;
import com.huawei.hisi.apm.model.DiagnoseRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FailureLocatorServiceTest {

    private DiagnosisReportStore reportStore;
    private DiagnosisDedupCache dedupCache;
    private ExceptionSpanIndex exceptionSpanIndex;
    private ApmDiagnoseProperties props;
    private CountingExecutor executor;
    private KgEnricher kgEnricher;
    private LlmDiagnoser llmDiagnoser;
    private FailureLocatorService service;

    private static final String TRACE_ID = "trace-abc";
    private static final String PROJECT = "/abs/proj";

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        reportStore = new DiagnosisReportStore(registry);
        dedupCache = new DiagnosisDedupCache(registry);
        exceptionSpanIndex = mock(ExceptionSpanIndex.class);
        props = new ApmDiagnoseProperties();
        // defaults: kgEnabled=true, llmEnabled=true, confidenceLowThreshold=0.5
        executor = new CountingExecutor();
        kgEnricher = mock(KgEnricher.class);
        llmDiagnoser = mock(LlmDiagnoser.class);

        service = new FailureLocatorService(reportStore, dedupCache, exceptionSpanIndex,
            props, executor, kgEnricher, llmDiagnoser);
    }

    private ApmSpanEntity errorSpan() {
        return ApmSpanEntity.builder()
            .traceId(TRACE_ID)
            .spanId("span-1")
            .statusCode("ERROR")
            .build();
    }

    private DiagnoseRequest request(boolean forceRefresh) {
        return new DiagnoseRequest(TRACE_ID, PROJECT, null, forceRefresh, "user note");
    }

    @Test
    @DisplayName("happy path: confidence >= threshold yields DONE with evidence + rootCause")
    void happyPath_done() {
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of(errorSpan()));
        var anchor = new DiagnoseReport.EvidenceAnchor("kg_method", "com.X", "m", "X.java", 5, null, "snip");
        when(kgEnricher.enrich(eq(PROJECT), anyList())).thenReturn(List.of(anchor));
        when(llmDiagnoser.diagnose(eq(PROJECT), anyList(), anyList(), anyString()))
            .thenReturn(new LlmDiagnoser.LlmResult("## root", 0.85));

        String reportId = service.startDiagnose(request(false));

        DiagnoseReport report = reportStore.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(DiagnoseReport.Status.DONE);
        assertThat(report.getRootCauseMarkdown()).isEqualTo("## root");
        assertThat(report.getConfidence()).isEqualTo(0.85);
        assertThat(report.getEvidence()).hasSize(1);
    }

    @Test
    @DisplayName("low confidence: result < threshold yields LOW_CONFIDENCE terminal status")
    void lowConfidence() {
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of(errorSpan()));
        when(kgEnricher.enrich(any(), any())).thenReturn(List.of());
        when(llmDiagnoser.diagnose(any(), any(), any(), any()))
            .thenReturn(new LlmDiagnoser.LlmResult("inconclusive", 0.3));

        String reportId = service.startDiagnose(request(false));

        DiagnoseReport report = reportStore.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(DiagnoseReport.Status.LOW_CONFIDENCE);
        assertThat(report.getConfidence()).isEqualTo(0.3);
        assertThat(report.getRootCauseMarkdown()).isEqualTo("inconclusive");
    }

    @Test
    @DisplayName("no exception spans: FAILED with DIAGNOSE_NO_EXCEPTION_SPANS")
    void noExceptionSpans_failed() {
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of());

        String reportId = service.startDiagnose(request(false));

        DiagnoseReport report = reportStore.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(DiagnoseReport.Status.FAILED);
        assertThat(report.getErrorCode())
            .isEqualTo(ApmErrorCode.DIAGNOSE_NO_EXCEPTION_SPANS.getCode());
        verify(kgEnricher, never()).enrich(any(), any());
        verify(llmDiagnoser, never()).diagnose(any(), any(), any(), any());
    }

    @Test
    @DisplayName("dedup hit: second call returns same reportId, pipeline runs once")
    void dedup_hit() {
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of(errorSpan()));
        when(kgEnricher.enrich(any(), any())).thenReturn(List.of());
        when(llmDiagnoser.diagnose(any(), any(), any(), any()))
            .thenReturn(new LlmDiagnoser.LlmResult("md", 0.8));

        String r1 = service.startDiagnose(request(false));
        String r2 = service.startDiagnose(request(false));

        assertThat(r1).isEqualTo(r2);
        assertThat(executor.count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("forceRefresh: invalidates dedup, pipeline runs again")
    void forceRefresh_runsAgain() {
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of(errorSpan()));
        when(kgEnricher.enrich(any(), any())).thenReturn(List.of());
        when(llmDiagnoser.diagnose(any(), any(), any(), any()))
            .thenReturn(new LlmDiagnoser.LlmResult("md", 0.8));

        String r1 = service.startDiagnose(request(false));
        String r2 = service.startDiagnose(request(true));

        assertThat(r1).isNotEqualTo(r2);
        assertThat(executor.count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("LLM disabled: stub fallback used, llmDiagnoser not invoked")
    void llmDisabled_usesFallback() {
        props.setLlmEnabled(false);
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of(errorSpan()));
        when(kgEnricher.enrich(any(), any())).thenReturn(List.of());

        String reportId = service.startDiagnose(request(false));

        DiagnoseReport report = reportStore.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(DiagnoseReport.Status.DONE);
        assertThat(report.getRootCauseMarkdown()).contains("LLM disabled");
        verify(llmDiagnoser, never()).diagnose(any(), any(), any(), any());
    }

    @Test
    @DisplayName("KG disabled: enricher not invoked, evidence list empty")
    void kgDisabled_skipsEnricher() {
        props.setKgEnabled(false);
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of(errorSpan()));
        when(llmDiagnoser.diagnose(any(), any(), any(), any()))
            .thenReturn(new LlmDiagnoser.LlmResult("md", 0.9));

        String reportId = service.startDiagnose(request(false));

        DiagnoseReport report = reportStore.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(DiagnoseReport.Status.DONE);
        assertThat(report.getEvidence()).isEmpty();
        verify(kgEnricher, never()).enrich(any(), any());
    }

    @Test
    @DisplayName("LLM throws RuntimeException: FAILED with DIAGNOSE_INTERNAL_ERROR")
    void llmThrows_internalError() {
        when(exceptionSpanIndex.getByTraceId(TRACE_ID)).thenReturn(List.of(errorSpan()));
        when(kgEnricher.enrich(any(), any())).thenReturn(List.of());
        when(llmDiagnoser.diagnose(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));

        String reportId = service.startDiagnose(request(false));

        DiagnoseReport report = reportStore.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(DiagnoseReport.Status.FAILED);
        assertThat(report.getErrorCode())
            .isEqualTo(ApmErrorCode.DIAGNOSE_INTERNAL_ERROR.getCode());
        assertThat(report.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("mapException: TimeoutException in LLM call maps to LLM_TIMEOUT, else DIAGNOSE_TIMEOUT")
    void mapException_timeout() {
        var timeout = new TimeoutException("slow");
        assertThat(service.mapException(timeout, true)).isEqualTo(ApmErrorCode.LLM_TIMEOUT);
        assertThat(service.mapException(timeout, false)).isEqualTo(ApmErrorCode.DIAGNOSE_TIMEOUT);
        assertThat(service.mapException(new RuntimeException(), false))
            .isEqualTo(ApmErrorCode.DIAGNOSE_INTERNAL_ERROR);
    }

    /** Inline executor that counts submissions for verification. */
    private static final class CountingExecutor implements Executor {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            count.incrementAndGet();
            command.run();
        }
    }
}
