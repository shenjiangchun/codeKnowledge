package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Agent 事件
 * 用于 WebSocket 推送的实时状态更新
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    /**
     * 事件类型
     */
    public enum EventType {
        REQUEST_RECEIVED,    // 诊断请求已接收
        AGENT_STARTED,       // Agent 开始执行
        AGENT_PROGRESS,      // Agent 执行进度更新
        AGENT_COMPLETED,     // Agent 执行完成
        AGENT_FAILED,        // Agent 执行失败
        AGENT_SKIPPED,       // Agent 被跳过
        ORCHESTRATION_START, // 编排开始
        ORCHESTRATION_END,   // 编排结束
        FINAL_RESULT         // 最终诊断结果
    }

    /**
     * 事件唯一标识
     */
    private String eventId;

    /**
     * 关联的请求 ID
     */
    private String requestId;

    /**
     * 事件类型
     */
    private EventType eventType;

    /**
     * Agent 类型（可选）
     */
    private String agentType;

    /**
     * 事件消息
     */
    private String message;

    /**
     * 进度百分比（0-100，用于 AGENT_PROGRESS）
     */
    private Integer progress;

    /**
     * 置信度（用于 AGENT_COMPLETED）
     */
    private Double confidence;

    /**
     * 当前阶段描述
     */
    private String phase;

    /**
     * 部分结果（用于中间状态推送）
     */
    private AgentResult partialResult;

    /**
     * 事件时间戳
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * 创建请求接收事件
     */
    public static AgentEvent requestReceived(String requestId) {
        return AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(requestId)
                .eventType(EventType.REQUEST_RECEIVED)
                .message("诊断请求已接收")
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 创建 Agent 启动事件
     */
    public static AgentEvent agentStarted(String requestId, String agentType, String phase) {
        return AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(requestId)
                .eventType(EventType.AGENT_STARTED)
                .agentType(agentType)
                .message("Agent " + agentType + " 开始执行")
                .phase(phase)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 创建 Agent 完成事件
     */
    public static AgentEvent agentCompleted(String requestId, String agentType, double confidence) {
        return AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(requestId)
                .eventType(EventType.AGENT_COMPLETED)
                .agentType(agentType)
                .message("Agent " + agentType + " 执行完成")
                .confidence(confidence)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 创建 Agent 失败事件
     */
    public static AgentEvent agentFailed(String requestId, String agentType, String errorMessage) {
        return AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(requestId)
                .eventType(EventType.AGENT_FAILED)
                .agentType(agentType)
                .message("Agent " + agentType + " 执行失败: " + errorMessage)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 创建编排开始事件
     */
    public static AgentEvent orchestrationStart(String requestId) {
        return AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(requestId)
                .eventType(EventType.ORCHESTRATION_START)
                .message("多Agent诊断编排开始")
                .phase("初始化")
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 创建编排结束事件
     */
    public static AgentEvent orchestrationEnd(String requestId) {
        return AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(requestId)
                .eventType(EventType.ORCHESTRATION_END)
                .message("多Agent诊断编排结束")
                .timestamp(LocalDateTime.now())
                .build();
    }
}