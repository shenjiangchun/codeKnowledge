package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱中的异常节点
 *
 * 表示一个异常类型在代码知识图谱中的节点信息，包含异常属性、抛出方法等关联信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExceptionNode {

    /**
     * 异常节点ID（唯一标识）
     */
    private String nodeId;

    /**
     * 异常类型全限定名（如 java.lang.NullPointerException）
     */
    private String exceptionType;

    /**
     * 异常简单名称（如 NullPointerException）
     */
    private String simpleName;

    /**
     * 异常包名（如 java.lang）
     */
    private String packageName;

    /**
     * 异常分类（Checked/Unchecked/Runtime/Error）
     */
    private ExceptionCategory category;

    /**
     * 异常的描述/文档摘要
     */
    private String description;

    /**
     * 抛出此异常的方法列表
     */
    private List<String> thrownByMethods;

    /**
     * 捕获此异常的方法列表
     */
    private List<String> caughtByMethods;

    /**
     * 此异常的父类异常（继承关系）
     */
    private String parentExceptionType;

    /**
     * 此异常的子类异常列表
     */
    private List<String> childExceptionTypes;

    /**
     * 扩展属性（可存储向量嵌入、标签等）
     */
    private Map<String, Object> properties;

    /**
     * 异常分类枚举
     */
    public enum ExceptionCategory {
        /**
         * 受检异常（必须显式处理）
         */
        CHECKED,

        /**
         * 非受检异常（RuntimeException子类）
         */
        UNCHECKED,

        /**
         * 运行时异常
         */
        RUNTIME,

        /**
         * 错误（Error子类）
         */
        ERROR,

        /**
         * 自定义异常
         */
        CUSTOM
    }

    /**
     * 判断是否为受检异常
     * @return true表示必须显式声明或处理
     */
    public boolean isCheckedException() {
        return category == ExceptionCategory.CHECKED;
    }

    /**
     * 判断是否为运行时异常
     * @return true表示RuntimeException子类
     */
    public boolean isRuntimeException() {
        return category == ExceptionCategory.RUNTIME || category == ExceptionCategory.UNCHECKED;
    }

    /**
     * 判断是否为错误类型
     * @return true表示Error子类
     */
    public boolean isError() {
        return category == ExceptionCategory.ERROR;
    }

    /**
     * 获取完整异常名称
     * @return 包名+简单名称
     */
    public String getFullName() {
        if (packageName != null && !packageName.isEmpty()) {
            return packageName + "." + simpleName;
        }
        return exceptionType;
    }
}