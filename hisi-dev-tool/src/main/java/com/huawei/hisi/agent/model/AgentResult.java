package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Agent 执行结果
 * 统一的 Agent 返回格式
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    /**
     * 执行结果状态
     */
    public enum Status {
        SUCCESS,       // 成功完成
        PARTIAL,       // 部分完成（需要其他 Agent 补充）
        FAILED,        // 执行失败
        SKIPPED        // 被跳过（置信度过低）
    }

    /**
     * Agent 类型标识
     */
    private String agentType;

    /**
     * 关联的请求 ID
     */
    private String requestId;

    /**
     * 执行状态
     */
    private Status status;

    /**
     * 置信度（0.0 - 1.0）
     */
    private double confidence;

    /**
     * 诊断结论
     */
    private String conclusion;

    /**
     * 根因分析结果
     */
    private String rootCause;

    /**
     * 受影响的代码位置列表
     */
    @Builder.Default
    private List<String> affectedCode = new ArrayList<>();

    /**
     * 修复建议列表
     */
    @Builder.Default
    private List<String> fixSuggestions = new ArrayList<>();

    /**
     * 提取的关键信息（如异常类型、方法名等）
     */
    @Builder.Default
    private Map<String, Object> extractedInfo = new HashMap<>();

    /**
     * 执行耗时（毫秒）
     */
    private long executionTimeMs;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 执行时间戳
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // ============== 流式输出支持 ==============

    /**
     * 是否为流式输出
     */
    @Builder.Default
    private boolean streaming = false;

    /**
     * 流式输出内容
     */
    private Flux<String> stream;

    /**
     * 会话ID（用于流式输出关联）
     */
    private String sessionId;

    /**
     * 添加受影响代码
     */
    public void addAffectedCode(String code) {
        if (affectedCode == null) {
            affectedCode = new ArrayList<>();
        }
        affectedCode.add(code);
    }

    /**
     * 添加修复建议
     */
    public void addFixSuggestion(String suggestion) {
        if (fixSuggestions == null) {
            fixSuggestions = new ArrayList<>();
        }
        fixSuggestions.add(suggestion);
    }

    /**
     * 添加提取信息
     */
    public void addExtractedInfo(String key, Object value) {
        if (extractedInfo == null) {
            extractedInfo = new HashMap<>();
        }
        extractedInfo.put(key, value);
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.PARTIAL;
    }

    // ============== 流式输出方法 ==============

    /**
     * 创建流式结果
     *
     * @param sessionId 会话ID
     * @param stream    流式输出内容
     * @return 流式 AgentResult
     */
    public static AgentResult streaming(String sessionId, Flux<String> stream) {
        return AgentResult.builder()
                .streaming(true)
                .sessionId(sessionId)
                .stream(stream)
                .build();
    }

    /**
     * 是否为流式结果
     *
     * @return true 如果是流式结果且流不为空
     */
    public boolean isStreaming() {
        return streaming && stream != null;
    }

    /**
     * 获取流式输出
     *
     * @return Flux<String> 流式内容
     */
    public Flux<String> getStream() {
        return stream;
    }
}