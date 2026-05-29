package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM意图标注结果
 *
 * 封装LLM对方法意图的标注信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentAnnotationResult {

    /** 方法意图描述 */
    private String intent;

    /** 关键词列表 */
    private List<String> keywords;

    /** 方法分类 */
    private MethodCategory category;

    /** 是否成功标注 */
    private boolean success;

    /** 错误信息（如果标注失败） */
    private String errorMessage;

    /** 标注耗时（毫秒） */
    private long annotationTimeMs;

    /**
     * 创建失败的标注结果
     */
    public static IntentAnnotationResult failure(String errorMessage) {
        return IntentAnnotationResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .intent("无法解析")
                .keywords(List.of())
                .category(MethodCategory.OTHER)
                .build();
    }

    /**
     * 创建成功的标注结果
     */
    public static IntentAnnotationResult success(String intent, List<String> keywords, MethodCategory category, long annotationTimeMs) {
        return IntentAnnotationResult.builder()
                .success(true)
                .intent(intent)
                .keywords(keywords)
                .category(category)
                .annotationTimeMs(annotationTimeMs)
                .build();
    }
}