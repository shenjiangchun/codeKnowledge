package com.huawei.hisi.apm.controller;

import com.huawei.hisi.apm.cache.DiagnosisReportStore;
import com.huawei.hisi.apm.model.DiagnoseReport;
import com.huawei.hisi.apm.model.DiagnoseRequest;
import com.huawei.hisi.apm.service.locator.FailureLocatorService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * REST controller exposing the asynchronous APM failure-locator pipeline.
 * <p>
 * All endpoints are mounted under {@code /api/apm/diagnose}. The controller
 * is gated on {@code hisi.apm.diagnose.enabled=true} so the feature can be
 * globally disabled. Business logic lives in {@link FailureLocatorService}
 * and {@link DiagnosisReportStore}; this controller only adapts HTTP.
 *
 * @author HiSi DevTool Team
 */
@RestController
@RequestMapping("/api/apm/diagnose")
@ConditionalOnProperty(prefix = "hisi.apm.diagnose", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DiagnoseController {

    private static final Logger LOG = LoggerFactory.getLogger(DiagnoseController.class);

    private final FailureLocatorService failureLocatorService;
    private final DiagnosisReportStore reportStore;

    /**
     * Constructor injection of pipeline service and report store.
     *
     * @param failureLocatorService async pipeline entry point
     * @param reportStore           in-memory report cache
     */
    public DiagnoseController(FailureLocatorService failureLocatorService,
                              DiagnosisReportStore reportStore) {
        this.failureLocatorService = Objects.requireNonNull(failureLocatorService, "failureLocatorService");
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore");
    }

    /** Submit a diagnose request; returns 202 with the reportId for polling. */
    @PostMapping
    public ResponseEntity<StartDiagnoseResponse> startDiagnose(@Valid @RequestBody DiagnoseRequest request) {
        String reportId = failureLocatorService.startDiagnose(request);
        return ResponseEntity.accepted().body(new StartDiagnoseResponse(reportId));
    }

    /** Fetch the full diagnose report by id. */
    @GetMapping("/{reportId}")
    public ResponseEntity<DiagnoseReport> getReport(@PathVariable String reportId) {
        DiagnoseReport report = reportStore.findById(reportId)
            .orElseThrow(() -> new NoSuchElementException("Report not found: " + reportId));
        return ResponseEntity.ok(report);
    }

    /** Lightweight status-only view used by the 1.5s frontend polling loop. */
    @GetMapping("/{reportId}/status")
    public ResponseEntity<DiagnoseStatusResponse> getStatus(@PathVariable String reportId) {
        DiagnoseReport r = reportStore.findById(reportId)
            .orElseThrow(() -> new NoSuchElementException("Report not found: " + reportId));
        return ResponseEntity.ok(new DiagnoseStatusResponse(
            r.getReportId(),
            r.getStatus(),
            r.getStartedAt(),
            r.getFinishedAt(),
            r.getElapsedMs(),
            r.getConfidence(),
            r.getErrorCode(),
            r.getErrorMessage()
        ));
    }

    /** Cancel an in-flight diagnose; 409 if the report is already terminal. */
    @DeleteMapping("/{reportId}")
    public ResponseEntity<DiagnoseReport> cancel(@PathVariable String reportId) {
        if (reportStore.findById(reportId).isEmpty()) {
            throw new NoSuchElementException("Report not found: " + reportId);
        }
        DiagnoseReport updated = reportStore.transition(reportId, DiagnoseReport.Status.CANCELLED);
        return ResponseEntity.ok(updated);
    }

    /** Map missing reports to 404. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        LOG.debug("Diagnose 404: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    /** Map illegal state (already terminal) to 409 Conflict. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex) {
        LOG.debug("Diagnose 409: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("Report is already in terminal state"));
    }

    /** Map bean-validation failures to 400 with the first violation message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(e -> e.getDefaultMessage())
            .orElse("Validation failed");
        return ResponseEntity.badRequest().body(new ErrorResponse(msg));
    }

    /** Response envelope for {@code POST /api/apm/diagnose}. */
    public static record StartDiagnoseResponse(String reportId) {}

    /** Lightweight status payload for polling. */
    public static record DiagnoseStatusResponse(
        String reportId,
        DiagnoseReport.Status status,
        Instant startedAt,
        Instant finishedAt,
        Long elapsedMs,
        Double confidence,
        Integer errorCode,
        String errorMessage
    ) {}

    /** Generic error envelope for non-2xx responses. */
    public static record ErrorResponse(String error) {}
}
