package com.huawei.hisi.service.intent;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.ArrayList;

/**
 * 自然语言诊断响应
 * 封装诊断结果返回给前端
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
     * 响应状态
     */
    public enum Status {
        SUCCESS,            // 成功完成诊断
        PARTIAL,            // 部分完成，需要更多信息
        CLARIFICATION_NEEDED,  // 需要用户澄清
        IN_PROGRESS,        // 正在处理中
        ERROR,              // 出错
        UNKNOWN_INTENT      // 无法识别意图
    }

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 响应状态
     */
    private Status status;

    /**
     * 主要结论
     */
    private String conclusion;

    /**
     * 根因分析
     */
    private String rootCause;

    /**
     * 置信度
     */
    private double confidence;

    /**
     * 响应消息（自然语言）
     */
    private String message;

    /**
     * 修复建议
     */
    @Builder.Default
    private List<String> fixSuggestions = new ArrayList<>();

    /**
     * 受影响的代码
     */
    @Builder.Default
    private List<String> affectedCode = new ArrayList<>();

    /**
     * 澄清问题列表
     */
    @Builder.Default
    private List<String> clarificationQuestions = new ArrayList<>();

    /**
     * 执行耗时（毫秒）
     */
    private long totalTimeMs;

    /**
     * 识别的意图类型
     */
    private String intentType;

    // ============== 静态工厂方法 ==============

    /**
     * 创建需要澄清的响应
     */
    public static DiagnosisResponse clarificationNeeded(List<String> questions, String sessionId) {
        return DiagnosisResponse.builder()
                .sessionId(sessionId)
                .status(Status.CLARIFICATION_NEEDED)
                .clarificationQuestions(questions)
                .message("需要您提供更多信息")
                .build();
    }

    /**
     * 创建未知意图响应
     */
    public static DiagnosisResponse unknownIntent(String sessionId) {
        return DiagnosisResponse.builder()
                .sessionId(sessionId)
                .status(Status.UNKNOWN_INTENT)
                .message("抱歉，我无法理解您的意思。请描述您遇到的问题或需要查询的内容。")
                .build();
    }

    /**
     * 创建处理中响应
     */
    public static DiagnosisResponse inProgress(String message, String sessionId) {
        return DiagnosisResponse.builder()
                .sessionId(sessionId)
                .status(Status.IN_PROGRESS)
                .message(message)
                .build();
    }

    /**
     * 创建需要更多信息的响应
     */
    public static DiagnosisResponse needMoreInfo(String message, String sessionId) {
        return DiagnosisResponse.builder()
                .sessionId(sessionId)
                .status(Status.PARTIAL)
                .message(message)
                .build();
    }

    /**
     * 从诊断结果创建响应
     */
    public static DiagnosisResponse fromDiagnosisResult(
            com.huawei.hisi.agent.orchestrator.DiagnosisResult result, String sessionId) {
        return DiagnosisResponse.builder()
                .sessionId(sessionId)
                .status(result.hasValidConclusion() ? Status.SUCCESS : Status.PARTIAL)
                .conclusion(result.getPrimaryConclusion())
                .rootCause(result.getPrimaryRootCause())
                .confidence(result.getOverallConfidence())
                .fixSuggestions(result.getCombinedFixSuggestions())
                .affectedCode(result.getCombinedAffectedCode())
                .totalTimeMs(result.getTotalTimeMs())
                .message(generateFriendlyMessage(result))
                .build();
    }

    /**
     * 生成友好的消息
     */
    private static String generateFriendlyMessage(com.huawei.hisi.agent.orchestrator.DiagnosisResult result) {
        if (result.hasValidConclusion()) {
            return "分析完成。根据分析，问题的根因可能是：" + result.getPrimaryRootCause();
        }
        return "分析未能确定明确结论，建议提供更多信息或检查相关日志。";
    }

    // ============== 辅助方法 ==============

    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * 是否需要澄清
     */
    public boolean needsClarification() {
        return status == Status.CLARIFICATION_NEEDED;
    }
}