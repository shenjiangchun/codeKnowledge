package com.huawei.hisi.loganalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.loganalysis.dto.FollowupMessageDto;
import com.huawei.hisi.loganalysis.dto.FollowupSessionDto;
import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.loganalysis.websocket.LogFollowupWebSocketHandler;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
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

    private final ReActSession reactSession;
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

    public LogFollowupService(ReActSession session,
                              LogFollowupWebSocketHandler wsHandler,
                              AgentSessionRepository sessionRepository,
                              AgentEventRepository eventRepository) {
        this.reactSession = session;
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

    private void executeFollowup(AgentSession session, String projectPathRaw) {
        String sessionId = session.getUuid();
        try {
            String systemPrompt = buildSystemPrompt(extractReportId(session.getUserId()));
            List<FollowupMessageDto> history = loadMessages(session.getId());
            List<String> projectPaths = parseProjectPaths(projectPathRaw);
            String userPrompt = buildFollowupPrompt(history, projectPaths);

            // 报告内容与图谱都经只读 MCP 工具查（log-report / kg_*）：统一运行时按声明装配
            ReActSession.Result followup = CompletableFuture.supplyAsync(() -> reactSession.run(
                            AgentRegistry.agent("log-followup").orElseThrow(),
                            "lf-" + session.getUuid().substring(0, 12),
                            workdir(projectPaths),
                            systemPrompt,
                            userPrompt,
                            event -> forwardEvent(event, sessionId),
                            () -> false,
                            null),
                    asyncExecutor)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();

            String finalText = followup.text();
            if (followup.note() != null && (finalText == null || finalText.isBlank())) {
                throw new IllegalStateException(followup.note());
            }

            appendMessage(session.getId(), "assistant",
                    finalText != null && !finalText.isBlank() ? finalText : "(no response)",
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

    private static List<String> parseProjectPaths(String projectPathRaw) {
        if (projectPathRaw == null || projectPathRaw.isBlank()) return List.of();
        if (projectPathRaw.contains(",")) {
            return Arrays.stream(projectPathRaw.split(","))
                    .map(String::trim)
                    .filter(p -> !p.isEmpty())
                    .toList();
        }
        return List.of(projectPathRaw.trim());
    }

    /** 把历史问答拼成一次用户消息：会话每次只追加，pi 在同一个会话里看不到旧消息，历史要随提示词带全。 */
    private static String buildFollowupPrompt(List<FollowupMessageDto> history, List<String> projectPaths) {
        StringBuilder prompt = new StringBuilder();
        if (!history.isEmpty()) {
            prompt.append("以下是本会话的历史问答：\n");
            for (FollowupMessageDto message : history) {
                prompt.append(message.role()).append(": ").append(message.content()).append('\n');
            }
            prompt.append('\n');
        }
        if (!projectPaths.isEmpty()) {
            prompt.append("要查的项目路径：").append(String.join("、", projectPaths)).append('\n');
        }
        prompt.append("请基于报告内容与代码/日志证据回答最后一条用户消息。需要报告细节用 log-report 工具查，"
                + "需要代码与调用关系用图谱工具查，不要编造。");
        return prompt.toString();
    }

    /** pi 会话工作目录：第一个项目路径；没有就退到进程目录。 */
    private static java.nio.file.Path workdir(List<String> projectPaths) {
        if (!projectPaths.isEmpty()) {
            java.nio.file.Path path = java.nio.file.Path.of(projectPaths.get(0));
            if (java.nio.file.Files.isDirectory(path)) {
                return path;
            }
        }
        return java.nio.file.Path.of(System.getProperty("user.dir"));
    }

    /** pi 事件 → 前端熟悉的 WebSocket 事件（增量、工具调用与结果）。 */
    private void forwardEvent(Map<String, Object> event, String sessionId) {
        String type = String.valueOf(event.getOrDefault("type", ""));
        if ("message_update".equals(type) && event.get("assistantMessageEvent") instanceof Map<?, ?> update
                && "text_delta".equals(String.valueOf(update.get("type"))) && update.get("delta") != null) {
            wsHandler.pushEvent(sessionId, Map.of("type", "assistant_delta", "sessionId", sessionId,
                    "delta", update.get("delta")));
        } else if ("tool_execution_start".equals(type)) {
            wsHandler.pushEvent(sessionId, Map.of("type", "tool_use", "sessionId", sessionId,
                    "toolName", event.getOrDefault("toolName", "?"), "input", event.getOrDefault("args", Map.of())));
        } else if ("tool_execution_end".equals(type)) {
            Object result = event.get("result") != null ? event.get("result") : event.get("output");
            wsHandler.pushEvent(sessionId, Map.of("type", "tool_result", "sessionId", sessionId,
                    "toolName", event.getOrDefault("toolName", "?"),
                    "result", truncateResult(String.valueOf(result))));
        }
    }
}
