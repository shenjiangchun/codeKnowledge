package com.huawei.hisi.agent.orchestrator;

import com.huawei.hisi.agent.model.AgentResult;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

/**
 * 诊断结果
 * 聚合多个 Agent 执行结果的最终诊断报告
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResult {

    /**
     * 关联的请求 ID
     */
    private String requestId;

    /**
     * 主要诊断结论
     */
    private String primaryConclusion;

    /**
     * 主要根因分析
     */
    private String primaryRootCause;

    /**
     * 主要结论置信度
     */
    private double primaryConfidence;

    /**
     * 提供主要结论的 Agent 类型
     */
    private String primaryAgentType;

    /**
     * 综合置信度（所有 Agent 加权平均）
     */
    private double overallConfidence;

    /**
     * 合并的受影响代码列表
     */
    @Builder.Default
    private List<String> combinedAffectedCode = new ArrayList<>();

    /**
     * 合并的修复建议列表
     */
    @Builder.Default
    private List<String> combinedFixSuggestions = new ArrayList<>();

    /**
     * 所有 Agent 执行结果
     */
    @Builder.Default
    private List<AgentResult> agentResults = new ArrayList<>();

    /**
     * 成功执行的 Agent 数量
     */
    private int successCount;

    /**
     * 失败执行的 Agent 数量
     */
    private int failedCount;

    /**
     * 总执行时间（毫秒）
     */
    private long totalTimeMs;

    /**
     * 结果生成时间
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * 创建空的诊断结果
     */
    public static DiagnosisResult empty(String requestId, String message) {
        return DiagnosisResult.builder()
                .requestId(requestId)
                .primaryConclusion(message)
                .primaryRootCause(message)
                .primaryConfidence(0.0)
                .overallConfidence(0.0)
                .successCount(0)
                .failedCount(0)
                .totalTimeMs(0)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 判断是否有有效结论
     */
    public boolean hasValidConclusion() {
        return primaryConclusion != null && primaryConfidence > 0.3;
    }

    /**
     * 获取所有成功的 Agent 结果
     */
    public List<AgentResult> getSuccessfulResults() {
        return agentResults.stream()
                .filter(AgentResult::isSuccess)
                .toList();
    }

    /**
     * 获取所有失败的 Agent 结果
     */
    public List<AgentResult> getFailedResults() {
        return agentResults.stream()
                .filter(r -> !r.isSuccess())
                .toList();
    }

    /**
     * 判断是否所有 Agent 都失败
     */
    public boolean isAllFailed() {
        return successCount == 0 && failedCount > 0;
    }
}