package com.huawei.hisi.apm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Async diagnose report stored in {@code DiagnosisReportStore} and returned by
 * {@code GET /api/apm/diagnose/{reportId}}.
 * <p>
 * Lifecycle: starts in {@link Status#PENDING}, transitions to
 * {@link Status#RUNNING}, then terminates in one of
 * {@link Status#DONE} / {@link Status#FAILED} / {@link Status#CANCELLED} /
 * {@link Status#TIMEOUT} / {@link Status#LOW_CONFIDENCE}.
 * <p><strong>Thread safety:</strong> This class is mutable and not thread-safe.
 * Concurrent access must be protected by the enclosing store or by replacing
 * instances atomically (copy-on-write).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnoseReport {

    /** UUID identifying this report. */
    private String reportId;

    /** W3C trace-id under analysis. */
    private String traceId;

    /** Absolute project path that backed the KG lookup. */
    private String projectPath;

    /** Current lifecycle state. */
    private Status status;

    /** Optional numeric error code matching {@link ApmErrorCode#getCode()}. */
    private Integer errorCode;

    /** Optional human-readable error message. */
    private String errorMessage;

    /** Optional confidence score in {@code [0.0, 1.0]}. */
    private Double confidence;

    /** Optional Markdown-rendered root-cause analysis produced by the LLM. */
    private String rootCauseMarkdown;

    /** Optional evidence anchors pointing into code / spans. */
    private List<EvidenceAnchor> evidence;

    /** When the diagnose flow started. */
    private Instant startedAt;

    /** When the diagnose flow finished, null while still running. */
    private Instant finishedAt;

    /** Elapsed time in milliseconds, null while still running. */
    private Long elapsedMs;

    /**
     * Lifecycle state of a {@link DiagnoseReport}.
     */
    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
        TIMEOUT,
        LOW_CONFIDENCE
    }

    /**
     * Anchor linking a piece of evidence to either a code location or a span.
     *
     * @param type        one of {@code exception_stack}, {@code silent_catch},
     *                    {@code slow_span}, {@code kg_method}
     * @param className   fully-qualified class name, nullable
     * @param methodName  method name, nullable
     * @param filePath    source file path, nullable
     * @param startLine   1-based line number, nullable
     * @param spanId      OTel span id, nullable
     * @param snippet     short code or log snippet for display, nullable
     */
    public record EvidenceAnchor(
        String type,
        String className,
        String methodName,
        String filePath,
        Integer startLine,
        String spanId,
        String snippet
    ) {}

    /**
     * Returns a defensive copy of the evidence list.
     * @return unmodifiable list of evidence anchors, or null if no evidence
     */
    public List<EvidenceAnchor> getEvidence() {
        return evidence == null ? null : List.copyOf(evidence);
    }

    /**
     * Factory returning a builder pre-populated with {@link Status#PENDING}
     * and {@code startedAt = Instant.now()}.
     *
     * @param reportId    UUID for the new report
     * @param traceId     trace identifier under analysis
     * @param projectPath absolute project path
     * @return a partially-populated {@link DiagnoseReportBuilder}
     */
    public static DiagnoseReportBuilder pending(String reportId, String traceId, String projectPath) {
        return DiagnoseReport.builder()
            .reportId(reportId)
            .traceId(traceId)
            .projectPath(projectPath)
            .status(Status.PENDING)
            .startedAt(Instant.now());
    }
}
