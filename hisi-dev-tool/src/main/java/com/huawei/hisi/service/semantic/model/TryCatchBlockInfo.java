package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Try-Catch块信息
 *
 * 用于记录代码中try-catch块的结构信息，支持异常传播路径分析。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TryCatchBlockInfo {

    /**
     * Try块所在方法节点ID
     */
    private String methodNodeId;

    /**
     * Try块起始行号
     */
    private Integer tryStartLine;

    /**
     * Try块结束行号
     */
    private Integer tryEndLine;

    /**
     * Catch块列表
     */
    private List<CatchClauseInfo> catchClauses;

    /**
     * 是否有finally块
     */
    private Boolean hasFinally;

    /**
     * Finally块起始行号
     */
    private Integer finallyStartLine;

    /**
     * Finally块结束行号
     */
    private Integer finallyEndLine;

    /**
     * Try块内调用的方法列表
     */
    private List<String> tryBlockMethodCalls;

    /**
     * 扩展属性
     */
    private Map<String, Object> properties;

    /**
     * Catch块信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CatchClauseInfo {
        /**
         * 捕获的异常类型全限定名
         */
        private String exceptionType;

        /**
         * 捕获的异常变量名
         */
        private String exceptionVariable;

        /**
         * Catch块起始行号
         */
        private Integer catchStartLine;

        /**
         * Catch块结束行号
         */
        private Integer catchEndLine;

        /**
         * Catch块中的处理行为
         * - RETHROW: 重新抛出
         * - LOG: 记录日志
         * - IGNORE: 忽略
         * - WRAP: 包装成其他异常
         * - HANDLE: 完全处理
         */
        private CatchBehavior behavior;

        /**
         * 如果是WRAP，包装后的异常类型
         */
        private String wrappedExceptionType;
    }

    /**
     * Catch块处理行为枚举
     */
    public enum CatchBehavior {
        /**
         * 重新抛出异常
         */
        RETHROW,

        /**
         * 记录日志后继续
         */
        LOG,

        /**
         * 忽略异常
         */
        IGNORE,

        /**
         * 包装成其他异常抛出
         */
        WRAP,

        /**
         * 完全处理异常（不传播）
         */
        HANDLE,

        /**
         * 未确定的行为
         */
        UNKNOWN
    }

    /**
     * 判断是否捕获指定类型的异常
     * @param exceptionType 异常类型
     * @return true表示存在捕获该类型的catch块
     */
    public boolean catchesException(String exceptionType) {
        if (catchClauses == null) {
            return false;
        }
        return catchClauses.stream()
                .anyMatch(c -> c.getExceptionType().equals(exceptionType) ||
                        isSubtypeOf(exceptionType, c.getExceptionType()));
    }

    /**
     * 简单的异常继承关系判断（需要知识图谱支持完整判断）
     */
    private boolean isSubtypeOf(String child, String parent) {
        // 简化判断：RuntimeException是Exception子类
        if ("java.lang.Exception".equals(parent)) {
            return child.startsWith("java.lang.Runtime") ||
                    child.startsWith("java.lang.");
        }
        return false;
    }

    /**
     * 获取指定异常类型的Catch行为
     * @param exceptionType 异常类型
     * @return Catch行为，如果未捕获则返回null
     */
    public CatchBehavior getCatchBehavior(String exceptionType) {
        if (catchClauses == null) {
            return null;
        }
        return catchClauses.stream()
                .filter(c -> c.getExceptionType().equals(exceptionType))
                .findFirst()
                .map(CatchClauseInfo::getBehavior)
                .orElse(null);
    }
}