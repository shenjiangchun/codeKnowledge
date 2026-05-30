package com.huawei.hisi.service.intent;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话上下文
 * 维护多轮对话的状态和累积信息
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogContext {

    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 用户ID（可选）
     */
    private String userId;

    /**
     * 对话历史消息列表
     */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    /**
     * 当前任务描述
     */
    private String currentTask;

    /**
     * 任务状态
     */
    @Builder.Default
    private TaskState taskState = TaskState.IDLE;

    /**
     * 已识别的实体累积
     */
    @Builder.Default
    private Map<String, Object> entities = new HashMap<>();

    /**
     * 已分析的文件列表
     */
    @Builder.Default
    private List<String> analyzedFiles = new ArrayList<>();

    /**
     * 上次诊断结论
     */
    private String lastConclusion;

    /**
     * 上次识别的意图
     */
    private IntentType lastIntent;

    /**
     * 项目路径（当前分析的项目）
     */
    private String projectPath;

    /**
     * 当前错误消息
     */
    private String currentErrorMessage;

    /**
     * 当前堆栈追踪
     */
    private String currentStackTrace;

    /**
     * 会话创建时间
     */
    @Builder.Default
    private LocalDateTime createTime = LocalDateTime.now();

    /**
     * 最后活动时间
     */
    @Builder.Default
    private LocalDateTime lastActivityTime = LocalDateTime.now();

    // ============== 任务状态枚举 ==============

    public enum TaskState {
        IDLE,           // 空闲，等待用户输入
        ANALYZING,      // 正在分析
        WAITING_CLARIFICATION,  // 等待用户澄清
        COMPLETED,      // 任务完成
        ERROR           // 出错
    }

    // ============== 消息内部类 ==============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        /**
         * 消息角色：user / assistant / system
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;

        /**
         * 意图（仅对用户消息有效）
         */
        private IntentType intent;

        /**
         * 时间戳
         */
        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
    }

    // ============== 上下文更新方法 ==============

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content, IntentType intent) {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(Message.builder()
                .role("user")
                .content(content)
                .intent(intent)
                .timestamp(LocalDateTime.now())
                .build());
        lastActivityTime = LocalDateTime.now();
    }

    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content) {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(Message.builder()
                .role("assistant")
                .content(content)
                .timestamp(LocalDateTime.now())
                .build());
        lastActivityTime = LocalDateTime.now();
    }

    /**
     * 更新实体信息
     */
    public void updateEntities(Map<String, String> newEntities) {
        if (entities == null) {
            entities = new HashMap<>();
        }
        if (newEntities != null) {
            newEntities.forEach((key, value) -> {
                if (value != null && !value.isEmpty()) {
                    entities.put(key, value);
                }
            });
        }
    }

    /**
     * 添加已分析文件
     */
    public void addAnalyzedFile(String filePath) {
        if (analyzedFiles == null) {
            analyzedFiles = new ArrayList<>();
        }
        if (!analyzedFiles.contains(filePath)) {
            analyzedFiles.add(filePath);
        }
    }

    /**
     * 更新任务状态
     */
    public void updateTaskState(TaskState state) {
        this.taskState = state;
        this.lastActivityTime = LocalDateTime.now();
    }

    // ============== 信息获取方法 ==============

    /**
     * 获取实体值
     */
    public Object getEntity(String key) {
        return entities != null ? entities.get(key) : null;
    }

    /**
     * 获取最近N条消息
     */
    public List<Message> getRecentMessages(int count) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        int start = Math.max(0, messages.size() - count);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    /**
     * 获取对话摘要（用于LLM Prompt）
     */
    public String getContextSummary() {
        StringBuilder summary = new StringBuilder();

        // 已识别信息
        summary.append("已识别信息:\n");
        if (entities != null && !entities.isEmpty()) {
            if (entities.containsKey("errorType")) {
                summary.append("- 错误类型: ").append(entities.get("errorType")).append("\n");
            }
            if (entities.containsKey("className")) {
                summary.append("- 涉及类: ").append(entities.get("className")).append("\n");
            }
            if (entities.containsKey("methodName")) {
                summary.append("- 涉及方法: ").append(entities.get("methodName")).append("\n");
            }
        }

        // 已分析文件
        if (analyzedFiles != null && !analyzedFiles.isEmpty()) {
            summary.append("- 已分析文件: ").append(String.join(", ", analyzedFiles)).append("\n");
        }

        // 上次结论
        if (lastConclusion != null && !lastConclusion.isEmpty()) {
            summary.append("- 上次结论: ").append(lastConclusion).append("\n");
        }

        // 对话历史摘要
        summary.append("\n对话历史摘要:\n");
        List<Message> recent = getRecentMessages(5);
        for (int i = 0; i < recent.size(); i++) {
            Message msg = recent.get(i);
            summary.append(i + 1).append(". [").append(msg.getRole()).append("]: ")
                    .append(truncate(msg.getContent(), 100)).append("\n");
        }

        return summary.toString();
    }

    /**
     * 截断字符串
     */
    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    // ============== 静态工厂方法 ==============

    /**
     * 创建新会话
     */
    public static DialogContext newSession(String sessionId) {
        return DialogContext.builder()
                .sessionId(sessionId)
                .messages(new ArrayList<>())
                .entities(new HashMap<>())
                .analyzedFiles(new ArrayList<>())
                .taskState(TaskState.IDLE)
                .createTime(LocalDateTime.now())
                .lastActivityTime(LocalDateTime.now())
                .build();
    }
}