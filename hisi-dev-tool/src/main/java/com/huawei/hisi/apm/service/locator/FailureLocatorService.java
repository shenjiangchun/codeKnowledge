package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.cache.DiagnosisDedupCache;
import com.huawei.hisi.apm.cache.DiagnosisReportStore;
import com.huawei.hisi.apm.cache.ExceptionSpanIndex;
import com.huawei.hisi.apm.config.ApmDiagnoseProperties;
import com.huawei.hisi.apm.model.ApmErrorCode;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport;
import com.huawei.hisi.apm.model.DiagnoseRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Async orchestrator for the APM Failure Locator MVP.
 * <p>The synchronous {@link #startDiagnose(DiagnoseRequest)} entry point performs
 * dedup, creates a PENDING {@link DiagnoseReport} and dispatches the actual
 * pipeline on the dedicated {@code apmDiagnoseExecutor}. The async pipeline
 * gathers exception spans, optionally enriches them via the knowledge graph,
 * invokes the LLM, and finalises the report.
 *
 * @author HiSi DevTool Team
 */
@Service
public class FailureLocatorService {

    private static final Logger LOG = LoggerFactory.getLogger(FailureLocatorService.class);

    private final DiagnosisReportStore reportStore;
    private final DiagnosisDedupCache dedupCache;
    private final ExceptionSpanIndex exceptionSpanIndex;
    private final ApmDiagnoseProperties props;
    private final Executor executor;
    private final KgEnricher kgEnricher;
    private final LlmDiagnoser llmDiagnoser;

    /**
     * Constructor injection of all dependencies. The {@code apmDiagnoseExecutor}
     * is qualified by name so Spring picks the isolated diagnose pool rather
     * than the general {@code @Async} pool.
     *
     * @param reportStore         async report store
     * @param dedupCache          dedup cache for in-flight diagnoses
     * @param exceptionSpanIndex  index of exception spans by traceId
     * @param props               diagnose pipeline configuration
     * @param executor            dedicated diagnose executor
     * @param kgEnricher          KG enrichment port (no-op default supplied by config)
     * @param llmDiagnoser        LLM diagnose port (stub default supplied by config)
     */
    public FailureLocatorService(DiagnosisReportStore reportStore,
                                 DiagnosisDedupCache dedupCache,
                                 ExceptionSpanIndex exceptionSpanIndex,
                                 ApmDiagnoseProperties props,
                                 @Qualifier("apmDiagnoseExecutor") Executor executor,
                                 KgEnricher kgEnricher,
                                 LlmDiagnoser llmDiagnoser) {
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore");
        this.dedupCache = Objects.requireNonNull(dedupCache, "dedupCache");
        this.exceptionSpanIndex = Objects.requireNonNull(exceptionSpanIndex, "exceptionSpanIndex");
        this.props = Objects.requireNonNull(props, "props");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.kgEnricher = Objects.requireNonNull(kgEnricher, "kgEnricher");
        this.llmDiagnoser = Objects.requireNonNull(llmDiagnoser, "llmDiagnoser");
    }

    /**
     * Synchronous entry point: dedup check, create PENDING report, dispatch the
     * async pipeline. Returns immediately with the reportId so the caller can
     * poll {@code GET /api/apm/diagnose/{reportId}}.
     *
     * @param request the diagnose request (non-null)
     * @return the reportId â€?either freshly created or the existing in-flight one
     */
    public String startDiagnose(DiagnoseRequest request) {
        Objects.requireNonNull(request, "request");
        if (Boolean.TRUE.equals(request.forceRefresh())) {
            dedupCache.invalidate(request.traceId(), request.projectPath());
        } else {
            Optional<String> existing = dedupCache.getExistingReportId(
                request.traceId(), request.projectPath());
            if (existing.isPresent()) {
                LOG.debug("Dedup hit traceId={} projectPath={} reportId={}",
                    request.traceId(), request.projectPath(), existing.get());
                return existing.get();
            }
        }

        String reportId = UUID.randomUUID().toString();
        reportStore.createPending(reportId, request.traceId(), request.projectPath());
        String registered = dedupCache.registerOrGet(
            request.traceId(), request.projectPath(), reportId);
        if (!registered.equals(reportId)) {
            // Lost the race; abandon our just-created entry (bounded by 2h TTL).
            LOG.debug("Lost dedup race; using winner reportId={} (ours={})", registered, reportId);
            return registered;
        }

        executor.execute(() -> runPipeline(reportId, request));
        return reportId;
    }

    /**
     * Async pipeline body. Runs on {@code apmDiagnoseExecutor}.
     */
    private void runPipeline(String reportId, DiagnoseRequest request) {
        long deadlineNanos = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(props.getTimeoutSeconds());
        boolean inLlmCall = false;
        try {
            reportStore.transition(reportId, DiagnoseReport.Status.RUNNING);

            List<ApmSpanEntity> spans = exceptionSpanIndex.getByTraceId(request.traceId());
            if (spans.isEmpty()) {
                reportStore.markFailed(reportId,
                    ApmErrorCode.DIAGNOSE_NO_EXCEPTION_SPANS,
                    "No exception spans indexed for traceId=" + request.traceId());
                LOG.debug("Pipeline complete reportId={} status=FAILED", reportId);
                return;
            }
            if (deadlinePassed(deadlineNanos)) {
                markTimeout(reportId);
                LOG.debug("Pipeline complete reportId={} status=TIMEOUT", reportId);
                return;
            }

            List<DiagnoseReport.EvidenceAnchor> evidence = props.isKgEnabled()
                ? defensiveCopy(kgEnricher.enrich(request.projectPath(), spans))
                : List.of();
            if (deadlinePassed(deadlineNanos)) {
                markTimeout(reportId);
                LOG.debug("Pipeline complete reportId={} status=TIMEOUT", reportId);
                return;
            }

            inLlmCall = true;
            LlmDiagnoser.LlmResult llm = props.isLlmEnabled()
                ? llmDiagnoser.diagnose(request.projectPath(), spans, evidence, request.userNote())
                : new LlmDiagnoser.LlmResult("(LLM disabled â€?template fallback)", 0.6);
            inLlmCall = false;
            if (deadlinePassed(deadlineNanos)) {
                markTimeout(reportId);
                LOG.debug("Pipeline complete reportId={} status=TIMEOUT", reportId);
                return;
            }

            DiagnoseReport.Status terminal = llm.confidence() < props.getConfidenceLowThreshold()
                ? DiagnoseReport.Status.LOW_CONFIDENCE
                : DiagnoseReport.Status.DONE;
            reportStore.markDoneOrLowConfidence(reportId, terminal,
                llm.rootCauseMarkdown(), llm.confidence(), evidence);
            LOG.debug("Pipeline complete reportId={} status={}", reportId, terminal);
        } catch (IllegalStateException stateBug) {
            // State machine should never disagree with our flow â€?surface the bug.
            LOG.error("State machine violation in diagnose pipeline reportId={}", reportId, stateBug);
            throw stateBug;
        } catch (Exception ex) {
            LOG.warn("Diagnose pipeline failed for reportId={}, traceId={}",
                reportId, request.traceId(), ex);
            ApmErrorCode code = mapException(ex, inLlmCall);
            try {
                reportStore.markFailed(reportId, code,
                    ex.getMessage() == null ? code.getDefaultMessage() : ex.getMessage());
            } catch (IllegalStateException already) {
                LOG.debug("Report {} already terminal; ignoring failure marker", reportId);
            }
            LOG.debug("Pipeline complete reportId={} status=FAILED", reportId);
        }
    }

    /**
     * Map an unexpected exception to a canonical {@link ApmErrorCode}.
     * Package-private to allow targeted unit testing without reflection.
     *
     * @param ex          the thrown exception
     * @param inLlmCall   true if the exception bubbled up while invoking the LLM
     * @return the matching error code
     */
    ApmErrorCode mapException(Throwable ex, boolean inLlmCall) {
        if (ex instanceof TimeoutException) {
            return inLlmCall ? ApmErrorCode.LLM_TIMEOUT : ApmErrorCode.DIAGNOSE_TIMEOUT;
        }
        return ApmErrorCode.DIAGNOSE_INTERNAL_ERROR;
    }

    private void markTimeout(String reportId) {
        try {
            reportStore.transition(reportId, DiagnoseReport.Status.TIMEOUT);
        } catch (IllegalStateException already) {
            LOG.debug("Report {} already terminal; ignoring TIMEOUT marker", reportId);
        }
    }

    private boolean deadlinePassed(long deadlineNanos) {
        return System.nanoTime() > deadlineNanos;
    }

    private static List<DiagnoseReport.EvidenceAnchor> defensiveCopy(
            List<DiagnoseReport.EvidenceAnchor> source) {
        return source == null ? List.of() : List.copyOf(source);
    }
}
