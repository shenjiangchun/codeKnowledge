package com.huawei.hisi.loganalysis.service;

import com.huawei.hisi.loganalysis.dto.FollowupMessageDto;
import com.huawei.hisi.loganalysis.dto.FollowupSessionDto;
import com.huawei.hisi.loganalysis.tool.LogReportLookupTool;
import com.huawei.hisi.loganalysis.websocket.LogFollowupWebSocketHandler;
import com.huawei.hisi.ram.nodes.impl.KgToolRegistry;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.nodes.impl.StreamCallbacks;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Manages follow-up Q&A sessions for log analysis reports.
 *
 * <p>Architecture:
 * <ol>
 *   <li>User sends a question about a completed log report</li>
 *   <li>Service creates a follow-up session (in-memory, keyed by sessionId)</li>
 *   <li>Claude is called with tool-use loop: lookup_log_report + KG tools</li>
 *   <li>Responses are streamed via WebSocket to the frontend</li>
 * </ol>
 */
@Slf4j
@Service
public class LogFollowupService {

    private final RamClaudeJsonClient claudeClient;
    private final KgToolRegistry kgToolRegistry;
    private final LogReportLookupTool reportLookupTool;
    private final LogFollowupWebSocketHandler wsHandler;

    @Value("${log.followup.timeout-seconds:120}")
    private long timeoutSeconds;

    private final Map<String, FollowupSession> sessions = new ConcurrentHashMap<>();

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "log-followup");
        t.setDaemon(true);
        return t;
    });

    public LogFollowupService(RamClaudeJsonClient claudeClient,
                              KgToolRegistry kgToolRegistry,
                              LogReportLookupTool reportLookupTool,
                              LogFollowupWebSocketHandler wsHandler) {
        this.claudeClient = claudeClient;
        this.kgToolRegistry = kgToolRegistry;
        this.reportLookupTool = reportLookupTool;
        this.wsHandler = wsHandler;
    }

    /**
     * Start a new follow-up session and send the first message.
     * Returns the sessionId immediately; the response streams via WebSocket.
     */
    public String startFollowup(long reportId, String message, String projectPath) {
        String sessionId = UUID.randomUUID().toString();

        List<FollowupMessageDto> messages = new ArrayList<>();
        messages.add(new FollowupMessageDto("user", message, System.currentTimeMillis()));

        FollowupSession session = new FollowupSession(sessionId, reportId, projectPath, messages);
        sessions.put(sessionId, session);

        // Emit user message to WS
        wsHandler.pushEvent(sessionId, Map.of(
                "type", "user_msg",
                "sessionId", sessionId,
                "text", message
        ));

        // Execute async
        CompletableFuture.runAsync(() -> executeFollowup(session), asyncExecutor);

        return sessionId;
    }

    /**
     * Continue an existing follow-up session with a new message.
     */
    public void continueFollowup(String sessionId, String message) {
        FollowupSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Follow-up session not found: " + sessionId);
        }

        session.messages.add(new FollowupMessageDto("user", message, System.currentTimeMillis()));

        wsHandler.pushEvent(sessionId, Map.of(
                "type", "user_msg",
                "sessionId", sessionId,
                "text", message
        ));

        CompletableFuture.runAsync(() -> executeFollowup(session), asyncExecutor);
    }

    /**
     * Get the current state of a follow-up session for reconnection.
     */
    public FollowupSessionDto getSession(String sessionId) {
        FollowupSession session = sessions.get(sessionId);
        if (session == null) return null;

        return new FollowupSessionDto(
                session.sessionId,
                session.reportId,
                List.copyOf(session.messages),
                session.status,
                session.createdAt,
                System.currentTimeMillis()
        );
    }

    private void executeFollowup(FollowupSession session) {
        session.status = "processing";
        String sessionId = session.sessionId;

        try {
            // Build system prompt
            String systemPrompt = buildSystemPrompt(session.reportId);

            // Build single user prompt from conversation history
            String userPrompt = buildConversationPrompt(session.messages);

            // Build tools: report lookup + KG tools (if projectPath available)
            List<ToolDefinition> tools = new ArrayList<>();
            tools.add(reportLookupTool.buildDefinition());

            Map<String, Function<Map<String, Object>, Object>> handlers = new LinkedHashMap<>();
            handlers.put("lookup_log_report", reportLookupTool.buildHandler());

            if (session.projectPath != null && !session.projectPath.isBlank() && kgToolRegistry.isAvailable()) {
                tools.addAll(kgToolRegistry.buildToolDefinitions(session.projectPath));
                handlers.putAll(kgToolRegistry.buildToolHandlers(session.projectPath));
            }

            // Stream callbacks
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
                    // No-op for follow-up; we track via delta
                }
            };

            // Call Claude with tool loop (single user prompt with conversation context)
            CompletableFuture<RamClaudeJsonClient.JsonCallResult> future = CompletableFuture.supplyAsync(() ->
                    claudeClient.callJsonWithToolsAndStreaming(
                            systemPrompt,
                            userPrompt,
                            tools,
                            handlers,
                            SendOptions.defaults(),
                            callbacks
                    ), asyncExecutor);

            RamClaudeJsonClient.JsonCallResult result = future
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();

            String finalText = extractFinalText(result.json());

            // Store assistant response
            session.messages.add(new FollowupMessageDto("assistant",
                    finalText != null ? finalText : "(no response)",
                    System.currentTimeMillis()));

            session.status = "completed";

            wsHandler.pushEvent(sessionId, Map.of(
                    "type", "turn_complete",
                    "sessionId", sessionId,
                    "text", finalText
            ));

        } catch (Exception e) {
            log.error("[LogFollowup] session={} failed: {}", sessionId, e.getMessage(), e);
            session.status = "error";

            wsHandler.pushEvent(sessionId, Map.of(
                    "type", "error",
                    "sessionId", sessionId,
                    "error", e.getMessage()
            ));
        }
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
     * Build a single user prompt from conversation history.
     * The Claude API takes (systemPrompt, userPrompt) pairs, so we embed
     * multi-turn history as context within the user prompt.
     */
    private static String buildConversationPrompt(List<FollowupMessageDto> messages) {
        if (messages.size() == 1) {
            return messages.get(0).content();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是对话历史：\n\n");
        for (FollowupMessageDto msg : messages) {
            String role = "user".equals(msg.role()) ? "用户" : "助手";
            sb.append("[").append(role).append("] ").append(msg.content()).append("\n\n");
        }
        sb.append("请基于以上对话历史，回答用户的最新问题。");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String extractFinalText(Map<String, Object> json) {
        if (json == null) return null;
        Object text = json.get("text");
        if (text instanceof String s && !s.isBlank()) return s;
        // Try nested fields
        Object summary = json.get("summary");
        if (summary instanceof String s && !s.isBlank()) return s;
        Object content = json.get("content");
        if (content instanceof String s && !s.isBlank()) return s;
        return json.toString();
    }

    /** In-memory follow-up session state. */
    private static class FollowupSession {
        final String sessionId;
        final long reportId;
        final String projectPath;
        final List<FollowupMessageDto> messages;
        volatile String status = "processing";
        final long createdAt = System.currentTimeMillis();

        FollowupSession(String sessionId, long reportId, String projectPath, List<FollowupMessageDto> messages) {
            this.sessionId = sessionId;
            this.reportId = reportId;
            this.projectPath = projectPath;
            this.messages = messages;
        }
    }
}
