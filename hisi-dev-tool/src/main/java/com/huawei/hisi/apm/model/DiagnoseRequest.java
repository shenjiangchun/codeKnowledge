package com.huawei.hisi.apm.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound POST body for {@code POST /api/apm/diagnose}.
 * <p>
 * Carries the trace identifier to be analysed along with the absolute project
 * path required for knowledge-graph lookup. Optional fields allow the caller
 * to bind the request to an existing session, force a cache refresh, or attach
 * a short user note.
 *
 * @param traceId      W3C trace-id (hex string) — required
 * @param projectPath  absolute project path for KG lookup — required
 * @param sessionId    optional APM session identifier
 * @param forceRefresh if {@code true} bypass any cached report (null treated as false)
 * @param userNote     optional free-form note, max 500 chars
 */
public record DiagnoseRequest(

    @NotBlank(message = "traceId must not be blank")
    String traceId,

    @NotBlank(message = "projectPath must not be blank")
    String projectPath,

    String sessionId,

    Boolean forceRefresh,

    @Size(max = 500, message = "userNote must be 500 characters or fewer")
    String userNote
) {

    /**
     * Compact constructor normalizing a null {@code forceRefresh} to {@code false}
     * so downstream code can dereference it without null checks.
     */
    public DiagnoseRequest {
        if (forceRefresh == null) {
            forceRefresh = Boolean.FALSE;
        }
    }
}
