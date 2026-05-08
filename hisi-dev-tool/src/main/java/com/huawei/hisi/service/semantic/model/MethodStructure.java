package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 方法结构信息
 *
 * JavaParser AST解析后的结构化信息，不包含语义信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodStructure {

    /** 方法名 */
    private String methodName;

    /** 方法签名（包含参数类型） */
    private String signature;

    /** 返回类型 */
    private String returnType;

    /** 参数列表 */
    private List<MethodSemantic.ParameterInfo> parameters;

    /** 抛出异常列表 */
    private List<String> thrownExceptions;

    /** 圈复杂度 */
    private int cyclomaticComplexity;

    /** 方法体行数 */
    private int bodyLineCount;

    /** 方法内调用的方法 */
    private List<MethodCallInfo> methodCalls;

    /** 是否是静态方法 */
    private boolean isStatic;

    /** 是否是public方法 */
    private boolean isPublic;

    /** 是否是构造方法 */
    private boolean isConstructor;

    /** 注解列表 */
    private List<String> annotations;

    /**
     * 方法调用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodCallInfo {
        /** 被调用的类名 */
        private String targetClassName;
        /** 被调用的方法名 */
        private String targetMethodName;
        /** 调用位置（行号） */
        private int lineNumber;
    }
}