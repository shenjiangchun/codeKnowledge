package com.huawei.hisi.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 方法意图分析结果
 * LLM 分析方法源码后返回的结构化意图描述
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentAnalysisResult {
    /** 方法用途（一句话描述） */
    private String purpose;

    /** 输入参数含义列表 */
    private List<String> inputs;

    /** 返回值含义 */
    private String outputs;

    /** 副作用列表 */
    private List<String> sideEffects;

    /** 可能的错误场景 */
    private List<String> errorConditions;

    /** 分析置信度 (0-1) */
    private Double confidence;

    /** 原始分析方法名 */
    private String methodName;

    /** 原始类名 */
    private String className;

    /** 分析耗时（毫秒） */
    private Long analysisTimeMs;
}