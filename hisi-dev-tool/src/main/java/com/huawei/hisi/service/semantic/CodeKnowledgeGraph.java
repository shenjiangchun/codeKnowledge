package com.huawei.hisi.service.semantic;

import com.huawei.hisi.service.semantic.model.ExceptionNode;
import com.huawei.hisi.service.semantic.model.MethodNode;

import java.util.List;
import java.util.Optional;

/**
 * 代码知识图谱接口
 *
 * 提供代码语义知识图谱的存储和查询能力，支持异常传播路径分析。
 */
public interface CodeKnowledgeGraph {

    /**
     * 根据类名和方法名查找方法节点
     * @param className 类全限定名
     * @param methodName 方法名
     * @return 方法节点，如果不存在则返回Optional.empty()
     */
    Optional<MethodNode> findMethod(String className, String methodName);

    /**
     * 根据节点ID查找方法节点
     * @param nodeId 方法节点ID
     * @return 方法节点
     */
    Optional<MethodNode> findMethodById(String nodeId);

    /**
     * 根据方法签名查找方法节点
     * @param fullSignature 方法完整签名（类名.方法签名）
     * @return 方法节点
     */
    Optional<MethodNode> findMethodBySignature(String fullSignature);

    /**
     * 查找指定异常类型的异常节点
     * @param exceptionType 异常类型全限定名
     * @return 异常节点
     */
    Optional<ExceptionNode> findException(String exceptionType);

    /**
     * 查找抛出指定异常类型的方法列表
     * @param exceptionType 异常类型全限定名
     * @return 抛出该异常的方法节点列表
     */
    List<MethodNode> findMethodsThrowingException(String exceptionType);

    /**
     * 查找指定异常类型在给定位置附近可能的抛出来源
     * @param exceptionType 异常类型
     * @param location 当前位置（类名.方法名格式）
     * @return 可能抛出该异常的方法节点列表
     */
    List<MethodNode> findExceptionSources(String exceptionType, String location);

    /**
     * 查找调用指定方法的所有方法（Caller）
     * @param methodNodeId 方法节点ID
     * @param depth 查询深度（1表示直接调用者，2表示调用者的调用者）
     * @return 调用者方法节点列表
     */
    List<MethodNode> findCallers(String methodNodeId, int depth);

    /**
     * 查找指定方法调用的所有方法（Callee）
     * @param methodNodeId 方法节点ID
     * @param depth 查询深度
     * @return 被调用方法节点列表
     */
    List<MethodNode> findCallees(String methodNodeId, int depth);

    /**
     * 查找从源方法到目标方法的调用路径
     * @param sourceNodeId 源方法节点ID
     * @param targetLocation 目标位置（类名.方法名格式）
     * @return 调用链（方法节点列表）
     */
    List<MethodNode> findCallPath(String sourceNodeId, String targetLocation);

    /**
     * 查找从源方法到目标方法的调用路径（反向：从目标到源）
     * @param targetLocation 目标位置
     * @param sourceNodeId 源方法节点ID
     * @return 反向调用链（从目标到源）
     */
    List<MethodNode> findReverseCallPath(String targetLocation, String sourceNodeId);

    /**
     * 添加方法节点到图谱
     * @param methodNode 方法节点
     */
    void addMethod(MethodNode methodNode);

    /**
     * 添加异常节点到图谱
     * @param exceptionNode 异常节点
     */
    void addException(ExceptionNode exceptionNode);

    /**
     * 添加方法调用关系
     * @param callerNodeId 调用方节点ID
     * @param calleeNodeId 被调用方节点ID
     */
    void addCallRelation(String callerNodeId, String calleeNodeId);

    /**
     * 添加方法抛出异常关系
     * @param methodNodeId 方法节点ID
     * @param exceptionType 异常类型
     */
    void addThrowsRelation(String methodNodeId, String exceptionType);

    /**
     * 获取图谱中所有方法节点数量
     * @return 方法节点数量
     */
    int getMethodCount();

    /**
     * 获取图谱中所有异常节点数量
     * @return 异常节点数量
     */
    int getExceptionCount();

    /**
     * 清空图谱数据
     */
    void clear();

    /**
     * 检查图谱是否为空
     * @return true表示图谱没有任何节点
     */
    boolean isEmpty();
}