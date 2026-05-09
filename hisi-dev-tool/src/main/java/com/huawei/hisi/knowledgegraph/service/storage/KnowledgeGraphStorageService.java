package com.huawei.hisi.knowledgegraph.service.storage;

import com.huawei.hisi.knowledgegraph.model.ClassExtends;
import com.huawei.hisi.knowledgegraph.model.InterfaceImplementation;
import com.huawei.hisi.knowledgegraph.model.MethodOverride;
import com.huawei.hisi.knowledgegraph.model.ProxyRelation;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱存储服务接口
 * 定义知识图谱数据的存储操作，支持多种存储后端实现
 */
public interface KnowledgeGraphStorageService {

    // ==================== 方法节点操作 ====================

    /**
     * 保存单个方法节点
     */
    void saveMethodNode(MethodNode node);

    /**
     * 批量保存方法节点
     */
    void saveMethodNodes(List<MethodNode> nodes);

    /**
     * 根据项目路径查询方法节点数量
     */
    int countMethodNodes(String projectPath);

    // ==================== 调用关系操作 ====================

    /**
     * 批量保存调用关系
     * @param relations 调用关系列表，每个 Map 包含:
     *                  - callerId: 调用方节点ID
     *                  - calleeId: 被调用方节点ID
     *                  - callType: 调用类型
     *                  - callLine: 调用行号
     *                  - bridgeType: 桥接类型
     *                  - sqlId: SQL ID (Mapper调用)
     *                  - targetService: 目标服务 (Feign调用)
     *                  - targetEndpoint: 目标端点 (MQ/HTTP调用)
     */
    void saveCallRelations(List<Map<String, Object>> relations);

    /**
     * 根据项目路径查询调用关系数量
     */
    int countCallRelations(String projectPath);

    // ==================== 入口点操作 ====================

    /**
     * 保存单个入口点
     */
    void saveEntryPoint(EntryPointNode entry);

    /**
     * 批量保存入口点
     */
    void saveEntryPoints(List<EntryPointNode> entries);

    /**
     * 根据项目路径查询入口点数量
     */
    int countEntryPoints(String projectPath);

    // ==================== 接口实现操作 ====================

    /**
     * 保存接口实现关系
     */
    void saveInterfaceImplementation(InterfaceImplementation impl);

    /**
     * 批量保存接口实现关系
     */
    void saveInterfaceImplementations(List<InterfaceImplementation> impls);

    /**
     * 根据项目路径查询接口实现数量
     */
    int countInterfaceImplementations(String projectPath);

    // ==================== 类继承关系操作 ====================

    /**
     * 保存类继承关系
     */
    void saveClassExtends(ClassExtends extendsRelation);

    /**
     * 批量保存类继承关系
     */
    void saveClassExtends(List<ClassExtends> extendsRelations);

    /**
     * 根据项目路径查询类继承关系数量
     */
    int countClassExtends(String projectPath);

    // ==================== 方法重写关系操作 ====================

    /**
     * 保存方法重写关系
     */
    void saveMethodOverride(MethodOverride overrideRelation);

    /**
     * 批量保存方法重写关系
     */
    void saveMethodOverrides(List<MethodOverride> overrideRelations);

    /**
     * 根据项目路径查询方法重写关系数量
     */
    int countMethodOverrides(String projectPath);

    // ==================== 代理类关系操作 ====================

    /**
     * 保存代理类关系
     */
    void saveProxyRelation(ProxyRelation proxyRelation);

    /**
     * 批量保存代理类关系
     */
    void saveProxyRelations(List<ProxyRelation> proxyRelations);

    /**
     * 根据项目路径查询代理类关系数量
     */
    int countProxyRelations(String projectPath);

    // ==================== 数据清理操作 ====================

    /**
     * 清理项目的所有知识图谱数据
     */
    void cleanProjectData(String projectPath);

}
