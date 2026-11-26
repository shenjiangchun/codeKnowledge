package com.huawei.hisi.ram.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.ram.chat.dto.TurnResult;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class RamChatOrchestrator {

    private final AgentEventRepository eventRepository;
    /** 对话走统一运行时：声明 ram-chat（只读图谱 + 动作工具，动作带用户令牌）。 */
    private final ReActSession reactSession;
    private final ChatContextBuilder contextBuilder;
    private final RamChatWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;
    private final TurnRegistry turnRegistry;

    @Value("${ram.chat.timeout-seconds:300}")
    private long timeoutSeconds;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ram-chat-turn");
        t.setDaemon(true);
        return t;
    });

    public TurnResult runTurn(long sessionId, String userText, String projectPath) {
        return runTurn(sessionId, userText, List.of(projectPath), null);
    }

    public TurnResult runTurn(long sessionId, String userText, List<String> projectPaths) {
        return runTurn(sessionId, userText, projectPaths, null);
    }

    /**
     * Kick off a turn without blocking the caller. Used by the chat controller's
     * {@code POST /messages} path so the HTTP request returns immediately with a
     * {@code STARTED} status; the actual streaming reaches the client via
     * WebSocket ({@code assistant_delta} / {@code tool_use_*} / {@code checkpoint}).
     *
     * @param scopeToken 用户的 JWT（动作工具以用户身份调后端时用）；只读会话可传 null
     * @return the freshly assigned {@code turnId} (clients can use it to
     *         correlate incoming WebSocket events)
     */
    public String startTurnAsync(long sessionId, String userText, List<String> projectPaths) {
        return startTurnAsync(sessionId, userText, projectPaths, null);
    }

    public String startTurnAsync(long sessionId, String userText, List<String> projectPaths, String scopeToken) {
        String turnId = UUID.randomUUID().toString();
        asyncExecutor.submit(() -> {
            try {
                runTurnInternal(sessionId, turnId, userText, projectPaths, scopeToken);
            } catch (RuntimeException e) {
                log.error("[RamChatOrchestrator] async runTurn failed sessionId={} turnId={}: {}",
                        sessionId, turnId, e.getMessage(), e);
            }
        });
        return turnId;
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
        injectAndContinue(sessionId, userContent, projectPaths, null);
    }

    public void injectAndContinue(long sessionId, String userContent, List<String> projectPaths, String scopeToken) {
        log.info("[RamChatOrchestrator] inject sessionId={} contentLen={}", sessionId, userContent == null ? 0 : userContent.length());
        var interrupted = turnRegistry.interrupt(sessionId);
        if (interrupted.isPresent()) {
            TurnRegistry.InterruptResult r = interrupted.get();
            String payload;
            try {
                payload = objectMapper.writeValueAsString(Map.of(
                        "turnId", r.turnId(),
                        "partialText", r.partialText(),
                        "reason", "user_interrupt"));
            } catch (JsonProcessingException e) {
                log.error("[RamChatOrchestrator.injectAndContinue] failed to serialize turn_interrupted payload sessionId={} turnId={}: {}",
                        sessionId, r.turnId(), e.getMessage());
                // Do not submit the new turn; caller can retry /inject. TurnRegistry
                // interrupt is idempotent so the original turn stays interrupted.
                throw new IllegalStateException("failed to serialize turn_interrupted payload", e);
            }
            AgentEvent ev = eventRepository.append(AgentEvent.turnInterrupted(
                    sessionId, 0L, r.turnId(), payload, "interrupt-" + r.turnId()));
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("type", "turn_interrupted");
            wsPayload.put("turnId", r.turnId());
            wsPayload.put("partialText", r.partialText());
            wsPayload.put("reason", "user_interrupt");
            wsPayload.put("sessionId", sessionId);
            wsPayload.put("eventId", ev.getId());
            wsPayload.put("seq", ev.getSeq());
            wsPayload.put("createdAt", ev.getCreatedAt());
            wsHandler.pushEvent(sessionId, wsPayload);
        }
        asyncExecutor.submit(() -> runTurn(sessionId, userContent, projectPaths, scopeToken));
    }

    public TurnResult runTurn(long sessionId, String userText, List<String> projectPaths, String scopeToken) {
        return runTurnInternal(sessionId, UUID.randomUUID().toString(), userText, projectPaths, scopeToken);
    }

    private TurnResult runTurnInternal(long sessionId, String turnId, String userText,
                                       List<String> projectPaths, String scopeToken) {
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

            StringBuilder partialTextBuf = new StringBuilder();

            // 中断经 TurnRegistry：cancelled 判定让统一运行时在中止轮次里结束 pi 进程。
            // proxyDisposable 保持接口不变——统一运行时没有可 dispose 的订阅，取消只走判定。
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
                    turnId, sessionId, proxyDisposable, partialTextBuf, Instant.now(), null));

            ReActSession.Result result = CompletableFuture.supplyAsync(() -> reactSession.run(
                            AgentRegistry.agent("ram-chat").orElseThrow(),
                            "ram-chat-" + turnId.substring(0, 12),
                            workdir(projectPaths),
                            ctx.systemPrompt(),
                            ctx.userPrompt(),
                            event -> forwardChatEvent(event, sessionId, turnId, partialTextBuf),
                            () -> !isActive(sessionId, turnId),
                            scopeToken),
                    asyncExecutor)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();

            String finalText = result.text() == null ? "" : result.text();
            String summary = summarize(finalText);

            if (isActive(sessionId, turnId)) {
                AgentEvent ckptEv = appendEvent(sessionId, EventType.CHECKPOINT, Map.of(
                        "turnId", turnId,
                        "summary", summary,
                        "finalText", finalText,
                        "reasoningSteps", List.of()
                ), "ckpt-" + turnId);

                wsHandler.pushEvent(sessionId, wsEvent(ckptEv, sessionId, Map.of(
                        "type", "checkpoint",
                        "turnId", turnId,
                        "summary", summary,
                        "finalText", finalText
                )));

                log.info("[RamChatOrchestrator] done turnId={} finalText.len={}",
                        turnId, finalText.length());
            } else {
                log.info("[RamChatOrchestrator] skip checkpoint for interrupted turnId={} finalText.len={}",
                        turnId, finalText.length());
            }

            turnRegistry.complete(sessionId, turnId);

            return new TurnResult(turnId, "DONE", finalText, List.of(), result.note());
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

    /**
     * pi 事件 → 前端熟悉的 WebSocket 事件（增量、工具调用与结果）。
     * 中断后的迟到事件一律丢弃，与旧实现一致（TURN_INTERRUPTED 已封住这一轮）。
     */
    private void forwardChatEvent(Map<String, Object> event, long sessionId, String turnId, StringBuilder partialTextBuf) {
        String type = String.valueOf(event.getOrDefault("type", ""));
        if ("message_update".equals(type)) {
            if (!(event.get("assistantMessageEvent") instanceof Map<?, ?> update)
                    || !"text_delta".equals(String.valueOf(update.get("type")))
                    || update.get("delta") == null) {
                return;
            }
            if (!isActive(sessionId, turnId)) {
                log.debug("[RamChatOrchestrator] dropping late assistant_delta for aborted turnId={}", turnId);
                return;
            }
            String delta = String.valueOf(update.get("delta"));
            synchronized (partialTextBuf) {
                partialTextBuf.append(delta);
            }
            AgentEvent deltaEv = appendEvent(sessionId, EventType.ASSISTANT_DELTA, Map.of(
                    "turnId", turnId, "delta", delta), "delta-" + turnId + "-" + System.nanoTime());
            wsHandler.pushEvent(sessionId, wsEvent(deltaEv, sessionId, Map.of(
                    "type", "assistant_delta", "turnId", turnId, "delta", delta)));
        } else if ("tool_execution_start".equals(type)) {
            if (!isActive(sessionId, turnId)) {
                return;
            }
            String toolName = String.valueOf(event.getOrDefault("toolName", "?"));
            Object input = event.getOrDefault("args", Map.of());
            AgentEvent toolEv = appendEvent(sessionId, EventType.TOOL_USE, Map.of(
                    "turnId", turnId, "toolName", toolName, "input", input),
                    "tool-use-" + turnId + "-" + System.nanoTime());
            wsHandler.pushEvent(sessionId, wsEvent(toolEv, sessionId, Map.of(
                    "type", "tool_use_start", "turnId", turnId, "toolName", toolName, "input", input)));
        } else if ("tool_execution_end".equals(type)) {
            if (!isActive(sessionId, turnId)) {
                return;
            }
            String toolName = String.valueOf(event.getOrDefault("toolName", "?"));
            Object resultContent = event.get("result") != null ? event.get("result") : event.get("output");
            AgentEvent resultEv = appendEvent(sessionId, EventType.TOOL_RESULT, Map.of(
                    "turnId", turnId, "toolName", toolName, "result", resultContent),
                    "tool-result-" + turnId + "-" + System.nanoTime());
            wsHandler.pushEvent(sessionId, wsEvent(resultEv, sessionId, Map.of(
                    "type", "tool_result", "turnId", turnId, "toolName", toolName,
                    "result", resultContent)));
        }
    }

    /** 会话工作目录：第一个项目路径；没有就退到进程目录。 */
    private static java.nio.file.Path workdir(List<String> projectPaths) {
        if (projectPaths != null && !projectPaths.isEmpty()) {
            java.nio.file.Path path = java.nio.file.Path.of(projectPaths.get(0));
            if (java.nio.file.Files.isDirectory(path)) {
                return path;
            }
        }
        return java.nio.file.Path.of(System.getProperty("user.dir"));
    }

    /**
     * True iff the currently-registered active turn for {@code sessionId} is
     * still {@code turnId}. Late deltas / tool events / checkpoints coming
     * from an already-interrupted Reactor sink must NOT be persisted, because
     * the TURN_INTERRUPTED event has already sealed that turn.
     */
    private boolean isActive(long sessionId, String turnId) {
        return turnRegistry.get(sessionId)
                .map(t -> t.turnId().equals(turnId))
                .orElse(false);
    }

    /**
     * Extract a short summary (first line or first 200 chars) from the LLM response.
     * Falls back to empty string for null/blank input.
     */
    static String summarize(String text) {
        if (text == null || text.isBlank()) return "";
        // Use the first non-empty meaningful line (skip markdown headings prefix "# ")
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            // Strip leading markdown heading markers
            String cleaned = trimmed.replaceFirst("^#{1,6}\\s+", "");
            if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
            return cleaned;
        }
        return "";
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
