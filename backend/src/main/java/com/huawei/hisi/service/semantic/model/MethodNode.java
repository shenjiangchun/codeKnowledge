package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱中的方法节点
 *
 * 表示一个方法在代码知识图谱中的节点信息，用于异常传播路径分析中的图遍历。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodNode {

    /**
     * 方法节点ID（唯一标识）
     */
    private String nodeId;

    /**
     * 方法所属类名（全限定名）
     */
    private String className;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 方法签名
     */
    private String signature;

    /**
     * 方法意图描述
     */
    private String intent;

    /**
     * 方法抛出的异常列表
     */
    private List<String> thrownExceptions;

    /**
     * 方法中catch块捕获的异常类型列表
     * 每个元素代表一个catch块捕获的异常类型
     */
    private List<String> caughtExceptions;

    /**
     * 方法中是否存在嵌套try-catch块
     */
    private boolean hasNestedTryCatch;

    /**
     * 方法调用的其他方法（节点ID列表）
     */
    private List<String> callsMethods;

    /**
     * 调用此方法的其他方法（节点ID列表）
     */
    private List<String> calledByMethods;

    /**
     * 方法所在文件路径
     */
    private String filePath;

    /**
     * 方法起始行号
     */
    private Integer startLineNumber;

    /**
     * 方法结束行号
     */
    private Integer endLineNumber;

    /**
     * 圈复杂度
     */
    private Integer complexity;

    /**
     * 方法分类
     */
    private MethodCategory category;

    /**
     * 扩展属性
     */
    private Map<String, Object> properties;

    /**
     * 获取方法的唯一标识符（className.methodName）
     * @return 方法唯一标识
     */
    public String getMethodKey() {
        return className + "." + methodName;
    }

    /**
     * 判断是否抛出指定类型的异常
     * @param exceptionType 异常类型全限定名
     * @return true表示该方法抛出该异常
     */
    public boolean throwsException(String exceptionType) {
        if (thrownExceptions == null) {
            return false;
        }
        return thrownExceptions.contains(exceptionType);
    }

    /**
     * 判断该方法是否是异常抛出的潜在来源
     * @return true表示该方法抛出至少一个异常
     */
    public boolean isExceptionSource() {
        return thrownExceptions != null && !thrownExceptions.isEmpty();
    }
}