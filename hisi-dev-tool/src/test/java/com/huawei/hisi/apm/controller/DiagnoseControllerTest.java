package com.huawei.hisi.apm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.cache.DiagnosisReportStore;
import com.huawei.hisi.apm.model.DiagnoseReport;
import com.huawei.hisi.apm.model.DiagnoseRequest;
import com.huawei.hisi.apm.service.locator.FailureLocatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link DiagnoseController}.
 */
@WebMvcTest(DiagnoseController.class)
class DiagnoseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FailureLocatorService failureLocatorService;

    @MockBean
    private DiagnosisReportStore reportStore;

    private static final String VALID_BODY = """
        {"traceId":"abc123","projectPath":"/proj/x"}
        """;

    @Test
    @DisplayName("POST valid request returns 202 with reportId")
    void postValid_returns202() throws Exception {
        when(failureLocatorService.startDiagnose(any(DiagnoseRequest.class)))
            .thenReturn("rid-1");

        mockMvc.perform(post("/api/apm/diagnose")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.reportId").value("rid-1"));
    }

    @Test
    @DisplayName("POST blank traceId returns 400")
    void postBlankTraceId_returns400() throws Exception {
        String body = """
            {"traceId":"","projectPath":"/proj/x"}
            """;
        mockMvc.perform(post("/api/apm/diagnose")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET report by id returns 200 with full payload")
    void getReport_found_returns200() throws Exception {
        DiagnoseReport report = DiagnoseReport.builder()
            .reportId("rid-2")
            .traceId("t")
            .projectPath("/p")
            .status(DiagnoseReport.Status.DONE)
            .rootCauseMarkdown("# RC")
            .confidence(0.9)
            .startedAt(Instant.parse("2026-05-21T10:00:00Z"))
            .finishedAt(Instant.parse("2026-05-21T10:00:05Z"))
            .elapsedMs(5000L)
            .build();
        when(reportStore.findById(eq("rid-2"))).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/apm/diagnose/rid-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reportId").value("rid-2"))
            .andExpect(jsonPath("$.rootCauseMarkdown").value("# RC"))
            .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("GET report not found returns 404")
    void getReport_missing_returns404() throws Exception {
        when(reportStore.findById(eq("nope"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/apm/diagnose/nope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Report not found: nope"));
    }

    @Test
    @DisplayName("GET status returns trimmed payload without markdown/evidence")
    void getStatus_found_returnsTrimmed() throws Exception {
        DiagnoseReport report = DiagnoseReport.builder()
            .reportId("rid-3")
            .traceId("t")
            .projectPath("/p")
            .status(DiagnoseReport.Status.RUNNING)
            .startedAt(Instant.parse("2026-05-21T10:00:00Z"))
            .rootCauseMarkdown("SHOULD-NOT-LEAK")
            .build();
        when(reportStore.findById(eq("rid-3"))).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/apm/diagnose/rid-3/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reportId").value("rid-3"))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.finishedAt").doesNotExist())
            .andExpect(jsonPath("$.rootCauseMarkdown").doesNotExist())
            .andExpect(jsonPath("$.evidence").doesNotExist());
    }

    @Test
    @DisplayName("GET status not found returns 404")
    void getStatus_missing_returns404() throws Exception {
        when(reportStore.findById(eq("nope"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/apm/diagnose/nope/status"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE pending report returns 200 with CANCELLED status")
    void deletePending_returns200Cancelled() throws Exception {
        DiagnoseReport existing = DiagnoseReport.builder()
            .reportId("rid-4")
            .traceId("t")
            .projectPath("/p")
            .status(DiagnoseReport.Status.PENDING)
            .startedAt(Instant.now())
            .build();
        DiagnoseReport cancelled = DiagnoseReport.builder()
            .reportId("rid-4")
            .traceId("t")
            .projectPath("/p")
            .status(DiagnoseReport.Status.CANCELLED)
            .startedAt(existing.getStartedAt())
            .finishedAt(Instant.now())
            .elapsedMs(1L)
            .build();
        when(reportStore.findById(eq("rid-4"))).thenReturn(Optional.of(existing));
        when(reportStore.transition(eq("rid-4"), eq(DiagnoseReport.Status.CANCELLED)))
            .thenReturn(cancelled);

        mockMvc.perform(delete("/api/apm/diagnose/rid-4"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("DELETE terminal report returns 409 Conflict")
    void deleteTerminal_returns409() throws Exception {
        DiagnoseReport done = DiagnoseReport.builder()
            .reportId("rid-5")
            .traceId("t")
            .projectPath("/p")
            .status(DiagnoseReport.Status.DONE)
            .build();
        when(reportStore.findById(eq("rid-5"))).thenReturn(Optional.of(done));
        when(reportStore.transition(eq("rid-5"), eq(DiagnoseReport.Status.CANCELLED)))
            .thenThrow(new IllegalStateException("Cannot transition from terminal state DONE to CANCELLED"));

        mockMvc.perform(delete("/api/apm/diagnose/rid-5"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Report is already in terminal state"));
    }

    @Test
    @DisplayName("DELETE missing report returns 404")
    void deleteMissing_returns404() throws Exception {
        when(reportStore.findById(eq("nope"))).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/apm/diagnose/nope"))
            .andExpect(status().isNotFound());
    }
}
