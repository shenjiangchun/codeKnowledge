package com.huawei.hisi.apm.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huawei.hisi.apm.model.ApmErrorCode;
import com.huawei.hisi.apm.model.DiagnoseReport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store for asynchronous {@link DiagnoseReport} objects.
 * <p>
 * Backed by a Caffeine cache with {@code expireAfterAccess(2h)} so that
 * frontend polling keeps a report alive for as long as it is being viewed,
 * and a {@code maximumSize(500)} cap to bound memory.
 * <p>
 * The store enforces a strict state machine on report lifecycle transitions
 * (see {@link #validateTransition(DiagnoseReport.Status, DiagnoseReport.Status)})
 * and atomically updates entries via {@link Map#compute(Object, java.util.function.BiFunction)}
 * to remain safe under concurrent HTTP threads.
 *
 * @author HiSi DevTool Team
 */
@Component
public class DiagnosisReportStore {

    private static final Logger LOG = LoggerFactory.getLogger(DiagnosisReportStore.class);

    private static final long MAX_SIZE = 500L;
    private static final long EXPIRE_HOURS = 2L;
    private static final String CACHE_NAME = "apm.diagnose.report.store";

    private static final Set<DiagnoseReport.Status> TERMINAL = Set.of(
        DiagnoseReport.Status.DONE,
        DiagnoseReport.Status.FAILED,
        DiagnoseReport.Status.CANCELLED,
        DiagnoseReport.Status.TIMEOUT,
        DiagnoseReport.Status.LOW_CONFIDENCE
    );

    private final Cache<String, DiagnoseReport> cache;

    /**
     * Construct the store and wire Caffeine stats to Micrometer.
     *
     * @param meterRegistry the Micrometer registry for metrics export
     */
    public DiagnosisReportStore(MeterRegistry meterRegistry) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterAccess(EXPIRE_HOURS, TimeUnit.HOURS)
                .recordStats()
                .build();

        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME);
        LOG.info("DiagnosisReportStore initialised — maxSize={}, expireAfterAccess={}h",
                MAX_SIZE, EXPIRE_HOURS);
    }

    /**
     * Create a new {@link DiagnoseReport.Status#PENDING} report and store it.
     *
     * @param reportId    UUID for the new report
     * @param traceId     trace identifier under analysis
     * @param projectPath absolute project path backing the diagnose
     * @return the newly stored report
     */
    public DiagnoseReport createPending(String reportId, String traceId, String projectPath) {
        DiagnoseReport report = DiagnoseReport.pending(reportId, traceId, projectPath).build();
        cache.put(reportId, report);
        return report;
    }

    /**
     * Retrieve a report by id.
     *
     * @param reportId the report UUID
     * @return the report wrapped in an {@link Optional}, empty if absent or expired
     */
    public Optional<DiagnoseReport> findById(String reportId) {
        return Optional.ofNullable(cache.getIfPresent(reportId));
    }

    /**
     * Atomically transition a report to a new status. Validates the transition
     * against the state machine; sets {@code finishedAt} and {@code elapsedMs}
     * when entering a terminal state.
     *
     * @param reportId  the report UUID
     * @param newStatus the target status
     * @return the updated report
     * @throws NoSuchElementException if the report is not in the store
     * @throws IllegalStateException  if the transition is not permitted
     */
    public DiagnoseReport transition(String reportId, DiagnoseReport.Status newStatus) {
        return updateAtomically(reportId, current -> {
            validateTransition(current.getStatus(), newStatus);
            current.setStatus(newStatus);
            applyTerminalTimestamps(current, newStatus);
            return current;
        });
    }

    /**
     * Mark a report as {@link DiagnoseReport.Status#DONE} with results.
     * Must be called from {@link DiagnoseReport.Status#RUNNING}.
     *
     * @param reportId          the report UUID
     * @param rootCauseMarkdown LLM-rendered root cause analysis
     * @param confidence        confidence score in {@code [0.0, 1.0]}, may be null
     * @param evidence          evidence anchors (defensively copied)
     * @return the updated report
     */
    public DiagnoseReport markDone(String reportId,
                                   String rootCauseMarkdown,
                                   Double confidence,
                                   List<DiagnoseReport.EvidenceAnchor> evidence) {
        return updateAtomically(reportId, current -> {
            validateTransition(current.getStatus(), DiagnoseReport.Status.DONE);
            current.setStatus(DiagnoseReport.Status.DONE);
            current.setRootCauseMarkdown(rootCauseMarkdown);
            current.setConfidence(confidence);
            current.setEvidence(evidence == null ? List.of() : List.copyOf(evidence));
            applyTerminalTimestamps(current, DiagnoseReport.Status.DONE);
            return current;
        });
    }

    /**
     * Mark a report as {@link DiagnoseReport.Status#FAILED} with error details.
     * Must be called from {@link DiagnoseReport.Status#RUNNING}.
     *
     * @param reportId     the report UUID
     * @param errorCode    the {@link ApmErrorCode} explaining the failure
     * @param errorMessage human-readable error message
     * @return the updated report
     */
    public DiagnoseReport markFailed(String reportId, ApmErrorCode errorCode, String errorMessage) {
        return updateAtomically(reportId, current -> {
            validateTransition(current.getStatus(), DiagnoseReport.Status.FAILED);
            current.setStatus(DiagnoseReport.Status.FAILED);
            current.setErrorCode(errorCode == null ? null : errorCode.getCode());
            current.setErrorMessage(errorMessage);
            applyTerminalTimestamps(current, DiagnoseReport.Status.FAILED);
            return current;
        });
    }

    /**
     * Number of reports currently in the store.
     *
     * @return the entry count
     */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * Apply an atomic update via {@link Map#compute}.
     */
    private DiagnoseReport updateAtomically(String reportId,
                                            java.util.function.UnaryOperator<DiagnoseReport> updater) {
        DiagnoseReport result = cache.asMap().compute(reportId, (id, existing) -> {
            if (existing == null) {
                throw new NoSuchElementException("DiagnoseReport not found: " + id);
            }
            return updater.apply(existing);
        });
        return result;
    }

    /**
     * Set finishedAt / elapsedMs when transitioning to a terminal state.
     */
    private void applyTerminalTimestamps(DiagnoseReport report, DiagnoseReport.Status newStatus) {
        if (!TERMINAL.contains(newStatus)) {
            return;
        }
        Instant now = Instant.now();
        report.setFinishedAt(now);
        if (report.getStartedAt() != null) {
            report.setElapsedMs(Duration.between(report.getStartedAt(), now).toMillis());
        }
    }

    /**
     * Validate a state-machine transition. Permitted edges:
     * <pre>
     *   PENDING -> RUNNING | CANCELLED
     *   RUNNING -> DONE | FAILED | TIMEOUT | LOW_CONFIDENCE | CANCELLED
     * </pre>
     * Terminal states are sinks.
     *
     * @throws IllegalStateException if the transition is not permitted
     */
    private void validateTransition(DiagnoseReport.Status from, DiagnoseReport.Status to) {
        if (TERMINAL.contains(from)) {
            throw new IllegalStateException(
                "Cannot transition from terminal state " + from + " to " + to);
        }
        boolean valid = switch (from) {
            case PENDING -> to == DiagnoseReport.Status.RUNNING
                         || to == DiagnoseReport.Status.CANCELLED;
            case RUNNING -> to == DiagnoseReport.Status.DONE
                         || to == DiagnoseReport.Status.FAILED
                         || to == DiagnoseReport.Status.TIMEOUT
                         || to == DiagnoseReport.Status.LOW_CONFIDENCE
                         || to == DiagnoseReport.Status.CANCELLED;
            default -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Invalid transition: " + from + " -> " + to);
        }
    }
}
