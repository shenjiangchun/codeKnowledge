package com.huawei.hisi.apm.e2e;

import com.huawei.hisi.apm.controller.DiagnoseController;
import com.huawei.hisi.apm.fixture.FixtureReplayer;
import com.huawei.hisi.apm.model.DiagnoseReport;
import com.huawei.hisi.apm.model.DiagnoseRequest;
import com.huawei.hisi.apm.service.SpanIngestionService;
import com.huawei.hisi.apm.service.locator.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Failure-Locator pipeline test (Task 13).
 *
 * <p>Boots the full Spring context with a deterministic stub {@link LlmClient}
 * so the test does not depend on any external LLM provider. For each of the
 * three P0 fixtures the test:
 * <ol>
 *   <li>registers the fixture's service name -> session mapping via
 *       {@link SpanIngestionService};</li>
 *   <li>POSTs the OTLP/JSON payload at {@code /v1/traces} so the ingestion
 *       pipeline flattens spans into the {@code ExceptionSpanIndex};</li>
 *   <li>POSTs a {@link DiagnoseRequest} to {@code /api/apm/diagnose} and
 *       polls {@code /status} until a terminal state is reached;</li>
 *   <li>asserts the report lands in {@link DiagnoseReport.Status#DONE} with
 *       the canned high-confidence value the stub returns.</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                com.huawei.hisi.DevToolApplication.class,
                FailureLocatorE2ETest.StubLlmConfig.class
        })
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Use a file-based SQLite under the build target so the schema persists
        // across the multiple datasource connections opened during context start.
        "spring.datasource.url=jdbc:sqlite:target/apm-e2e.db",
        // Make the run deterministic and fast.
        "hisi.apm.diagnose.enabled=true",
        "hisi.apm.diagnose.llmEnabled=true",
        "hisi.apm.diagnose.kgEnabled=false",
        "hisi.apm.diagnose.timeoutSeconds=30",
        "hisi.apm.diagnose.llmTimeoutSeconds=20",
        "hisi.apm.diagnose.confidenceLowThreshold=0.5"
})
class FailureLocatorE2ETest {

    /** Deterministic stub LLM — bypasses any real provider. */
    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        @Primary
        LlmClient stubLlmClient() {
            return (system, user) -> "{\n"
                    + "  \"rootCauseMarkdown\": \"## Root Cause\\nStub diagnosis for E2E test.\",\n"
                    + "  \"confidence\": 0.88,\n"
                    + "  \"summary\": \"stub root cause\"\n"
                    + "}";
        }
    }

    @Autowired private TestRestTemplate http;
    @Autowired private SpanIngestionService spanIngestionService;

    @ParameterizedTest(name = "fixture {0} -> diagnose DONE (traceId={1}, service={2})")
    @CsvSource({
            "npe,      00000000000000000000000000000001, order-service",
            "sql-fail, 00000000000000000000000000000002, order-service",
            "http-5xx, 00000000000000000000000000000003, checkout-service"
    })
    @DisplayName("P0 fixture diagnose pipeline reaches DONE with stub LLM")
    void fixture_endToEnd_done(String fixtureName, String traceId, String serviceName) {
        // 1) Wire up the in-memory service -> session mapping so the ingestion
        //    pipeline does not drop the spans.
        String sessionId = "e2e-" + fixtureName;
        String projectPath = "/abs/" + fixtureName;
        spanIngestionService.registerSession(serviceName, sessionId, projectPath);

        // 2) Replay the fixture against /v1/traces.
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        String otlpJson = FixtureReplayer.loadFixture(fixtureName);
        ResponseEntity<Void> ingest = http.postForEntity(
                "/v1/traces", new HttpEntity<>(otlpJson, jsonHeaders), Void.class);
        assertThat(ingest.getStatusCode().is2xxSuccessful())
                .as("/v1/traces should accept the OTLP payload")
                .isTrue();

        // 3) Kick off the async diagnose.
        DiagnoseRequest req = new DiagnoseRequest(
                traceId, projectPath, sessionId, /* forceRefresh */ true, "e2e test");
        ResponseEntity<DiagnoseController.StartDiagnoseResponse> startResp = http.postForEntity(
                "/api/apm/diagnose", new HttpEntity<>(req, jsonHeaders),
                DiagnoseController.StartDiagnoseResponse.class);
        assertThat(startResp.getStatusCode().is2xxSuccessful())
                .as("startDiagnose returns 202").isTrue();
        String reportId = startResp.getBody().reportId();
        assertThat(reportId).as("reportId not blank").isNotBlank();

        // 4) Poll the status endpoint until terminal.
        DiagnoseController.DiagnoseStatusResponse status = pollUntilTerminal(
                reportId, Duration.ofSeconds(20));

        // 5) Assertions: must finish DONE with stub confidence.
        assertThat(status.status())
                .as("fixture %s reaches DONE", fixtureName)
                .isEqualTo(DiagnoseReport.Status.DONE);
        assertThat(status.confidence())
                .as("fixture %s carries stub confidence", fixtureName)
                .isNotNull()
                .isEqualTo(0.88);

        // 6) Fetch full report and assert payload shape.
        ResponseEntity<DiagnoseReport> reportResp = http.getForEntity(
                "/api/apm/diagnose/" + reportId, DiagnoseReport.class);
        assertThat(reportResp.getStatusCode().is2xxSuccessful()).isTrue();
        DiagnoseReport report = reportResp.getBody();
        assertThat(report).isNotNull();
        assertThat(report.getRootCauseMarkdown())
                .as("rootCauseMarkdown populated").contains("Stub diagnosis");
        assertThat(report.getTraceId()).isEqualTo(traceId);
        assertThat(report.getProjectPath()).isEqualTo(projectPath);
    }

    private DiagnoseController.DiagnoseStatusResponse pollUntilTerminal(
            String reportId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        DiagnoseController.DiagnoseStatusResponse last = null;
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<DiagnoseController.DiagnoseStatusResponse> resp = http.getForEntity(
                    "/api/apm/diagnose/" + reportId + "/status",
                    DiagnoseController.DiagnoseStatusResponse.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                last = resp.getBody();
                if (isTerminal(last.status())) {
                    return last;
                }
            }
            sleepQuietly(150);
        }
        throw new AssertionError("Diagnose report " + reportId
                + " did not reach terminal state within " + timeout
                + " (lastStatus=" + (last == null ? "null" : last.status()) + ")");
    }

    private static boolean isTerminal(DiagnoseReport.Status s) {
        return s == DiagnoseReport.Status.DONE
                || s == DiagnoseReport.Status.FAILED
                || s == DiagnoseReport.Status.CANCELLED
                || s == DiagnoseReport.Status.TIMEOUT
                || s == DiagnoseReport.Status.LOW_CONFIDENCE;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
