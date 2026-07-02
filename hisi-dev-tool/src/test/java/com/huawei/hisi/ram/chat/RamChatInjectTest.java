package com.huawei.hisi.ram.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.chat.dto.TurnResult;
import com.huawei.hisi.ram.chat.tools.ProjectOverviewTool;
import com.huawei.hisi.ram.config.ChatModelProperties;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.nodes.impl.KgToolRegistry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RamChatOrchestrator.injectAndContinue")
class RamChatInjectTest {

    @Mock private AgentEventRepository eventRepository;
    @Mock private RamClaudeJsonClient claudeClient;
    @Mock private KgToolRegistry kgToolRegistry;
    @Mock private ProjectOverviewTool projectOverviewTool;
    @Mock private ChatContextBuilder contextBuilder;
    @Mock private RamChatWebSocketHandler wsHandler;
    @Mock private TurnRegistry turnRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RamChatOrchestrator orchestrator;

    /**
     * Synchronous ExecutorService — runs submitted tasks on the caller thread so
     * tests can assert side effects of async work without Awaitility.
     */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown = false;

            @Override public void shutdown() { shutdown = true; }
            @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    @BeforeEach
    void setUp() {
        ChatModelProperties chatProps = new ChatModelProperties();
        ChatModelProperties.ModelSpec spec = new ChatModelProperties.ModelSpec();
        spec.setScenarioMaxTokens(Map.of("chat", 4096));
        chatProps.setModels(Map.of("glm-5.1", spec));

        RamChatOrchestrator real = new RamChatOrchestrator(
                eventRepository,
                claudeClient,
                kgToolRegistry,
                projectOverviewTool,
                contextBuilder,
                wsHandler,
                objectMapper,
                chatProps,
                turnRegistry
        );
        ReflectionTestUtils.setField(real, "asyncExecutor", directExecutor());
        orchestrator = spy(real);
        // Prevent the real runTurn (which drives the whole LLM path) from firing.
        doReturn(new TurnResult("stub-turn", "DONE", "", List.of(), null))
                .when(orchestrator).runTurn(anyLong(), anyString(), anyList());
    }

    @Test
    @DisplayName("with active turn: appends TURN_INTERRUPTED, pushes ws event, starts new turn")
    void injectAndContinue_withActiveTurn_emitsTurnInterruptedThenStartsNewTurn() throws Exception {
        when(turnRegistry.interrupt(42L))
                .thenReturn(Optional.of(new TurnRegistry.InterruptResult("old-turn-id", "partial text so far")));

        AgentEvent persisted = AgentEvent.builder()
                .id(101L)
                .sessionId(42L)
                .seq(9L)
                .type(EventType.TURN_INTERRUPTED)
                .payload("{}")
                .createdAt(1_700_000_000L)
                .build();
        when(eventRepository.append(any(AgentEvent.class))).thenReturn(persisted);

        orchestrator.injectAndContinue(42L, "new message", List.of("/proj"));

        // (a) TURN_INTERRUPTED event persisted with correct payload + idempotency key.
        ArgumentCaptor<AgentEvent> evCaptor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventRepository, times(1)).append(evCaptor.capture());
        AgentEvent appended = evCaptor.getValue();
        assertThat(appended.getType()).isEqualTo(EventType.TURN_INTERRUPTED);
        assertThat(appended.getIdempotencyKey()).isEqualTo("interrupt-old-turn-id");
        Map<String, Object> payload = objectMapper.readValue(
                appended.getPayload(), new TypeReference<Map<String, Object>>() {});
        assertThat(payload)
                .containsEntry("turnId", "old-turn-id")
                .containsEntry("partialText", "partial text so far")
                .containsEntry("reason", "user_interrupt");

        // (b) WebSocket turn_interrupted event pushed with expected fields.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> wsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(wsHandler, times(1)).pushEvent(eq(42L), wsCaptor.capture());
        Map<String, Object> ws = wsCaptor.getValue();
        assertThat(ws).containsEntry("type", "turn_interrupted");
        assertThat(ws).containsEntry("turnId", "old-turn-id");
        assertThat(ws).containsEntry("partialText", "partial text so far");
        assertThat(ws).containsEntry("sessionId", 42L);
        assertThat(ws).containsEntry("eventId", 101L);
        assertThat(ws).containsEntry("seq", 9L);

        // (c) A new turn is started with the injected user text.
        verify(orchestrator, times(1)).runTurn(eq(42L), eq("new message"), eq(List.of("/proj")));
    }

    @Test
    @DisplayName("no active turn: skips TURN_INTERRUPTED event, still starts new turn")
    void injectAndContinue_withNoActiveTurn_skipsInterruptEventAndStartsNewTurn() {
        when(turnRegistry.interrupt(anyLong())).thenReturn(Optional.empty());

        orchestrator.injectAndContinue(42L, "new message", List.of("/proj"));

        // (a) No TURN_INTERRUPTED event appended.
        verify(eventRepository, never()).append(any(AgentEvent.class));
        verify(wsHandler, never()).pushEvent(anyLong(), any(Map.class));

        // (b) runTurn is still invoked with the injected user text.
        verify(orchestrator, times(1)).runTurn(eq(42L), eq("new message"), eq(List.of("/proj")));
    }
}
