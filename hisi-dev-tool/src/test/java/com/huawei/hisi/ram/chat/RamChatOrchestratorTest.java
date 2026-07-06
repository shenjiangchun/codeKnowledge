package com.huawei.hisi.ram.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.chat.tools.ProjectOverviewTool;
import com.huawei.hisi.ram.config.ChatModelProperties;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.nodes.impl.KgToolRegistry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.nodes.impl.StreamCallbacks;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RamChatOrchestrator CHECKPOINT payload")
class RamChatOrchestratorTest {

    @Mock private AgentEventRepository eventRepository;
    @Mock private RamClaudeJsonClient claudeClient;
    @Mock private KgToolRegistry kgToolRegistry;
    @Mock private ProjectOverviewTool projectOverviewTool;
    @Mock private ChatContextBuilder contextBuilder;
    @Mock private RamChatWebSocketHandler wsHandler;
    @Mock private TurnRegistry turnRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RamChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ChatModelProperties chatProps = new ChatModelProperties();
        ChatModelProperties.ModelSpec spec = new ChatModelProperties.ModelSpec();
        spec.setScenarioMaxTokens(Map.of("chat", 4096));
        chatProps.setModels(Map.of("glm-5.1", spec));

        orchestrator = new RamChatOrchestrator(
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
        ReflectionTestUtils.setField(orchestrator, "timeoutSeconds", 10L);

        // Persist path: return whatever was passed in.
        when(eventRepository.append(any(AgentEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Context / tools stubs — minimal, do not care about content.
        when(contextBuilder.buildContext(anyLong(), anyString(), any()))
                .thenReturn(new ChatContextBuilder.ChatContext("sys", "user"));
        when(kgToolRegistry.buildToolDefinitions(any(List.class)))
                .thenReturn(List.<ToolDefinition>of());
        when(kgToolRegistry.buildToolHandlers(any(List.class)))
                .thenReturn(Map.of());
        when(projectOverviewTool.buildDefinition())
                .thenReturn(mock(ToolDefinition.class));
        when(projectOverviewTool.buildHandler(any(List.class)))
                .thenReturn(map -> null);

        // TurnRegistry stubbing: capture the ActiveTurn passed to register(...),
        // then serve it back via get(...) so isActive() returns true for the
        // current turn. Without this the CHECKPOINT / delta / tool events are
        // dropped by the late-write guards added in Phase A T1.
        AtomicReference<TurnRegistry.ActiveTurn> activeRef = new AtomicReference<>();
        doAnswer(inv -> {
            activeRef.set(inv.getArgument(1));
            return null;
        }).when(turnRegistry).register(anyLong(), any(TurnRegistry.ActiveTurn.class));
        when(turnRegistry.get(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(activeRef.get()));
    }

    @Test
    @DisplayName("CHECKPOINT payload uses streamed markdown text, not JSON schema fields")
    void checkpoint_usesStreamedMarkdown() throws Exception {
        // Stub the streaming Claude call: fire 3 assistant deltas, then return
        // a JsonCallResult whose `json` map contains an `answer` field that
        // MUST NOT leak into the CHECKPOINT payload.
        when(claudeClient.callJsonWithToolsAndStreaming(
                anyString(), anyString(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5);
                    cb.onAssistantDelta("段1");
                    cb.onAssistantDelta("段2");
                    cb.onAssistantDelta("段3");
                    cb.onRoundComplete(0, "end_turn");
                    return new RamClaudeJsonClient.JsonCallResult(
                            Map.of("answer", "IGNORED_JSON_ANSWER",
                                    "key_findings", List.of("f1", "f2")),
                            List.of()
                    );
                });

        orchestrator.runTurn(42L, "hi", "/tmp/proj");

        // Capture all events written to the repository, find the CHECKPOINT.
        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventRepository, atLeastOnce()).append(captor.capture());

        AgentEvent ckpt = captor.getAllValues().stream()
                .filter(e -> e.getType() == EventType.CHECKPOINT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("CHECKPOINT event was not emitted"));

        Map<String, Object> payload = objectMapper.readValue(
                ckpt.getPayload(), new TypeReference<Map<String, Object>>() {});

        // The streamed markdown buffer must feed finalText.
        assertThat(payload).containsEntry("finalText", "段1段2段3");

        // JSON-schema-derived fields must NOT appear.
        assertThat(payload).doesNotContainKey("answer");
        assertThat(payload).doesNotContainKey("key_findings");
        assertThat(payload).doesNotContainKey("finalJson");

        // summary is now empty (no JSON schema to pull from).
        assertThat(payload).containsEntry("summary", "");
    }

    @Test
    @DisplayName("orchestrator sources model id from ChatModelProperties.defaultModelId() (Phase B #5)")
    void orchestrator_usesConfigDrivenDefaultModelId() throws Exception {
        // Re-build chatProps with a custom default-model override.
        ChatModelProperties customProps = new ChatModelProperties();
        customProps.setDefaultModel("test-custom-model");
        ChatModelProperties.ModelSpec spec = new ChatModelProperties.ModelSpec();
        spec.setScenarioMaxTokens(Map.of("chat", 4096));
        customProps.setModels(Map.of("glm-5.1", spec));

        RamChatOrchestrator customOrchestrator = new RamChatOrchestrator(
                eventRepository,
                claudeClient,
                kgToolRegistry,
                projectOverviewTool,
                contextBuilder,
                wsHandler,
                objectMapper,
                customProps,
                turnRegistry);
        ReflectionTestUtils.setField(customOrchestrator, "timeoutSeconds", 10L);

        // Re-stub the same context/tools/turnRegistry/captured-ActiveTurn setup used in setUp().
        AtomicReference<TurnRegistry.ActiveTurn> activeRef = new AtomicReference<>();
        doAnswer(inv -> {
            activeRef.set(inv.getArgument(1));
            return null;
        }).when(turnRegistry).register(anyLong(), any(TurnRegistry.ActiveTurn.class));
        when(turnRegistry.get(anyLong())).thenAnswer(inv -> Optional.ofNullable(activeRef.get()));
        when(eventRepository.append(any(AgentEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(contextBuilder.buildContext(anyLong(), anyString(), any()))
                .thenReturn(new ChatContextBuilder.ChatContext("sys", "user"));
        when(kgToolRegistry.buildToolDefinitions(any(List.class))).thenReturn(List.<ToolDefinition>of());
        when(kgToolRegistry.buildToolHandlers(any(List.class))).thenReturn(Map.of());
        when(projectOverviewTool.buildDefinition()).thenReturn(mock(ToolDefinition.class));
        when(projectOverviewTool.buildHandler(any(List.class))).thenReturn(map -> null);
        when(claudeClient.callJsonWithToolsAndStreaming(
                anyString(), anyString(), any(), anyMap(), any(), any(StreamCallbacks.class), any()))
                .thenAnswer(inv -> {
                    StreamCallbacks cb = inv.getArgument(5);
                    cb.onRoundComplete(0, "end_turn");
                    return new RamClaudeJsonClient.JsonCallResult(Map.of(), List.of());
                });

        customOrchestrator.runTurn(99L, "hi", "/tmp/proj");

        // The ActiveTurn registered with TurnRegistry must carry the custom model id.
        assertThat(activeRef.get()).isNotNull();
        assertThat(activeRef.get().modelId()).isEqualTo("test-custom-model");
    }
}
