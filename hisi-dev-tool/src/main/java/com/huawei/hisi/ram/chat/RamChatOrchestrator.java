package com.huawei.hisi.ram.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.chat.dto.TurnResult;
import com.huawei.hisi.ram.chat.tools.ProjectOverviewTool;
import com.huawei.hisi.ram.config.ChatModelProperties;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.nodes.impl.KgToolRegistry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.nodes.impl.StreamCallbacks;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class RamChatOrchestrator {

    private final AgentEventRepository eventRepository;
    private final RamClaudeJsonClient claudeClient;
    private final KgToolRegistry kgToolRegistry;
    private final ProjectOverviewTool projectOverviewTool;
    private final ChatContextBuilder contextBuilder;
    private final RamChatWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;
    private final ChatModelProperties chatProps;
    private final TurnRegistry turnRegistry;

    private static final String DEFAULT_MODEL_ID = "glm-5.1";
    private static final String CHAT_SCENARIO = "chat";

    @Value("${ram.chat.timeout-seconds:300}")
    private long timeoutSeconds;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ram-chat-turn");
        t.setDaemon(true);
        return t;
    });

    public TurnResult runTurn(long sessionId, String userText, String projectPath) {
        return runTurn(sessionId, userText, List.of(projectPath));
    }

    /**
     * Send a new user message DURING an active streaming turn. If a turn is
     * currently streaming, interrupt it atomically, persist a
     * {@code TURN_INTERRUPTED} event carrying the partial text, push a
     * corresponding WebSocket event, then start a new turn with {@code userContent}.
     * If no turn is active, this is equivalent to submitting {@code runTurn}
     * asynchronously.
     */
    public void injectAndContinue(long sessionId, String userContent, List<String> projectPaths) {
        var interrupted = turnRegistry.interrupt(sessionId);
        interrupted.ifPresent(r -> {
            try {
                String payload = objectMapper.writeValueAsString(Map.of(
                        "turnId", r.turnId(),
                        "partialText", r.partialText(),
                        "reason", "user_interrupt"));
                AgentEvent ev = eventRepository.append(AgentEvent.turnInterrupted(
                        sessionId, 0L, payload, "interrupt-" + r.turnId()));
                Map<String, Object> wsPayload = new LinkedHashMap<>();
                wsPayload.put("type", "turn_interrupted");
                wsPayload.put("turnId", r.turnId());
                wsPayload.put("partialText", r.partialText());
                wsPayload.put("sessionId", sessionId);
                wsPayload.put("eventId", ev != null ? ev.getId() : null);
                wsPayload.put("seq", ev != null ? ev.getSeq() : null);
                wsPayload.put("createdAt", ev != null ? ev.getCreatedAt() : System.currentTimeMillis() / 1000L);
                wsHandler.pushEvent(sessionId, wsPayload);
            } catch (JsonProcessingException e) {
                log.error("[RamChatOrchestrator.injectAndContinue] failed to serialize turn_interrupted payload sessionId={} turnId={}: {}",
                        sessionId, r.turnId(), e.getMessage());
            }
        });
        asyncExecutor.submit(() -> runTurn(sessionId, userContent, projectPaths));
    }

    public TurnResult runTurn(long sessionId, String userText, List<String> projectPaths) {
        String turnId = UUID.randomUUID().toString();
        log.info("[RamChatOrchestrator] start turnId={} sessionId={} userText.len={}",
                turnId, sessionId, userText.length());

        AgentEvent userEv = appendEvent(sessionId, EventType.USER_MSG, Map.of(
                "turnId", turnId,
                "text", userText
        ), "user-msg-" + turnId);

        wsHandler.pushEvent(sessionId, wsEvent(userEv, sessionId, Map.of(
                "type", "user_msg",
                "turnId", turnId,
                "text", userText
        )));

        try {
            ChatContextBuilder.ChatContext ctx = contextBuilder.buildContext(sessionId, userText, projectPaths);

            List<ToolDefinition> tools = new ArrayList<>(kgToolRegistry.buildToolDefinitions(projectPaths));
            tools.add(projectOverviewTool.buildDefinition());

            Map<String, Function<Map<String, Object>, Object>> handlers = new LinkedHashMap<>(
                    kgToolRegistry.buildToolHandlers(projectPaths));
            handlers.put("generate_project_overview", projectOverviewTool.buildHandler(projectPaths));

            StringBuilder partialTextBuf = new StringBuilder();
            StreamCallbacks callbacks = new StreamCallbacks() {
                @Override
                public void onAssistantDelta(String deltaText) {
                    synchronized (partialTextBuf) {
                        partialTextBuf.append(deltaText);
                    }
                    AgentEvent deltaEv = appendEvent(sessionId, EventType.ASSISTANT_DELTA, Map.of(
                            "turnId", turnId,
                            "delta", deltaText
                    ), "delta-" + turnId + "-" + System.nanoTime());
                    wsHandler.pushEvent(sessionId, wsEvent(deltaEv, sessionId, Map.of(
                            "type", "assistant_delta",
                            "turnId", turnId,
                            "delta", deltaText
                    )));
                }

                @Override
                public void onToolUseStart(String toolName, Map<String, Object> input) {
                    AgentEvent toolEv = appendEvent(sessionId, EventType.TOOL_USE, Map.of(
                            "turnId", turnId,
                            "toolName", toolName,
                            "input", input
                    ), "tool-use-" + turnId + "-" + System.nanoTime());
                    wsHandler.pushEvent(sessionId, wsEvent(toolEv, sessionId, Map.of(
                            "type", "tool_use_start",
                            "turnId", turnId,
                            "toolName", toolName,
                            "input", input
                    )));
                }

                @Override
                public void onToolResult(String toolName, String resultContent) {
                    AgentEvent resultEv = appendEvent(sessionId, EventType.TOOL_RESULT, Map.of(
                            "turnId", turnId,
                            "toolName", toolName,
                            "result", resultContent
                    ), "tool-result-" + turnId + "-" + System.nanoTime());
                    wsHandler.pushEvent(sessionId, wsEvent(resultEv, sessionId, Map.of(
                            "type", "tool_result",
                            "turnId", turnId,
                            "toolName", toolName,
                            "result", resultContent
                    )));
                }

                @Override
                public void onRoundComplete(int round, String stopReason) {
                    log.debug("[RamChatOrchestrator] turnId={} round={} stopReason={}",
                            turnId, round, stopReason);
                }
            };

            // Pre-register the active turn BEFORE scheduling the streaming call so that a
            // POST /interrupt arriving between supplyAsync scheduling and Flux.subscribe
            // callback firing cannot lose the race. We install a proxy Disposable that
            // forwards dispose() to the real per-round Disposable once it becomes known;
            // if interrupt() calls dispose() first, the disposableSink callback will
            // observe proxyDisposable.isDisposed() and dispose the real one immediately.
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            Disposable proxyDisposable = new Disposable() {
                private volatile boolean disposed = false;

                @Override
                public void dispose() {
                    disposed = true;
                    Disposable real = disposableRef.get();
                    if (real != null) {
                        real.dispose();
                    }
                }

                @Override
                public boolean isDisposed() {
                    Disposable real = disposableRef.get();
                    return disposed || (real != null && real.isDisposed());
                }
            };
            turnRegistry.register(sessionId, new TurnRegistry.ActiveTurn(
                    turnId, sessionId, proxyDisposable, partialTextBuf, Instant.now(), DEFAULT_MODEL_ID));

            CompletableFuture<RamClaudeJsonClient.JsonCallResult> future = CompletableFuture.supplyAsync(() ->
                    claudeClient.callJsonWithToolsAndStreaming(
                            ctx.systemPrompt(),
                            ctx.userPrompt(),
                            tools,
                            handlers,
                            SendOptions.forScenario(chatProps, DEFAULT_MODEL_ID, CHAT_SCENARIO),
                            callbacks,
                            d -> {
                                disposableRef.set(d);
                                if (proxyDisposable.isDisposed()) {
                                    d.dispose();
                                }
                            }
            ), asyncExecutor);

            RamClaudeJsonClient.JsonCallResult result = future
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();

            String finalText = partialTextBuf.toString();
            String summary = "";

            AgentEvent ckptEv = appendEvent(sessionId, EventType.CHECKPOINT, Map.of(
                    "turnId", turnId,
                    "summary", summary,
                    "finalText", finalText,
                    "reasoningSteps", result.reasoning()
            ), "ckpt-" + turnId);

            wsHandler.pushEvent(sessionId, wsEvent(ckptEv, sessionId, Map.of(
                    "type", "checkpoint",
                    "turnId", turnId,
                    "summary", summary,
                    "finalText", finalText
            )));

            log.info("[RamChatOrchestrator] done turnId={} finalText.len={}",
                    turnId, finalText.length());

            turnRegistry.complete(sessionId, turnId);

            return new TurnResult(turnId, "DONE", finalText, result.reasoning(), null);
        } catch (Exception e) {
            log.error("[RamChatOrchestrator] failed turnId={}: {}", turnId, e.getMessage(), e);
            turnRegistry.complete(sessionId, turnId);
            AgentEvent errEv = appendEvent(sessionId, EventType.ERROR, Map.of(
                    "turnId", turnId,
                    "error", e.getMessage(),
                    "type", e.getClass().getName()
            ), "error-" + turnId);
            wsHandler.pushEvent(sessionId, wsEvent(errEv, sessionId, Map.of(
                    "type", "error",
                    "turnId", turnId,
                    "error", e.getMessage()
            )));
            return new TurnResult(turnId, "FAILED", null, List.of(), e.getMessage());
        }
    }

    private AgentEvent appendEvent(long sessionId, EventType type, Map<String, Object> payload, String idempotencyKey) {
        try {
            AgentEvent ev = AgentEvent.builder()
                    .sessionId(sessionId)
                    .type(type)
                    .payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey(idempotencyKey)
                    .circuitState("OK")
                    .validatorStatus("OK")
                    .createdAt(System.currentTimeMillis() / 1000L)
                    .build();
            return eventRepository.append(ev);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize event payload: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("appendEvent failed sessionId={} type={}: {}", sessionId, type, e.getMessage());
        }
        return null;
    }

    /**
     * Build a WebSocket event map enriched with server-side fields
     * (eventId, seq, sessionId, createdAt) so the frontend can use
     * authoritative IDs instead of generating its own.
     */
    private static Map<String, Object> wsEvent(AgentEvent ev, long sessionId, Map<String, Object> base) {
        Map<String, Object> enriched = new LinkedHashMap<>(base);
        enriched.put("sessionId", sessionId);
        if (ev != null) {
            enriched.put("eventId", ev.getId());
            enriched.put("seq", ev.getSeq());
            enriched.put("createdAt", ev.getCreatedAt());
        } else {
            enriched.put("createdAt", System.currentTimeMillis() / 1000L);
        }
        return enriched;
    }
}
