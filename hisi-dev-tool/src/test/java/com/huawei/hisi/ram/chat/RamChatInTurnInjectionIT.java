package com.huawei.hisi.ram.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.chat.dto.CreateSessionRequest;
import com.huawei.hisi.ram.chat.dto.CreateSessionResponse;
import com.huawei.hisi.ram.chat.dto.InjectRequest;
import com.huawei.hisi.ram.chat.dto.SendMessageRequest;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.nodes.impl.StreamCallbacks;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for in-turn injection (M4-T12).
 *
 * <p>Drives the real Spring MVC stack via {@link MockMvc}, using a file-backed
 * SQLite database and the real {@link RamChatOrchestrator}, {@link TurnRegistry},
 * and {@link AgentEventRepository}. The upstream Claude SDK client is mocked so
 * the first streaming turn can be suspended mid-delta, letting the test issue a
 * {@code POST /inject} that must interrupt the running turn and start a new one.
 *
 * <p>Neo4j is disabled via both {@code neo4j.uri=} (empty) and
 * {@code neo4j.embedded.enabled=false}; JWT filter is bypassed via
 * {@code @AutoConfigureMockMvc(addFilters = false)}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "neo4j.uri=bolt://localhost:0",
        "neo4j.embedded.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/ram-inturn-injection-it.db",
        "spring.datasource.hikari.maximum-pool-size=1",
        "ram.chat.models.glm-5.1.scenario-max-tokens.chat=4096"
})
class RamChatInTurnInjectionIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentEventRepository eventRepository;

    @MockBean private RamClaudeJsonClient claudeClient;
    @MockBean private KgMcpClient kgMcpClient;

    @Test
    @DisplayName("mid-turn /inject interrupts first turn, persists TURN_INTERRUPTED, starts new turn")
    void injectMidStream_persistsInterruptAndStartsNewTurn() throws Exception {
        // Latches to coordinate the streaming stub with the test thread.
        CountDownLatch firstDeltaEmitted = new CountDownLatch(1);
        CountDownLatch releaseFirstTurn = new CountDownLatch(1);
        AtomicReference<String> firstTurnDelta = new AtomicReference<>("first partial ");

        // First-turn stub: emit one ASSISTANT_DELTA, then block until released.
        when(claudeClient.callJsonWithToolsAndStreaming(
                anyString(), anyString(), anyList(), anyMap(),
                any(SendOptions.class), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5, StreamCallbacks.class);
                    Consumer<Disposable> sink = inv.getArgument(6, Consumer.class);
                    // Provide a no-op disposable so the orchestrator's disposableSink runs cleanly.
                    sink.accept(new Disposable() {
                        private volatile boolean disposed = false;
                        @Override public void dispose() { disposed = true; }
                        @Override public boolean isDisposed() { return disposed; }
                    });
                    cb.onAssistantDelta(firstTurnDelta.get());
                    firstDeltaEmitted.countDown();
                    // Block: this simulates the streaming turn being suspended so /inject can race in.
                    releaseFirstTurn.await(30, TimeUnit.SECONDS);
                    cb.onRoundComplete(0, "end_turn");
                    return new RamClaudeJsonClient.JsonCallResult(Map.of(), List.of());
                });

        // 1. POST /sessions → obtain session id.
        String createBody = objectMapper.writeValueAsString(new CreateSessionRequest(
                List.of("/proj/it"), "it-project", null));
        MvcResult createRes = mockMvc.perform(post("/api/ram/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn();
        ApiResponse<CreateSessionResponse> createResp = objectMapper.readValue(
                createRes.getResponse().getContentAsString(),
                new TypeReference<ApiResponse<CreateSessionResponse>>() {});
        assertThat(createResp.getData()).isNotNull();
        long sid = Long.parseLong(createResp.getData().sessionId());

        // 2. POST /{sid}/messages on a background thread — the endpoint is blocking.
        CompletableFuture<Void> firstTurnFuture = CompletableFuture.runAsync(() -> {
            try {
                mockMvc.perform(post("/api/ram/chat/{sid}/messages", sid)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new SendMessageRequest("first question"))))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait until (a) stub emitted its delta AND (b) it was persisted by the orchestrator.
        assertThat(firstDeltaEmitted.await(30, TimeUnit.SECONDS)).isTrue();
        await().atMost(30, TimeUnit.SECONDS).until(() ->
                eventRepository.countBySessionIdAndType(sid, EventType.ASSISTANT_DELTA) >= 1);

        // Capture the aborted turnId before /inject fires (from the first USER_MSG event).
        String abortedTurnId = objectMapper.readValue(
                        eventRepository.findBySessionId(sid).stream()
                                .filter(e -> e.getType() == EventType.USER_MSG)
                                .findFirst().orElseThrow().getPayload(),
                        new TypeReference<Map<String, Object>>() {})
                .get("turnId").toString();

        // 3. Reset the stub so the SECOND (injected) turn returns immediately.
        reset(claudeClient);
        when(claudeClient.callJsonWithToolsAndStreaming(
                anyString(), anyString(), anyList(), anyMap(),
                any(SendOptions.class), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5, StreamCallbacks.class);
                    Consumer<Disposable> sink = inv.getArgument(6, Consumer.class);
                    sink.accept(new Disposable() {
                        private volatile boolean disposed = false;
                        @Override public void dispose() { disposed = true; }
                        @Override public boolean isDisposed() { return disposed; }
                    });
                    cb.onAssistantDelta("second-turn reply");
                    cb.onRoundComplete(0, "end_turn");
                    return new RamClaudeJsonClient.JsonCallResult(Map.of(), List.of());
                });

        // 4. POST /{sid}/inject → 202 accepted.
        mockMvc.perform(post("/api/ram/chat/{sid}/inject", sid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InjectRequest("new message"))))
                .andExpect(status().isAccepted());

        // Release the first turn (its Disposable was proxied — dispose() from turnRegistry.interrupt
        // sets the proxy's disposed flag; since we blocked the stub, we now unblock it here to let
        // the orchestrator finish its CHECKPOINT/complete path).
        releaseFirstTurn.countDown();
        firstTurnFuture.get(30, TimeUnit.SECONDS);

        // 5. Wait for the SECOND turn to fully persist (USER_MSG "new message" + CHECKPOINT).
        await().atMost(30, TimeUnit.SECONDS).until(() ->
                eventRepository.countBySessionIdAndType(sid, EventType.CHECKPOINT) >= 2);

        // ---- Assertions on persisted event log ----
        List<AgentEvent> events = eventRepository.findBySessionId(sid);
        assertThat(events).isNotEmpty();

        // Strictly monotonic seq.
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getSeq())
                    .as("seq must be strictly monotonic at index %d", i)
                    .isGreaterThan(events.get(i - 1).getSeq());
        }

        // Exactly ONE TURN_INTERRUPTED, with the aborted turnId + interrupt-<turnId> idem key.
        List<AgentEvent> interrupts = events.stream()
                .filter(e -> e.getType() == EventType.TURN_INTERRUPTED)
                .toList();
        assertThat(interrupts).hasSize(1);
        AgentEvent interrupt = interrupts.get(0);
        assertThat(interrupt.isInterrupted()).isTrue();
        assertThat(interrupt.getTurnId()).isEqualTo(abortedTurnId);
        assertThat(interrupt.getIdempotencyKey()).startsWith("interrupt-");
        Map<String, Object> interruptPayload = objectMapper.readValue(
                interrupt.getPayload(), new TypeReference<Map<String, Object>>() {});
        assertThat(interruptPayload)
                .containsEntry("reason", "user_interrupt")
                .containsEntry("turnId", abortedTurnId)
                .containsKey("partialText");
        assertThat(interruptPayload.get("partialText").toString()).isNotEmpty();

        // Ordered sequence: USER_MSG → ASSISTANT_DELTA → TURN_INTERRUPTED → USER_MSG("new message") with DIFFERENT turnId.
        int userMsgIdx = -1, deltaIdx = -1, interruptIdx = -1, secondUserMsgIdx = -1;
        String secondTurnId = null;
        for (int i = 0; i < events.size(); i++) {
            AgentEvent e = events.get(i);
            if (e.getType() == EventType.USER_MSG && userMsgIdx < 0) {
                userMsgIdx = i;
            } else if (e.getType() == EventType.ASSISTANT_DELTA && deltaIdx < 0 && userMsgIdx >= 0) {
                deltaIdx = i;
            } else if (e.getType() == EventType.TURN_INTERRUPTED && interruptIdx < 0 && deltaIdx >= 0) {
                interruptIdx = i;
            } else if (e.getType() == EventType.USER_MSG && interruptIdx >= 0 && secondUserMsgIdx < 0) {
                secondUserMsgIdx = i;
                Map<String, Object> payload = objectMapper.readValue(
                        e.getPayload(), new TypeReference<Map<String, Object>>() {});
                assertThat(payload).containsEntry("text", "new message");
                secondTurnId = payload.get("turnId").toString();
            }
        }
        assertThat(userMsgIdx).as("first USER_MSG index").isGreaterThanOrEqualTo(0);
        assertThat(deltaIdx).as("ASSISTANT_DELTA after USER_MSG").isGreaterThan(userMsgIdx);
        assertThat(interruptIdx).as("TURN_INTERRUPTED after ASSISTANT_DELTA").isGreaterThan(deltaIdx);
        assertThat(secondUserMsgIdx).as("second USER_MSG after TURN_INTERRUPTED").isGreaterThan(interruptIdx);
        assertThat(secondTurnId).as("second turnId").isNotNull().isNotEqualTo(abortedTurnId);
    }
}
