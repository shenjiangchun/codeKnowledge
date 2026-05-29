package com.huawei.hisi.service.intent;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * 意图识别结果
 * 封装意图解析后的所有信息
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResult {

    /**
     * 识别出的意图类型
     */
    private IntentType intent;

    /**
     * 置信度 (0.0 - 1.0)
     * - > 0.7: 高置信度，可直接执行
     * - 0.3 ~ 0.7: 中等置信度，建议确认
     * - < 0.3: 低置信度，需要澄清
     */
    private double confidence;

    /**
     * 提取的关键实体
     * 包括 errorType, className, methodName, focusArea 等
     */
    @Builder.Default
    private Map<String, String> entities = new HashMap<>();

    /**
     * 需要澄清的问题列表
     * 当置信度过低或信息不完整时，需要向用户提问
     */
    @Builder.Default
    private List<String> clarificationQuestions = new ArrayList<>();

    /**
     * 是否需要用户澄清
     */
    @Builder.Default
    private boolean needClarification = false;

    /**
     * 原始用户输入
     */
    private String originalInput;

    /**
     * 识别方法（RULE 或 LLM）
     */
    private String recognitionMethod;

    /**
     * 识别耗时（毫秒）
     */
    private long recognitionTimeMs;

    // ============== 实体提取便捷方法 ==============

    /**
     * 获取错误类型
     */
    public String getErrorType() {
        return entities != null ? entities.get("errorType") : null;
    }

    /**
     * 获取类名
     */
    public String getClassName() {
        return entities != null ? entities.get("className") : null;
    }

    /**
     * 获取方法名
     */
    public String getMethodName() {
        return entities != null ? entities.get("methodName") : null;
    }

    /**
     * 获取关注区域
     */
    public String getFocusArea() {
        return entities != null ? entities.get("focusArea") : null;
    }

    /**
     * 获取文件路径
     */
    public String getFilePath() {
        return entities != null ? entities.get("filePath") : null;
    }

    /**
     * 添加实体
     */
    public void addEntity(String key, String value) {
        if (entities == null) {
            entities = new HashMap<>();
        }
        entities.put(key, value);
    }

    /**
     * 添加澄清问题
     */
    public void addClarificationQuestion(String question) {
        if (clarificationQuestions == null) {
            clarificationQuestions = new ArrayList<>();
        }
        clarificationQuestions.add(question);
    }

    // ============== 状态判断方法 ==============

    /**
     * 是否为高置信度结果
     */
    public boolean isHighConfidence() {
        return confidence >= 0.7;
    }

    /**
     * 是否为低置信度结果
     */
    public boolean isLowConfidence() {
        return confidence < 0.3;
    }

    /**
     * 是否可直接执行
     */
    public boolean canExecuteDirectly() {
        return intent != IntentType.UNKNOWN && isHighConfidence() && !needClarification;
    }

    /**
     * 是否需要确认
     */
    public boolean needsConfirmation() {
        return confidence >= 0.3 && confidence < 0.7;
    }

    /**
     * 判断意图是否有效
     */
    public boolean isValidIntent() {
        return intent != null && intent != IntentType.UNKNOWN;
    }

    // ============== 静态工厂方法 ==============

    /**
     * 创建未知意图结果
     */
    public static IntentResult unknown(String originalInput) {
        return IntentResult.builder()
                .intent(IntentType.UNKNOWN)
                .confidence(0.0)
                .originalInput(originalInput)
                .needClarification(true)
                .clarificationQuestions(List.of("我不太理解您的意思，请描述您遇到的问题或需要查询的内容"))
                .build();
    }

    /**
     * 创建高置信度结果
     */
    public static IntentResult highConfidence(IntentType intent, Map<String, String> entities, String originalInput) {
        return IntentResult.builder()
                .intent(intent)
                .confidence(0.85)
                .entities(entities)
                .originalInput(originalInput)
                .needClarification(false)
                .build();
    }

    /**
     * 创建默认诊断意图结果（IntentParserService removed）
     */
    public static IntentResult defaultResult(String originalInput) {
        return IntentResult.builder()
                .intent(IntentType.DIAGNOSE_LOG)
                .confidence(0.5)
                .originalInput(originalInput)
                .recognitionMethod("DEFAULT")
                .needClarification(false)
                .build();
    }
}