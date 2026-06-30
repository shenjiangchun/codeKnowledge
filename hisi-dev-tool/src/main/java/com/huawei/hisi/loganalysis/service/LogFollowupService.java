package com.huawei.hisi.loganalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.loganalysis.dto.FollowupMessageDto;
import com.huawei.hisi.loganalysis.dto.FollowupSessionDto;
import com.huawei.hisi.loganalysis.tool.LogReportLookupTool;
import com.huawei.hisi.loganalysis.websocket.LogFollowupWebSocketHandler;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.nodes.impl.KgToolRegistry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.nodes.impl.StreamCallbacks;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Manages follow-up Q&A sessions for log analysis reports.
 *
 * <p>Persistence: each session is one row in {@code agent_session} with
 * {@code session_type=LOG_FOLLOWUP}; each user/assistant message is appended
 * to {@code agent_event} with {@code type=MESSAGE} and payload
 * {@code {"role":"user|assistant","content":"...","createdAt":...}}.
 */
@Slf4j
@Service
public class LogFollowupService {

    private final RamClaudeJsonClient claudeClient;
    private final KgToolRegistry kgToolRegistry;
    private final LogReportLookupTool reportLookupTool;
    private final LogFollowupWebSocketHandler wsHandler;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${log.followup.timeout-seconds:120}")
    private long timeoutSeconds;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "log-followup");
        t.setDaemon(true);
        return t;
    });

    public LogFollowupService(RamClaudeJsonClient claudeClient,
                              KgToolRegistry kgToolRegistry,
                              LogReportLookupTool reportLookupTool,
                              LogFollowupWebSocketHandler wsHandler,
                              AgentSessionRepository sessionRepository,
                              AgentEventRepository eventRepository) {
        this.claudeClient = claudeClient;
        this.kgToolRegistry = kgToolRegistry;
        this.reportLookupTool = reportLookupTool;
        this.wsHandler = wsHandler;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Start a new follow-up session and send the first message.
     * Returns the sessionId immediately; the response streams via WebSocket.
     */
    public String startFollowup(long reportId, String message, String projectPath) {
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long nowEpoch = now / 1000L;

        AgentSession session = AgentSession.builder()
                .userId("log-followup-" + reportId)
                .status(SessionStatus.RUNNING)
                .currentNode("followup")
                .stepCount(0)
                .uuid(sessionId)
                .intent("log-followup")
                .projectPaths(projectPath)
                .sessionType(SessionType.LOG_FOLLOWUP)
                .version(0)
                .createdAt(nowEpoch)
                .updatedAt(nowEpoch)
                .build();
        AgentSession saved = sessionRepository.save(session);

        appendMessage(saved.getId(), "user", message, now);

        wsHandler.pushEvent(sessionId, Map.of(
                "type", "user_msg",
                "sessionId", sessionId,
                "text", message
        ));

        CompletableFuture.runAsync(() -> executeFollowup(saved, projectPath), asyncExecutor);

        return sessionId;
    }

    /**
     * Continue an existing follow-up session with a new message.
     */
    public void continueFollowup(String sessionId, String message) {
        AgentSession session = sessionRepository.findByUuid(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Follow-up session not found: " + sessionId));

        long now = System.currentTimeMillis();
        appendMessage(session.getId(), "user", message, now);

        wsHandler.pushEvent(sessionId, Map.of(
                "type", "user_msg",
                "sessionId", sessionId,
                "text", message
        ));

        CompletableFuture.runAsync(() -> executeFollowup(session, session.getProjectPaths()), asyncExecutor);
    }

    /**
     * Get the current state of a follow-up session for reconnection.
     */
    public FollowupSessionDto getSession(String sessionId) {
        AgentSession session = sessionRepository.findByUuid(sessionId).orElse(null);
        if (session == null) return null;

        List<FollowupMessageDto> messages = loadMessages(session.getId());
        String status = mapStatus(session.getStatus());

        return new FollowupSessionDto(
                session.getUuid(),
                extractReportId(session.getUserId()),
                List.copyOf(messages),
                status,
                session.getCreatedAt() * 1000L,
                session.getUpdatedAt() * 1000L
        );
    }

    private void executeFollowup(AgentSession session, String projectPath) {
        String sessionId = session.getUuid();
        try {
            String systemPrompt = buildSystemPrompt(extractReportId(session.getUserId()));
            List<FollowupMessageDto> history = loadMessages(session.getId());
            List<Map<String, Object>> messages = buildMessagesArray(history);

            List<ToolDefinition> tools = new ArrayList<>();
            tools.add(reportLookupTool.buildDefinition());

            Map<String, Function<Map<String, Object>, Object>> handlers = new LinkedHashMap<>();
            handlers.put("lookup_log_report", reportLookupTool.buildHandler(session.getUserId()));

            if (projectPath != null && !projectPath.isBlank() && kgToolRegistry.isAvailable()) {
                tools.addAll(kgToolRegistry.buildToolDefinitions(projectPath));
                handlers.putAll(kgToolRegistry.buildToolHandlers(projectPath));
            }

            StreamCallbacks callbacks = new StreamCallbacks() {
                @Override
                public void onAssistantDelta(String deltaText) {
                    wsHandler.pushEvent(sessionId, Map.of(
                            "type", "assistant_delta",
                            "sessionId", sessionId,
                            "delta", deltaText
                    ));
                }

                @Override
                public void onToolUseStart(String toolName, Map<String, Object> input) {
                    wsHandler.pushEvent(sessionId, Map.of(
                            "type", "tool_use",
                            "sessionId", sessionId,
                            "toolName", toolName,
                            "input", input
                    ));
                }

                @Override
                public void onToolResult(String toolName, String resultContent) {
                    wsHandler.pushEvent(sessionId, Map.of(
                            "type", "tool_result",
                            "sessionId", sessionId,
                            "toolName", toolName,
                            "result", truncateResult(resultContent)
                    ));
                }

                @Override
                public void onRoundComplete(int round, String assistantText) {
                    // No-op for follow-up
                }
            };

            CompletableFuture<RamClaudeJsonClient.JsonCallResult> future = CompletableFuture.supplyAsync(() ->
                    claudeClient.callJsonWithToolsAndStreamingMultiTurn(
                            systemPrompt,
                            messages,
                            tools,
                            handlers,
                            SendOptions.defaults(),
                            callbacks
                    ), asyncExecutor);

            RamClaudeJsonClient.JsonCallResult result = future
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();

            String finalText = extractFinalText(result.json());

            appendMessage(session.getId(), "assistant",
                    finalText != null ? finalText : "(no response)",
                    System.currentTimeMillis());

            sessionRepository.updateStatus(session.getId(), SessionStatus.DONE);

            wsHandler.pushEvent(sessionId, Map.of(
                    "type", "turn_complete",
                    "sessionId", sessionId,
                    "text", finalText
            ));

        } catch (Exception e) {
            log.error("[LogFollowup] session={} failed: {}", sessionId, e.getMessage(), e);
            sessionRepository.updateStatus(session.getId(), SessionStatus.FAILED);

            wsHandler.pushEvent(sessionId, Map.of(
                    "type", "error",
                    "sessionId", sessionId,
                    "error", e.getMessage()
            ));
        }
    }

    private void appendMessage(long sessionId, String role, String content, long createdAt) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "role", role,
                    "content", content,
                    "createdAt", createdAt
            ));
            long seq = eventRepository.findMaxSeq(sessionId) + 1;
            AgentEvent event = AgentEvent.builder()
                    .sessionId(sessionId)
                    .seq(seq)
                    .type(EventType.MESSAGE)
                    .payload(payload)
                    .idempotencyKey("followup-" + sessionId + "-" + seq)
                    .cumulativeTokens(0L)
                    .retryCount(0)
                    .createdAt(createdAt / 1000L)
                    .build();
            eventRepository.append(event);
        } catch (Exception e) {
            log.error("[LogFollowup] appendMessage failed session={} role={}: {}", sessionId, role, e.getMessage());
        }
    }

    private List<FollowupMessageDto> loadMessages(long sessionId) {
        List<AgentEvent> events = eventRepository.findBySessionId(sessionId);
        List<FollowupMessageDto> messages = new ArrayList<>();
        for (AgentEvent e : events) {
            if (e.getType() != EventType.MESSAGE) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = objectMapper.readValue(e.getPayload(), Map.class);
                String role = String.valueOf(payload.get("role"));
                String content = String.valueOf(payload.get("content"));
                long createdAt = payload.get("createdAt") instanceof Number n ? n.longValue() : e.getCreatedAt() * 1000L;
                messages.add(new FollowupMessageDto(role, content, createdAt));
            } catch (Exception ex) {
                log.warn("[LogFollowup] Failed to parse event payload event={}: {}", e.getId(), ex.getMessage());
            }
        }
        return messages;
    }

    private static long extractReportId(String userId) {
        // userId format: "log-followup-{reportId}"
        if (userId == null) return 0L;
        int idx = userId.lastIndexOf('-');
        if (idx < 0) return 0L;
        try {
            return Long.parseLong(userId.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String mapStatus(SessionStatus status) {
        if (status == null) return "unknown";
        return switch (status) {
            case RUNNING -> "processing";
            case DONE -> "completed";
            case FAILED -> "error";
            default -> status.name().toLowerCase();
        };
    }

    private String buildSystemPrompt(long reportId) {
        return """
                你是一个日志分析助手。用户正在查看一份已完成的日志根因分析报告，并提出后续问题。

                你可以使用以下工具：
                - lookup_log_report: 查看报告的详细信息（错误消息、堆栈跟踪、分析结果等）
                - hybrid_search: 在项目代码库中语义搜索相关代码
                - load_method_bodies: 加载指定方法的源代码
                - callees_tree: 查看方法的下游调用链
                - root_entries: 查找方法的上游入口点
                - grep_project: 在项目文件中搜索文本/正则

                当前报告 ID: %d

                请用中文回答，基于报告数据和代码搜索结果给出准确、有深度的分析。
                """.formatted(reportId);
    }

    private static String truncateResult(String result) {
        if (result != null && result.length() > 5000) {
            return result.substring(0, 5000) + "... (truncated)";
        }
        return result;
    }

    /**
     * Build a multi-turn messages array from conversation history.
     * Each message is a Map with "role" and "content" keys,
     * suitable for the Claude Messages API.
     */
    private static List<Map<String, Object>> buildMessagesArray(List<FollowupMessageDto> messages) {
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        for (FollowupMessageDto msg : messages) {
            result.add(Map.of("role", msg.role(), "content", msg.content()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String extractFinalText(Map<String, Object> json) {
        if (json == null) return null;
        Object text = json.get("text");
        if (text instanceof String s && !s.isBlank()) return s;
        Object summary = json.get("summary");
        if (summary instanceof String s && !s.isBlank()) return s;
        Object content = json.get("content");
        if (content instanceof String s && !s.isBlank()) return s;
        return json.toString();
    }
}
