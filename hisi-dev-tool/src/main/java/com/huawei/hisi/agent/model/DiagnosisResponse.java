package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

/**
 * 诊断响应 DTO
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResponse {

    /**
     * 请求 ID
     */
    private String requestId;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 主要诊断结论
     */
    private String conclusion;

    /**
     * 根因分析
     */
    private String rootCause;

    /**
     * 置信度（0.0 - 1.0）
     */
    private double confidence;

    /**
     * 受影响的代码位置
     */
    @Builder.Default
    private List<String> affectedCode = new ArrayList<>();

    /**
     * 修复建议
     */
    @Builder.Default
    private List<String> fixSuggestions = new ArrayList<>();

    /**
     * 执行的 Agent 详情
     */
    @Builder.Default
    private List<AgentSummary> agents = new ArrayList<>();

    /**
     * 总执行时间（毫秒）
     */
    private long executionTimeMs;

    /**
     * 响应时间
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * Agent 执行摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentSummary {
        private String type;
        private String status;
        private double confidence;
        private long executionTimeMs;
        private String conclusion;
    }
}