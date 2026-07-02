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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
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
    @SpyBean private RamChatWebSocketHandler wsHandler;

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
        // Wrap the remainder of the test in try/finally so a failure between the delta-wait and
        // the /inject POST cannot leave the stub thread blocked on releaseFirstTurn.await, which
        // would orphan firstTurnFuture for 30s and mask the real assertion error.
        // abortedTurnId is captured inside the try but referenced by later assertions, so declare
        // it outside the block.
        String abortedTurnId;
        try {
            assertThat(firstDeltaEmitted.await(30, TimeUnit.SECONDS)).isTrue();
            await().atMost(30, TimeUnit.SECONDS).until(() ->
                    eventRepository.countBySessionIdAndType(sid, EventType.ASSISTANT_DELTA) >= 1);

            // Capture the aborted turnId before /inject fires (from the first USER_MSG event).
            abortedTurnId = objectMapper.readValue(
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

            // 5. Wait for the SECOND turn to fully persist (USER_MSG "new message" + CHECKPOINT).
            //    The finally block below unblocks the first-turn stub and joins the background
            //    thread; extra countdowns past zero are safe on CountDownLatch.
            //    NOTE: With the T1 isActive-guard, the ABORTED turn no longer emits its own
            //    CHECKPOINT (late writes are dropped), so we can't wait for CHECKPOINT >= 2.
            //    Wait instead for the second USER_MSG plus at least one CHECKPOINT (from the
            //    second turn's completion).
            await().atMost(30, TimeUnit.SECONDS).until(() ->
                    eventRepository.countBySessionIdAndType(sid, EventType.USER_MSG) >= 2
                            && eventRepository.countBySessionIdAndType(sid, EventType.CHECKPOINT) >= 1);
        } finally {
            // Guarantee the stub thread is released and the background future is not orphaned,
            // regardless of whether any assertion above threw. Both operations are idempotent:
            // extra countDown() calls on a zeroed latch are no-ops, and cancel(true) on an
            // already-completed future is also a no-op.
            releaseFirstTurn.countDown();
            firstTurnFuture.cancel(true);
        }

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
        assertThat(interrupt.getIdempotencyKey()).isEqualTo("interrupt-" + abortedTurnId);
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

        // ---- Late-write guard: no CHECKPOINT/ASSISTANT_DELTA/TOOL_USE events after
        //      the interrupt may carry the aborted turnId. The T1 isActive-guard drops
        //      such writes on the Reactor thread; verify they never landed in the log.
        for (int i = interruptIdx + 1; i < events.size(); i++) {
            AgentEvent e = events.get(i);
            EventType t = e.getType();
            if (t != EventType.CHECKPOINT
                    && t != EventType.ASSISTANT_DELTA
                    && t != EventType.TOOL_USE) {
                continue;
            }
            Map<String, Object> payload = objectMapper.readValue(
                    e.getPayload(), new TypeReference<Map<String, Object>>() {});
            Object tid = payload.get("turnId");
            assertThat(tid)
                    .as("no late %s event may carry aborted turnId (event index %d)", t, i)
                    .isNotEqualTo(abortedTurnId);
        }

        // ---- WS push assertion: the turn_interrupted event pushed to the client MUST
        //      carry reason=user_interrupt so the frontend can render partial + status.
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> wsCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(wsHandler, atLeastOnce()).pushEvent(anyLong(), wsCaptor.capture());
        List<Map<String, Object>> interruptWsPushes = wsCaptor.getAllValues().stream()
                .filter(m -> "turn_interrupted".equals(m.get("type")))
                .toList();
        assertThat(interruptWsPushes)
                .as("exactly one turn_interrupted WS push")
                .hasSize(1);
        assertThat(interruptWsPushes.get(0))
                .containsEntry("reason", "user_interrupt")
                .containsEntry("turnId", abortedTurnId)
                .containsKey("partialText");
    }
}
