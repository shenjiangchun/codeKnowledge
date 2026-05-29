package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Agent 执行上下文
 * 封装诊断所需的输入数据和元信息
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    /**
     * 诊断请求唯一标识
     */
    private String requestId;

    /**
     * 项目路径（用于代码搜索）
     */
    private String projectPath;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 堆栈追踪信息
     */
    private String stackTrace;

    /**
     * 日志内容（可选）
     */
    private String logContent;

    /**
     * 会话ID（支持多轮对话）
     */
    private String sessionId;

    /**
     * Trace ID（调用链追踪）
     */
    private String traceId;

    /**
     * 入口点信息
     */
    private String entryPoint;

    /**
     * 扩展属性（用于传递额外数据）
     */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    /**
     * 已执行的 Agent 结果（用于多 Agent 协作）
     */
    @Builder.Default
    private List<AgentResult> previousResults = new ArrayList<>();

    /**
     * 创建时间
     */
    @Builder.Default
    private LocalDateTime createTime = LocalDateTime.now();

    /**
     * 添加扩展属性
     */
    public void addAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }

    /**
     * 获取扩展属性
     */
    public Object getAttribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }

    /**
     * 添加前序 Agent 结果
     */
    public void addPreviousResult(AgentResult result) {
        if (previousResults == null) {
            previousResults = new ArrayList<>();
        }
        previousResults.add(result);
    }
}