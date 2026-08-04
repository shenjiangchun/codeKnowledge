package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 方法语义信息
 *
 * 包含方法的结构信息和LLM标注的语义信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodSemantic {

    /** 类名 */
    private String className;

    /** 方法名 */
    private String methodName;

    /** 方法签名 */
    private String signature;

    /** 参数列表 */
    private List<ParameterInfo> parameters;

    /** 返回类型 */
    private String returnType;

    /** 方法意图描述（LLM标注） */
    private String intent;

    /** 抛出异常列表 */
    private List<String> exceptions;

    /** 圈复杂度 */
    private int complexity;

    /** 关键词列表（LLM标注） */
    private List<String> keywords;

    /** 方法分类 */
    private MethodCategory category;

    /** 方法源代码 */
    private String sourceCode;

    /** 方法调用列表 */
    private List<String> calledMethods;

    /** 被调用方法列表 */
    private List<String> callingMethods;

    /**
     * 参数信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterInfo {
        /** 参数名 */
        private String name;
        /** 参数类型 */
        private String type;
        /** 是否是泛型 */
        private boolean isGeneric;
    }

    /**
     * 获取方法的唯一标识
     */
    public String getUniqueId() {
        return className + "#" + methodName;
    }

    /**
     * 获取方法的完整签名
     */
    public String getFullSignature() {
        return className + "." + signature;
    }
}