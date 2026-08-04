package com.huawei.hisi.apm.service.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.config.ApmLlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Tests for {@link ApmClaudeLlmClient}.
 */
class ApmClaudeLlmClientTest {

    private static final String BASE_URL = "https://stub.example/v1";
    private static final String CHAT_URL = "https://stub.example/v1/chat/completions";
    private static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        mapper = new ObjectMapper();
    }

    private ApmLlmProperties props(int maxConcurrency, int timeoutSeconds) {
        ApmLlmProperties p = new ApmLlmProperties();
        p.setBaseUrl("https://stub.example");
        p.setModel("claude-opus-4-6-cc");
        p.setApiKey("sk-test-token");
        p.setTimeoutSeconds(timeoutSeconds);
        p.setMaxConcurrency(maxConcurrency);
        p.setTemperature(0.2);
        p.setMaxTokens(1024);
        return p;
    }

    @Test
    void chat_happyPath_returnsContent_andSendsBothPrompts() {
        ApmClaudeLlmClient client = new ApmClaudeLlmClient(props(2, 40), restTemplate, mapper);

        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(req -> {
                    String body = req.getBody().toString();
                    JsonNode root = mapper.readTree(body);
                    assertThat(root.path("model").asText()).isEqualTo("claude-opus-4-6-cc");
                    assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
                    assertThat(root.path("messages").get(0).path("content").asText()).isEqualTo("SYS");
                    assertThat(root.path("messages").get(1).path("role").asText()).isEqualTo("user");
                    assertThat(root.path("messages").get(1).path("content").asText()).isEqualTo("USR");
                    assertThat(req.getHeaders().getFirst("Authorization")).isEqualTo("Bearer sk-test-token");
                })
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        String result = client.chat("SYS", "USR");

        assertThat(result).isEqualTo("OK");
        server.verify();
    }

    @Test
    void chat_serverError_throwsDiagnoseLlmException() {
        ApmClaudeLlmClient client = new ApmClaudeLlmClient(props(2, 40), restTemplate, mapper);

        server.expect(requestTo(CHAT_URL))
                .andRespond(withServerError().body("upstream boom"));

        assertThatThrownBy(() -> client.chat("s", "u"))
                .isInstanceOf(DiagnoseLlmException.class)
                .hasMessageContaining("500");
    }

    @Test
    void chat_clientError_throwsDiagnoseLlmException() {
        ApmClaudeLlmClient client = new ApmClaudeLlmClient(props(2, 40), restTemplate, mapper);

        server.expect(requestTo(CHAT_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED).body("nope"));

        assertThatThrownBy(() -> client.chat("s", "u"))
                .isInstanceOf(DiagnoseLlmException.class)
                .hasMessageContaining("401");
    }

    @Test
    void chat_blankApiKey_throwsImmediately() {
        ApmLlmProperties p = props(2, 40);
        p.setApiKey("");
        ApmClaudeLlmClient client = new ApmClaudeLlmClient(p, restTemplate, mapper);

        assertThatThrownBy(() -> client.chat("s", "u"))
                .isInstanceOf(DiagnoseLlmException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void chat_concurrency_secondCallBlocksUntilFirstReleases() throws Exception {
        // Use 2 separate clients sharing a single stub server is not possible; use one
        // client with concurrency=1 and a latch-blocked first call.
        ApmClaudeLlmClient client = new ApmClaudeLlmClient(props(1, 40), restTemplate, mapper);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger(0);

        server.expect(requestTo(CHAT_URL))
                .andRespond(req -> {
                    order.incrementAndGet();
                    firstStarted.countDown();
                    try {
                        releaseFirst.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(OK_BODY, MediaType.APPLICATION_JSON).createResponse(req);
                });
        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> client.chat("s1", "u1"));
        firstStarted.await(5, TimeUnit.SECONDS);

        CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> client.chat("s2", "u2"));

        // Second call should be blocked on the semaphore (no permit available).
        assertThatThrownBy(() -> f2.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(client.availablePermits()).isEqualTo(0);

        // Release the first call -> second proceeds.
        releaseFirst.countDown();
        assertThat(f1.get(5, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(f2.get(5, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(client.availablePermits()).isEqualTo(1);
    }

    @Test
    void chat_concurrencyTimeout_secondCallThrowsLimitExceeded() throws Exception {
        // timeoutSeconds=1 -> tryAcquire timeout = 1+5 = 6s. To force an
        // acquire timeout we instead pre-drain the semaphore by holding the
        // single permit while the second call waits with a short window.
        // Approach: use a real client with maxConcurrency=1 + timeoutSeconds=1
        // and simulate the upstream taking longer than the acquire window by
        // manually acquiring the semaphore via a long-running first call.
        ApmLlmProperties p = props(1, 1); // acquire timeout = 6s
        // To make this test fast, swap in a shorter wait by using a custom subclass.
        // Simpler approach: just verify that with permits=0 and an immediate
        // tryAcquire failing, the exception type+message is correct. We
        // exercise it via a blocking first call that we never release.
        ApmClaudeLlmClient client = new ApmClaudeLlmClient(p, restTemplate, mapper) {
            // override available-permit-checked flow: just drain permit
        };

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        server.expect(requestTo(CHAT_URL))
                .andRespond(req -> {
                    firstStarted.countDown();
                    try {
                        releaseFirst.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(OK_BODY, MediaType.APPLICATION_JSON).createResponse(req);
                });

        CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> client.chat("s1", "u1"));
        firstStarted.await(5, TimeUnit.SECONDS);

        // tryAcquire(6s) will block. To verify the limit-exceeded path
        // deterministically, run the second call on a thread and wait
        // beyond the 6s window for the DiagnoseLlmException.
        CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> client.chat("s2", "u2"));

        assertThatThrownBy(() -> f2.get(15, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DiagnoseLlmException.class)
                .hasMessageContaining("LLM concurrency limit exceeded");

        releaseFirst.countDown();
        f1.get(15, TimeUnit.SECONDS);
    }
}
