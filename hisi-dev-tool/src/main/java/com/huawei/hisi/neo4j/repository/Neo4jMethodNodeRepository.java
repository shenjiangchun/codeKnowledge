package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.VectorSearchResult;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 方法节点 Repository (Neo4j)
 * 提供基础 CRUD 和自定义查询方法
 */
@Repository
public interface Neo4jMethodNodeRepository extends Neo4jRepository<MethodNode, String> {

    /**
     * 根据节点ID查询
     */
    Optional<MethodNode> findByNodeId(String nodeId);

    /**
     * 批量根据节点ID查询
     * 用于调用链图构建时一次性获取所有方法节点
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.nodeId IN $nodeIds
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description
        """)
    List<MethodNode> findAllByNodeIds(@Param("nodeIds") List<String> nodeIds);

    /**
     * 根据项目路径查询所有方法节点
     */
    List<MethodNode> findByProjectPath(String projectPath);

    /**
     * 根据项目路径查询所有方法节点（不加载关系，仅用于批量处理）
     * 使用投影避免加载 CALLS 关系，大幅提升查询性能
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               m.descriptionEmbedding as descriptionEmbedding, m.codeEmbedding as codeEmbedding
        """)
    List<MethodNode> findByProjectPathWithoutRelationships(@Param("projectPath") String projectPath);

    /**
     * 根据服务名查询所有方法节点
     */
    List<MethodNode> findByServiceName(String serviceName);

    /**
     * 根据类名查询方法节点
     */
    List<MethodNode> findByClassName(String className);

    /**
     * 根据方法名查询方法节点
     */
    List<MethodNode> findByMethodName(String methodName);

    /**
     * 根据项目路径和类名查询
     */
    List<MethodNode> findByProjectPathAndClassName(String projectPath, String className);

    /**
     * 删除项目下的所有方法节点
     * 使用 DETACH DELETE 同时删除关联的 CALLS 关系
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        DETACH DELETE m
        """)
    void deleteByProjectPath(String projectPath);

    /**
     * 统计项目下的方法节点数量
     */
    long countByProjectPath(String projectPath);

    /**
     * [批量聚合] 按多项目路径列表统计方法节点数量（Cypher IN）。
     * 用于多项目场景下一次性聚合多个项目的方法节点总数。
     * 调用方需先用 PathUtils.normalize 把所有路径规范化为正斜杠格式。
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
        RETURN count(m)
        """)
    long countByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * [诊断] 统计项目下拥有 descriptionEmbedding 的方法节点数量
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.descriptionEmbedding IS NOT NULL
        RETURN count(m)
        """)
    long countByProjectPathWithDescriptionEmbedding(@Param("projectPath") String projectPath);

    /**
     * [诊断] 不设阈值，返回 descriptionEmbedding 向量索引检索的 topK 真实分数
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_description_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath = $projectPath
        RETURN m.className as className, m.methodName as methodName, m.description as description, score
        ORDER BY score DESC
        """)
    List<Map<String, Object>> diagnosticTopScoresByDescription(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("topK") int topK
    );

    /**
     * [诊断] 检查数据库中实际存储的向量维度
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.descriptionEmbedding IS NOT NULL
        RETURN m.className as className, m.methodName as methodName,
               size(m.descriptionEmbedding) as dimension
        LIMIT 10
        """)
    List<Map<String, Object>> diagnosticCheckVectorDimensions(
        @Param("projectPath") String projectPath
    );

    /**
     * [诊断] 检查向量索引的配置信息
     */
    @Query("""
        SHOW INDEXES YIELD name, type, options
        WHERE name IN ['method_description_vector_index', 'method_code_vector_index', 'sql_vector_index']
        RETURN name, type, options
        """)
    List<Map<String, Object>> diagnosticCheckVectorIndexes();

    /**
     * [诊断] 不使用向量索引，直接扫描节点计算相似度（用于对比）
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.descriptionEmbedding IS NOT NULL
        WITH m, vector.similarity.cosine(m.descriptionEmbedding, $embedding) AS score
        WHERE score >= $threshold
        RETURN m.className as className, m.methodName as methodName, m.description as description, score
        ORDER BY score DESC
        LIMIT $topK
        """)
    List<Map<String, Object>> diagnosticDirectSimilaritySearch(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 查询调用者 - 谁调用了这个方法
     * 返回所有调用指定方法的节点
     */
    @Query("""
        MATCH (caller:Method)-[:CALLS]->(callee:Method {nodeId: $nodeId})
        RETURN caller
        """)
    List<MethodNode> findCallers(@Param("nodeId") String nodeId);

    /**
     * 查询被调用者 - 这个方法调用了哪些方法
     * 返回指定方法调用的所有节点
     */
    @Query("""
        MATCH (caller:Method {nodeId: $nodeId})-[:CALLS]->(callee:Method)
        RETURN callee
        """)
    List<MethodNode> findCallees(@Param("nodeId") String nodeId);

    /**
     * 查询调用链 - 向上追溯N层
     * 注意：Neo4j 不允许在变长关系长度中使用参数（包括 SpEL 转出的参数），
     *       因此固定上限为 10，再用 WHERE length(path) <= $depth 过滤实际深度。
     */
    @Query("""
        MATCH path = (caller:Method)-[:CALLS*1..10]->(callee:Method {nodeId: $nodeId})
        WHERE length(path) <= $depth
        RETURN DISTINCT caller
        """)
    List<MethodNode> findCallersUpToDepth(@Param("nodeId") String nodeId, @Param("depth") int depth);

    /**
     * 查询调用链 - 向下追溯N层
     * 注意：Neo4j 不允许在变长关系长度中使用参数（包括 SpEL 转出的参数），
     *       因此固定上限为 10，再用 WHERE length(path) <= $depth 过滤实际深度。
     */
    @Query("""
        MATCH path = (caller:Method {nodeId: $nodeId})-[:CALLS*1..10]->(callee:Method)
        WHERE length(path) <= $depth
        RETURN DISTINCT callee
        """)
    List<MethodNode> findCalleesUpToDepth(@Param("nodeId") String nodeId, @Param("depth") int depth);

    /**
     * 跨项目下游调用链 — 遍历 CALLS 关系（包含同项目内部调用和跨服务 EXTERNAL_CALL）
     * 用于多项目模式下追踪跨服务调用
     */
    @Query("""
        MATCH path = (start:Method {nodeId: $nodeId})-[:CALLS*1..10]->(end:Method)
        WHERE length(path) <= $depth
        RETURN DISTINCT end
        """)
    List<MethodNode> findDownstreamCrossService(@Param("nodeId") String nodeId, @Param("depth") int depth);

    /**
     * 跨项目上游调用链 — 遍历 CALLS 关系（包含同项目内部调用和跨服务 EXTERNAL_CALL）
     * 用于多项目模式下追踪跨服务调用
     */
    @Query("""
        MATCH path = (start:Method)-[:CALLS*1..10]->(end:Method {nodeId: $nodeId})
        WHERE length(path) <= $depth
        RETURN DISTINCT start
        """)
    List<MethodNode> findUpstreamCrossService(@Param("nodeId") String nodeId, @Param("depth") int depth);

    /**
     * 查询入口点的完整调用链
     * EntryPoint通过methodNodeId属性关联Method节点
     */
    @Query("""
        MATCH (entry:EntryPoint {entryId: $entryId})
        WITH entry.methodNodeId as entryMethodId
        MATCH (entryMethod:Method {nodeId: entryMethodId})
        MATCH path = (entryMethod)-[:CALLS*0..]->(method:Method)
        RETURN DISTINCT method
        """)
    List<MethodNode> findMethodsByEntryPoint(@Param("entryId") String entryId);

    /**
     * 根据方法名模糊查询
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.methodName CONTAINS $keyword
        RETURN m
        """)
    List<MethodNode> findByMethodNameContaining(@Param("keyword") String keyword);

    /**
     * 批量创建调用关系
     * 使用 UNWIND 批量创建 CALLS 关系
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (caller:Method {nodeId: rel.callerId})
        MATCH (callee:Method {nodeId: rel.calleeId})
        MERGE (caller)-[r:CALLS]->(callee)
        SET r.callType = rel.callType,
            r.callLine = rel.callLine,
            r.bridgeType = rel.bridgeType,
            r.sqlId = rel.sqlId,
            r.targetService = rel.targetService,
            r.targetEndpoint = rel.targetEndpoint
        """)
    void createCallRelations(@Param("relations") List<Map<String, Object>> relations);

    // ==================== 接口实现关系 (IMPLEMENTS) ====================

    /**
     * 批量创建 IMPLEMENTS 关系
     * 使用 UNWIND 批量创建接口实现关系
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (impl:Method)
        WHERE impl.className = rel.implementationName
          AND impl.projectPath IN $projectPaths
        MATCH (iface:Method)
        WHERE iface.className = rel.interfaceName
          AND iface.projectPath IN $projectPaths
          AND iface.methodName = impl.methodName
        WITH impl, iface, rel
        MERGE (impl)-[r:IMPLEMENTS]->(iface)
        SET r.projectPath = rel.projectPath, r.implType = rel.implType
        """)
    void createImplementsRelations(
        @Param("relations") List<Map<String, Object>> relations,
        @Param("projectPaths") List<String> projectPaths);

    /**
     * 统计项目的 IMPLEMENTS 关系数量
     */
    @Query("""
        MATCH (:Method)-[r:IMPLEMENTS]->(:Method)
        WHERE r.projectPath = $projectPath
        RETURN count(r)
        """)
    int countImplementsRelations(@Param("projectPath") String projectPath);

    /**
     * 查询接口的所有实现类
     */
    @Query("""
        MATCH (impl:Method)-[:IMPLEMENTS]->(iface:Method {className: $interfaceName})
        WHERE impl.projectPath IN $projectPaths
        RETURN DISTINCT impl.className AS implementationName
        """)
    List<String> findImplementationsByInterface(
        @Param("interfaceName") String interfaceName,
        @Param("projectPaths") List<String> projectPaths);

    /**
     * 查找实现某接口方法的所有实现类方法 nodeId
     * 用于调用链遍历：从接口方法跳转到实现类方法
     */
    @Query("""
        MATCH (impl:Method)-[:IMPLEMENTS]->(iface:Method {nodeId: $ifaceNodeId})
        RETURN impl.nodeId AS implNodeId
        """)
    List<String> findImplementationMethodsByInterfaceMethod(
        @Param("ifaceNodeId") String ifaceNodeId);

    /**
     * 查找实现某接口方法的 LOCAL 实现类方法 nodeId（排除 Feign 代理）
     * 用于调用链遍历：优先选择本地实现，避免通过 Feign 代理展开
     */
    @Query("""
        MATCH (impl:Method)-[r:IMPLEMENTS]->(iface:Method {nodeId: $ifaceNodeId})
        WHERE coalesce(r.implType, 'LOCAL') = 'LOCAL'
        RETURN impl.nodeId AS implNodeId
        """)
    List<String> findLocalImplementationMethods(@Param("ifaceNodeId") String ifaceNodeId);

    /**
     * 下游 Feign 桥接：从 FEIGN_PROXY 节点出发，通过共享接口找到 LOCAL 兄弟实现
     * 用于调用链遍历：当遇到 FeignClient 方法（无 CALLS 出边）时，
     * 两跳穿透到 ServiceImpl 方法继续遍历
     */
    @Query("""
        MATCH (feign:Method {nodeId: $nodeId})-[r1:IMPLEMENTS]->(iface:Method)
        WHERE r1.implType = 'FEIGN_PROXY'
        MATCH (local:Method)-[r2:IMPLEMENTS]->(iface)
        WHERE coalesce(r2.implType, 'LOCAL') = 'LOCAL'
          AND local.nodeId <> $nodeId
        RETURN local.nodeId AS implNodeId, iface.nodeId AS ifaceNodeId
        """)
    List<FeignBridgeTarget> findFeignBridgeTargets(@Param("nodeId") String nodeId);

    /**
     * 上游 Feign 桥接：从 LOCAL 实现出发，通过共享接口找到 FEIGN_PROXY 兄弟
     * 用于上游查询：从 ServiceImpl 反向穿透找到 FeignClient，继续上溯到 gw
     */
    @Query("""
        MATCH (local:Method {nodeId: $nodeId})-[r1:IMPLEMENTS]->(iface:Method)
        WHERE coalesce(r1.implType, 'LOCAL') = 'LOCAL'
        MATCH (feign:Method)-[r2:IMPLEMENTS]->(iface)
        WHERE r2.implType = 'FEIGN_PROXY'
          AND feign.nodeId <> $nodeId
        RETURN feign.nodeId AS feignNodeId, iface.nodeId AS ifaceNodeId
        """)
    List<FeignBridgeCaller> findFeignBridgeCallers(@Param("nodeId") String nodeId);

    // ==================== Dispatch 边物化（KG 生成阶段） ====================

    /**
     * 基于 IMPLEMENTS 边创建 IMPL_DISPATCH 类型的 CALLS 边。
     * 方向: (接口方法) -[:CALLS {callType:'IMPL_DISPATCH'}]-> (实现类方法)
     * 这样下游遍历直接跟 CALLS 边即可，无需 fallback 逻辑。
     * 对 FEIGN_PROXY 实现单独标记为 IMPL_DISPATCH_FEIGN。
     */
    @Query("""
        MATCH (impl:Method)-[r:IMPLEMENTS]->(iface:Method)
        WHERE r.projectPath = $projectPath
        MERGE (iface)-[c:CALLS]->(impl)
        ON CREATE SET c.callType = CASE
            WHEN r.implType = 'FEIGN_PROXY' THEN 'IMPL_DISPATCH_FEIGN'
            ELSE 'IMPL_DISPATCH'
          END,
          c.callLine = 0,
          c.projectPath = r.projectPath
        """)
    void createImplDispatchEdges(@Param("projectPath") String projectPath);

    /**
     * 基于 IMPLEMENTS 边创建 FEIGN_BRIDGE 类型的 CALLS 边。
     * 方向: (FeignClient方法) -[:CALLS {callType:'FEIGN_BRIDGE'}]-> (ServiceImpl方法)
     * 通过共享接口方法桥接: feign -[:IMPLEMENTS {FEIGN_PROXY}]-> iface <-[:IMPLEMENTS {LOCAL}]- local
     * 结果: feign → local 直连 CALLS 边，下游遍历自动穿透微服务边界。
     */
    @Query("""
        MATCH (feign:Method)-[r1:IMPLEMENTS]->(iface:Method)
        WHERE r1.implType = 'FEIGN_PROXY' AND r1.projectPath = $projectPath
        MATCH (local:Method)-[r2:IMPLEMENTS]->(iface)
        WHERE coalesce(r2.implType, 'LOCAL') = 'LOCAL'
          AND local.nodeId <> feign.nodeId
        MERGE (feign)-[c:CALLS]->(local)
        ON CREATE SET c.callType = 'FEIGN_BRIDGE',
          c.callLine = 0,
          c.bridgeType = 'FEIGN',
          c.projectPath = r1.projectPath
        """)
    void createFeignBridgeEdges(@Param("projectPath") String projectPath);

    /**
     * 按 callType 统计 CALLS 边数量
     * 用于验证 dispatch 边创建结果
     */
    @Query("""
        MATCH (:Method)-[r:CALLS]->(:Method)
        WHERE r.projectPath = $projectPath AND r.callType = $callType
        RETURN COUNT(r)
        """)
    long countCallsByType(@Param("projectPath") String projectPath, @Param("callType") String callType);

    /**
     * 统计项目的调用关系数量
     */
    @Query("""
        MATCH (:Method {projectPath: $projectPath})-[r:CALLS]->(:Method)
        RETURN COUNT(r)
        """)
    long countCallRelationsByProjectPath(@Param("projectPath") String projectPath);


    /**
     * 更新方法描述
     */
    @Query("""
        MATCH (m:Method {nodeId: $nodeId})
        SET m.description = $description
        """)
    void updateDescription(@Param("nodeId") String nodeId, @Param("description") String description);

    /**
     * 更新方法描述和双向量（描述向量 + 代码向量）
     * 用于新的多向量搜索架构
     */
    @Query("""
        MATCH (m:Method {nodeId: $nodeId})
        SET m.description = $description,
            m.descriptionEmbedding = $descriptionEmbedding,
            m.codeEmbedding = $codeEmbedding
        """)
    void updateDescriptionAndCodeEmbedding(
        @Param("nodeId") String nodeId,
        @Param("description") String description,
        @Param("descriptionEmbedding") List<Double> descriptionEmbedding,
        @Param("codeEmbedding") List<Double> codeEmbedding
    );

    /**
     * 描述向量相似度查询 (使用余弦相似度)
     * 用于自然语言搜索
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath = $projectPath AND m.descriptionEmbedding IS NOT NULL AND size(m.descriptionEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.descriptionEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodNode> findByDescriptionVectorSimilarity(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 描述向量相似度查询 (带分数返回)
     * 用于自然语言搜索，返回节点和相似度分数
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath = $projectPath AND m.descriptionEmbedding IS NOT NULL AND size(m.descriptionEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.descriptionEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               similarity as score
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodWithScore> findByDescriptionVectorSimilarityWithScore(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 代码向量相似度查询 (使用余弦相似度)
     * 用于代码片段搜索
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath = $projectPath AND m.codeEmbedding IS NOT NULL AND size(m.codeEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.codeEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodNode> findByCodeVectorSimilarity(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 代码向量相似度查询 (带分数返回)
     * 用于代码片段搜索，返回节点和相似度分数
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath = $projectPath AND m.codeEmbedding IS NOT NULL AND size(m.codeEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.codeEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               similarity as score
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodWithScore> findByCodeVectorSimilarityWithScore(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 清除项目的所有描述和向量
     * 用于全量重新生成
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        SET m.description = null,
            m.descriptionEmbedding = null, m.codeEmbedding = null
        RETURN count(m) as clearedCount
        """)
    long clearDescriptionsAndEmbeddings(@Param("projectPath") String projectPath);

    /**
     * 统计项目的描述数量
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.description IS NOT NULL
        RETURN count(m)
        """)
    long countWithDescription(@Param("projectPath") String projectPath);

    /**
     * 统计项目的描述向量数量
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.descriptionEmbedding IS NOT NULL
        RETURN count(m)
        """)
    long countWithDescriptionEmbedding(@Param("projectPath") String projectPath);

    /**
     * 统计项目的代码向量数量
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.codeEmbedding IS NOT NULL
        RETURN count(m)
        """)
    long countWithCodeEmbedding(@Param("projectPath") String projectPath);

    /**
     * 统计项目中缺失描述向量的方法数量
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.descriptionEmbedding IS NULL
        RETURN count(m)
        """)
    long countMissingDescriptionEmbedding(@Param("projectPath") String projectPath);

    /**
     * 查询项目中缺失描述向量的方法预览列表（最多返回 limit 条）
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.descriptionEmbedding IS NULL
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName, m.signature as signature
        LIMIT $limit
        """)
    List<Map<String, Object>> findMissingDescriptionEmbedding(@Param("projectPath") String projectPath, @Param("limit") int limit);

    /**
     * 查询带关系属性的调用者
     * 返回调用者和关系属性
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method {nodeId: $nodeId})
        RETURN caller.nodeId as callerId, caller.className as callerClassName,
               caller.methodName as callerMethodName, caller.signature as callerSignature,
               r.callType as callType, r.callLine as callLine, r.bridgeType as bridgeType,
               r.sqlId as sqlId, r.targetService as targetService, r.targetEndpoint as targetEndpoint
        """)
    List<CallerWithRelation> findCallersWithRelation(@Param("nodeId") String nodeId);

    /**
     * 查询带关系属性的被调用者
     * 返回被调用者和关系属性
     */
    @Query("""
        MATCH (caller:Method {nodeId: $nodeId})-[r:CALLS]->(callee:Method)
        RETURN callee.nodeId as calleeId, callee.className as calleeClassName,
               callee.methodName as calleeMethodName, callee.signature as calleeSignature,
               r.callType as callType, r.callLine as callLine, r.bridgeType as bridgeType,
               r.sqlId as sqlId, r.targetService as targetService, r.targetEndpoint as targetEndpoint
        """)
    List<CalleeWithRelation> findCalleesWithRelation(@Param("nodeId") String nodeId);

    /**
     * 查询项目的所有调用关系（带属性）
     */
    @Query("""
        MATCH (caller:Method {projectPath: $projectPath})-[r:CALLS]->(callee:Method)
        RETURN caller.nodeId as callerId, caller.className as callerClassName,
               caller.methodName as callerMethodName,
               callee.nodeId as calleeId, callee.className as calleeClassName,
               callee.methodName as calleeMethodName,
               r.callType as callType, r.callLine as callLine, r.bridgeType as bridgeType,
               r.sqlId as sqlId, r.targetService as targetService, r.targetEndpoint as targetEndpoint
        """)
    List<CallRelationWithNodes> findAllCallRelationsByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 按 bridgeType 统计调用关系数量
     */
    @Query("""
        MATCH (:Method {projectPath: $projectPath})-[r:CALLS]->(:Method)
        WHERE r.bridgeType = $bridgeType
        RETURN COUNT(r)
        """)
    long countByBridgeType(@Param("projectPath") String projectPath, @Param("bridgeType") String bridgeType);

    /**
     * 按 bridgeType 查询调用关系
     */
    @Query("""
        MATCH (caller:Method {projectPath: $projectPath})-[r:CALLS]->(callee:Method)
        WHERE r.bridgeType = $bridgeType
        RETURN caller.nodeId as callerId, caller.className as callerClassName,
               caller.methodName as callerMethodName,
               callee.nodeId as calleeId, callee.className as calleeClassName,
               callee.methodName as calleeMethodName,
               r.callType as callType, r.callLine as callLine, r.bridgeType as bridgeType,
               r.sqlId as sqlId, r.targetService as targetService, r.targetEndpoint as targetEndpoint
        """)
    List<CallRelationWithNodes> findByBridgeType(@Param("projectPath") String projectPath, @Param("bridgeType") String bridgeType);

    /**
     * 根据 nodeId 删除方法节点
     */
    @Query("""
        MATCH (m:Method {nodeId: $nodeId})
        DETACH DELETE m
        """)
    void deleteByNodeId(@Param("nodeId") String nodeId);

    /**
     * 根据 filePath 和 projectPath 范围删除方法节点（用于增量刷新）
     * 使用 DETACH DELETE 同时移除节点及其所有关系（含向量等节点属性）
     */
    @Query("""
        MATCH (n:Method)
        WHERE n.filePath = $filePath
          AND n.projectPath = $projectPath
        DETACH DELETE n
        """)
    void detachDeleteByFilePathAndProjectPath(@Param("filePath") String filePath, @Param("projectPath") String projectPath);

    /**
     * 调用关系数据传输对象
     */
    record CallRelationData(
        String callerId,
        String calleeId,
        String callType,
        Integer callLine,
        String bridgeType,
        String sqlId,
        String targetService,
        String targetEndpoint
    ) {}

    /**
     * 调用者+关系 DTO
     */
    record CallerWithRelation(
        String callerId,
        String callerClassName,
        String callerMethodName,
        String callerSignature,
        String callType,
        Integer callLine,
        String bridgeType,
        String sqlId,
        String targetService,
        String targetEndpoint
    ) {}

    /**
     * 被调用者+关系 DTO
     */
    record CalleeWithRelation(
        String calleeId,
        String calleeClassName,
        String calleeMethodName,
        String calleeSignature,
        String callType,
        Integer callLine,
        String bridgeType,
        String sqlId,
        String targetService,
        String targetEndpoint
    ) {}

    /**
     * 完整调用关系 DTO（包含两端节点信息）
     */
    record CallRelationWithNodes(
        String callerId,
        String callerClassName,
        String callerMethodName,
        String calleeId,
        String calleeClassName,
        String calleeMethodName,
        String callType,
        Integer callLine,
        String bridgeType,
        String sqlId,
        String targetService,
        String targetEndpoint
    ) {}

    // ==================== 图遍历查询（性能优化） ====================

    /**
     * 图遍历结果 DTO
     */
    record GraphTraversalResult(
        String nodeId,
        String className,
        String methodName,
        String signature,
        String filePath,
        Integer startLine,
        Integer depth,
        String description
    ) {
        /** 便捷构造函数：description 可缺省 */
        public GraphTraversalResult(String nodeId, String className, String methodName,
                                    String signature, String filePath, Integer startLine,
                                    Integer depth) {
            this(nodeId, className, methodName, signature, filePath, startLine, depth, null);
        }
    }

    /**
     * 图遍历边结果 DTO
     */
    record GraphEdgeResult(
        String sourceId,
        String targetId,
        String callType,
        Integer callLine
    ) {}

    /**
     * 根据入口Key获取完整调用链的所有节点（单次Neo4j图遍历）
     * 使用Neo4j原生图遍历，性能远优于多次查询
     * 注意：Neo4j 不允许在变长关系长度中使用参数，固定上限 20，再用 WHERE 过滤实际深度。
     */
    @Query("""
        MATCH (ep:EntryPoint {entryKey: $entryKey, projectPath: $projectPath})
        WITH ep.methodNodeId as entryMethodId
        MATCH (entry:Method {nodeId: entryMethodId})
        MATCH path = (entry)-[:CALLS*0..20]->(m:Method)
        WHERE length(path) <= $maxDepth
        RETURN DISTINCT m.nodeId as nodeId, m.className as className,
               m.methodName as methodName, m.signature as signature,
               m.filePath as filePath, m.startLine as startLine,
               m.description as description, length(path) as depth
        ORDER BY depth, nodeId
        """)
    List<GraphTraversalResult> getCallChainNodesByEntryKey(
        @Param("entryKey") String entryKey,
        @Param("projectPath") String projectPath,
        @Param("maxDepth") int maxDepth
    );

    /**
     * 根据入口Key获取调用链的所有边（单次Neo4j图遍历）
     * 注意：Neo4j 不允许在变长关系长度中使用参数，固定上限 20，再用 WHERE 过滤实际深度。
     */
    @Query("""
        MATCH (ep:EntryPoint {entryKey: $entryKey, projectPath: $projectPath})
        WITH ep.methodNodeId as entryMethodId
        MATCH (entry:Method {nodeId: entryMethodId})
        MATCH path = (entry)-[:CALLS*1..20]->(method:Method)
        WHERE length(path) <= $maxDepth
        UNWIND relationships(path) as r
        RETURN DISTINCT startNode(r).nodeId as sourceId,
               endNode(r).nodeId as targetId,
               r.callType as callType, r.callLine as callLine
        """)
    List<GraphEdgeResult> getCallChainEdgesByEntryKey(
        @Param("entryKey") String entryKey,
        @Param("projectPath") String projectPath,
        @Param("maxDepth") int maxDepth
    );

    // ==================== 调用链统计查询 ====================

    /**
     * 统计从入口点可达的方法总数
     * 用于替代 PostgreSQL 的 callChainCount
     */
    @Query("""
        MATCH (ep:EntryPoint {projectPath: $projectPath})
        WITH ep.methodNodeId as entryMethodId
        MATCH (entry:Method {nodeId: entryMethodId})
        MATCH (entry)-[:CALLS*0..50]->(m:Method)
        RETURN COUNT(DISTINCT m) as count
        """)
    long countReachableMethodsFromEntryPoints(@Param("projectPath") String projectPath);

    /**
     * [批量聚合] 按多项目路径列表统计入口点可达的方法数（Cypher IN）。
     * 调用方需先用 PathUtils.normalize 把所有路径规范化为正斜杠格式。
     */
    @Query("""
        MATCH (ep:EntryPoint)
        WHERE ep.projectPath IN $projectPaths
        WITH ep.methodNodeId as entryMethodId
        MATCH (entry:Method {nodeId: entryMethodId})
        MATCH (entry)-[:CALLS*0..50]->(m:Method)
        RETURN COUNT(DISTINCT m) as count
        """)
    long countReachableMethodsFromEntryPointsByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 查找调用指定方法的所有入口点（反向图遍历）
     * 用于 getCallChainsAffecting 方法
     */
    @Query("""
        MATCH (ep:EntryPoint {projectPath: $projectPath})
        WITH ep.methodNodeId as entryMethodId, ep.entryId as entryId,
             ep.entryType as entryType, ep.entryKey as entryKey
        MATCH (entry:Method {nodeId: entryMethodId})
        MATCH path = (entry)-[:CALLS*]->(target:Method {nodeId: $nodeId})
        RETURN DISTINCT entryId, entryType, entryKey
        """)
    List<EntryPointInfo> findEntryPointsCallingMethod(
        @Param("nodeId") String nodeId,
        @Param("projectPath") String projectPath
    );

    /**
     * Feign 桥接目标 DTO
     * 从 FEIGN_PROXY 实现通过共享接口找到 LOCAL 实现
     */
    record FeignBridgeTarget(
        String implNodeId,
        String ifaceNodeId
    ) {}

    /**
     * Feign 桥接调用者 DTO
     * 从 LOCAL 实现通过共享接口找到 FEIGN_PROXY 兄弟（用于上游查询）
     */
    record FeignBridgeCaller(
        String feignNodeId,
        String ifaceNodeId
    ) {}

    /**
     * 入口点信息 DTO
     */
    record EntryPointInfo(
        String entryId,
        String entryType,
        String entryKey
    ) {}

    /**
     * 获取所有不同的项目路径
     * 用于替代旧的 callchain/projects 接口
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IS NOT NULL
        RETURN DISTINCT m.projectPath as projectPath
        ORDER BY projectPath
        """)
    List<String> findDistinctProjectPaths();

    /**
     * 获取项目路径下所有不同的类名
     * 用于替代旧的 callchain/classes 接口
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.className IS NOT NULL
        RETURN DISTINCT m.className as className
        ORDER BY className
        """)
    List<String> findDistinctClassNamesByProjectPath(@Param("projectPath") String projectPath);

    // ==================== 多策略路由搜索查询 ====================

    /**
     * 根据项目路径和类名模糊匹配
     * 用于 CLASS_NAME 查询类型
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.className CONTAINS $className
        RETURN m
        """)
    List<MethodNode> findByProjectPathAndClassNameContaining(
        @Param("projectPath") String projectPath,
        @Param("className") String className
    );

    /**
     * 根据项目路径、类名精确匹配和方法名精确匹配
     * 用于 FULL_QUALIFIED_NAME 查询类型
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath, className: $className, methodName: $methodName})
        RETURN m
        """)
    List<MethodNode> findByProjectPathAndClassNameAndMethodName(
        @Param("projectPath") String projectPath,
        @Param("className") String className,
        @Param("methodName") String methodName
    );

    /**
     * 根据项目路径和方法名模糊匹配
     * 用于 METHOD_NAME 查询类型
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE m.methodName CONTAINS $methodName
        RETURN m
        """)
    List<MethodNode> findByProjectPathAndMethodNameContaining(
        @Param("projectPath") String projectPath,
        @Param("methodName") String methodName
    );

    /**
     * 在方法体和注释中搜索包含指定注解的方法
     * 用于 ANNOTATION 查询类型
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE (m.methodBody IS NOT NULL AND m.methodBody CONTAINS $annotation)
           OR (m.comment IS NOT NULL AND m.comment CONTAINS $annotation)
        RETURN m
        """)
    List<MethodNode> findByProjectPathAndAnnotation(
        @Param("projectPath") String projectPath,
        @Param("annotation") String annotation
    );

    /**
     * 在抛出异常或捕获异常中搜索包含指定异常类型的方法
     * 用于 EXCEPTION_TYPE 查询类型
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})
        WHERE any(ex IN m.thrownExceptions WHERE ex CONTAINS $exceptionType)
           OR any(ex IN m.caughtExceptions WHERE ex CONTAINS $exceptionType)
        RETURN m
        """)
    List<MethodNode> findByProjectPathAndExceptionType(
        @Param("projectPath") String projectPath,
        @Param("exceptionType") String exceptionType
    );

    /**
     * 描述向量相似度查询 (使用 Neo4j 向量索引)
     * 用于 NATURAL_LANGUAGE 查询类型（向量索引可用时）
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_description_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath = $projectPath AND score >= $threshold
        RETURN m
        ORDER BY score DESC
        """)
    List<MethodNode> findByDescriptionVectorIndex(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 描述向量相似度查询 (使用 Neo4j 向量索引，带分数返回)
     * 用于 NATURAL_LANGUAGE 查询类型（向量索引可用时），返回节点和相似度分数
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_description_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath = $projectPath AND score >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               score
        ORDER BY score DESC
        """)
    List<MethodWithScore> findByDescriptionVectorIndexWithScore(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 代码向量相似度查询 (使用 Neo4j 向量索引)
     * 用于 CODE_SNIPPET 查询类型（向量索引可用时）
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_code_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath = $projectPath AND score >= $threshold
        RETURN m
        ORDER BY score DESC
        """)
    List<MethodNode> findByCodeVectorIndex(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 代码向量相似度查询 (使用 Neo4j 向量索引，带分数返回)
     * 用于 CODE_SNIPPET 查询类型（向量索引可用时），返回节点和相似度分数
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_code_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath = $projectPath AND score >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               score
        ORDER BY score DESC
        """)
    List<MethodWithScore> findByCodeVectorIndexWithScore(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 根据入口点的 methodNodeId 查询关联的方法节点
     * 用于 HTTP_URI 查询类型的后续关联
     */
    @Query("""
        MATCH (ep:EntryPoint {projectPath: $projectPath})
        WHERE ep.methodNodeId = $methodNodeId
        WITH ep
        MATCH (m:Method {nodeId: $methodNodeId})
        RETURN m
        """)
    Optional<MethodNode> findMethodByEntryPointMethodNodeId(
        @Param("projectPath") String projectPath,
        @Param("methodNodeId") String methodNodeId
    );

    // ==================== 批量上下文查询（N+1 问题优化） ====================

    /**
     * 批量查询调用者（带关系属性）
     * 返回 Map<nodeId, List<CallerWithRelation>>
     * 用于 buildSearchResultItems 批量获取调用者摘要
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method)
        WHERE callee.nodeId IN $nodeIds
        RETURN callee.nodeId as targetNodeId,
               caller.nodeId as callerId, caller.className as callerClassName,
               caller.methodName as callerMethodName, caller.signature as callerSignature,
               r.callType as callType, r.callLine as callLine
        """)
    List<CallerWithRelationByTarget> findCallersByNodeIds(@Param("nodeIds") List<String> nodeIds);

    /**
     * 批量查询被调用者（带关系属性）
     * 返回 Map<nodeId, List<CalleeWithRelation>>
     * 用于 buildSearchResultItems 批量获取被调用者摘要
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method)
        WHERE caller.nodeId IN $nodeIds
        RETURN caller.nodeId as sourceNodeId,
               callee.nodeId as calleeId, callee.className as calleeClassName,
               callee.methodName as calleeMethodName, callee.signature as calleeSignature,
               r.callType as callType, r.callLine as callLine
        """)
    List<CalleeWithRelationBySource> findCalleesByNodeIds(@Param("nodeIds") List<String> nodeIds);

    /**
     * 调用者批量查询结果 DTO（包含目标节点ID）
     */
    record CallerWithRelationByTarget(
        String targetNodeId,
        String callerId,
        String callerClassName,
        String callerMethodName,
        String callerSignature,
        String callType,
        Integer callLine
    ) {}

    /**
     * 被调用者批量查询结果 DTO（包含源节点ID）
     */
    record CalleeWithRelationBySource(
        String sourceNodeId,
        String calleeId,
        String calleeClassName,
        String calleeMethodName,
        String calleeSignature,
        String callType,
        Integer callLine
    ) {}

    // ==================== Cross-Service Linking (HttpRestLinkStrategy) ====================

    /**
     * Outbound HTTP call projection for cross-service linking.
     */
    interface OutboundHttpCall {
        String getCallerNodeId();
        String getCallerProjectPath();
        String getTargetEndpoint();
        String getHttpMethod();
        Integer getCallLine();
    }

    /**
     * HTTP entry point projection for cross-service linking.
     */
    interface HttpEntryInfo {
        String getEntryKey();
        String getMethodNodeId();
        String getProjectPath();
    }

    /**
     * Find outbound HTTP calls within the given project paths.
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method)
        WHERE caller.projectPath IN $projectPaths
        AND r.bridgeType IN ['HTTP', 'FEIGN']
        AND r.targetEndpoint IS NOT NULL
        RETURN caller.nodeId AS callerNodeId,
               caller.projectPath AS callerProjectPath,
               r.targetEndpoint AS targetEndpoint,
               r.callType AS httpMethod,
               r.callLine AS callLine
        """)
    List<OutboundHttpCall> findOutboundHttpCalls(@Param("projectPaths") List<String> projectPaths);

    /**
     * Find HTTP entry points within the given project paths.
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths
        AND entry.entryType IN ['HTTP', 'FASTAPI_ROUTE', 'FLASK_ROUTE', 'DJANGO_URL']
        AND entry.methodNodeId IS NOT NULL
        RETURN entry.entryKey AS entryKey,
               entry.methodNodeId AS methodNodeId,
               entry.projectPath AS projectPath
        """)
    List<HttpEntryInfo> findHttpEntries(@Param("projectPaths") List<String> projectPaths);

    // ==================== Cross-Service Linking (MqLinkStrategy) ====================

    /**
     * MQ producer call projection for cross-service linking.
     */
    interface MqProducerCall {
        String getCallerNodeId();
        String getCallerProjectPath();
        String getTopic();
        Integer getCallLine();
    }

    /**
     * MQ consumer entry point projection for cross-service linking.
     */
    interface MqConsumerEntry {
        String getEntryKey();
        String getMethodNodeId();
        String getProjectPath();
    }

    /**
     * Find MQ producer calls within the given project paths.
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method)
        WHERE caller.projectPath IN $projectPaths
        AND r.bridgeType = 'MQ'
        AND r.targetEndpoint IS NOT NULL
        RETURN caller.nodeId AS callerNodeId,
               caller.projectPath AS callerProjectPath,
               r.targetEndpoint AS topic,
               r.callLine AS callLine
        """)
    List<MqProducerCall> findMqProducerCalls(@Param("projectPaths") List<String> projectPaths);

    /**
     * Find MQ consumer entry points within the given project paths.
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths
        AND entry.entryType = 'MQ_CONSUMER'
        AND entry.methodNodeId IS NOT NULL
        RETURN entry.entryKey AS entryKey,
               entry.methodNodeId AS methodNodeId,
               entry.projectPath AS projectPath
        """)
    List<MqConsumerEntry> findMqConsumerEntries(@Param("projectPaths") List<String> projectPaths);

    /**
     * 向量搜索结果 DTO (包含方法节点和相似度分数)
     * 注意：不包含 descriptionEmbedding/codeEmbedding 字段，避免 Spring Data Neo4j
     * DTO 映射路径 (AdditionalTypes.asFloatArray → asFloat → asString) 对 Neo4j 原生
     * FLOAT 列表无法 coerce 为 String 的 bug。向量字段在搜索结果中也无下游用途。
     */
    record MethodWithScore(
        String nodeId,
        String className,
        String methodName,
        String signature,
        String filePath,
        Integer startLine,
        Integer endLine,
        Integer complexity,
        List<String> thrownExceptions,
        List<String> caughtExceptions,
        String methodBody,
        String projectPath,
        String serviceName,
        String comment,
        String description,
        Double score
    ) {
        /**
         * 转换为 MethodNode 对象
         */
        public MethodNode toMethodNode() {
            return MethodNode.builder()
                    .nodeId(nodeId)
                    .className(className)
                    .methodName(methodName)
                    .signature(signature)
                    .filePath(filePath)
                    .startLine(startLine)
                    .endLine(endLine)
                    .complexity(complexity)
                    .thrownExceptions(thrownExceptions)
                    .caughtExceptions(caughtExceptions)
                    .methodBody(methodBody)
                    .projectPath(projectPath)
                    .serviceName(serviceName)
                    .comment(comment)
                    .description(description)
                    .build();
        }
    }

    // ==================== ByProjectPaths 重载 (多项目范围检索) ====================

    /**
     * 按项目路径列表查询所有方法节点
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
        RETURN m
        """)
    List<MethodNode> findByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 按项目路径列表 + 类名模糊匹配
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.className CONTAINS $className
        RETURN m
        """)
    List<MethodNode> findByProjectPathsAndClassNameContaining(
        @Param("projectPaths") List<String> projectPaths,
        @Param("className") String className
    );

    /**
     * 按项目路径列表 + 方法名模糊匹配
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.methodName CONTAINS $methodName
        RETURN m
        """)
    List<MethodNode> findByProjectPathsAndMethodNameContaining(
        @Param("projectPaths") List<String> projectPaths,
        @Param("methodName") String methodName
    );

    /**
     * 按项目路径列表 + 类名/方法名精确
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.className = $className AND m.methodName = $methodName
        RETURN m
        """)
    List<MethodNode> findByProjectPathsAndClassNameAndMethodName(
        @Param("projectPaths") List<String> projectPaths,
        @Param("className") String className,
        @Param("methodName") String methodName
    );

    /**
     * 按项目路径列表 + 类名精确
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.className = $className
        RETURN m
        """)
    List<MethodNode> findByProjectPathsAndClassName(
        @Param("projectPaths") List<String> projectPaths,
        @Param("className") String className
    );

    /**
     * 按项目路径列表搜索注解/装饰器
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND ((m.methodBody IS NOT NULL AND m.methodBody CONTAINS $annotation)
               OR (m.comment IS NOT NULL AND m.comment CONTAINS $annotation))
        RETURN m
        """)
    List<MethodNode> findByProjectPathsAndAnnotation(
        @Param("projectPaths") List<String> projectPaths,
        @Param("annotation") String annotation
    );

    /**
     * 按项目路径列表搜索异常类型
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND (any(ex IN m.thrownExceptions WHERE ex CONTAINS $exceptionType)
               OR any(ex IN m.caughtExceptions WHERE ex CONTAINS $exceptionType))
        RETURN m
        """)
    List<MethodNode> findByProjectPathsAndExceptionType(
        @Param("projectPaths") List<String> projectPaths,
        @Param("exceptionType") String exceptionType
    );

    /**
     * 描述向量相似度（按项目路径列表）
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.descriptionEmbedding IS NOT NULL
          AND size(m.descriptionEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.descriptionEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodNode> findByDescriptionVectorSimilarityByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") float[] embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 描述向量相似度（按项目路径列表，带分数）
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.descriptionEmbedding IS NOT NULL
          AND size(m.descriptionEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.descriptionEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               similarity as score
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodWithScore> findByDescriptionVectorSimilarityWithScoreByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") float[] embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 代码向量相似度（按项目路径列表）
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.codeEmbedding IS NOT NULL
          AND size(m.codeEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.codeEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodNode> findByCodeVectorSimilarityByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") float[] embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 代码向量相似度（按项目路径列表，带分数）
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.codeEmbedding IS NOT NULL
          AND size(m.codeEmbedding) = size($embedding)
        WITH m, gds.similarity.cosine(m.codeEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               similarity as score
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<MethodWithScore> findByCodeVectorSimilarityWithScoreByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") float[] embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 描述向量索引（按项目路径列表）
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_description_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath IN $projectPaths AND score >= $threshold
        RETURN m
        ORDER BY score DESC
        """)
    List<MethodNode> findByDescriptionVectorIndexByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 描述向量索引（按项目路径列表，带分数）
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_description_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath IN $projectPaths AND score >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               score
        ORDER BY score DESC
        """)
    List<MethodWithScore> findByDescriptionVectorIndexWithScoreByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 代码向量索引（按项目路径列表）
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_code_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath IN $projectPaths AND score >= $threshold
        RETURN m
        ORDER BY score DESC
        """)
    List<MethodNode> findByCodeVectorIndexByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 代码向量索引（按项目路径列表，带分数）
     */
    @Query("""
        CALL db.index.vector.queryNodes('method_code_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath IN $projectPaths AND score >= $threshold
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.thrownExceptions as thrownExceptions,
               m.caughtExceptions as caughtExceptions, m.methodBody as methodBody, m.projectPath as projectPath,
               m.serviceName as serviceName, m.comment as comment, m.description as description,
               score
        ORDER BY score DESC
        """)
    List<MethodWithScore> findByCodeVectorIndexWithScoreByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * 入口点 methodNodeId 关联方法（按项目路径列表）
     * 用于 HTTP_URI 反查
     */
    @Query("""
        MATCH (ep:EntryPoint)
        WHERE ep.projectPath IN $projectPaths
          AND ep.methodNodeId = $methodNodeId
        WITH ep
        MATCH (m:Method {nodeId: $methodNodeId})
        RETURN m
        """)
    Optional<MethodNode> findMethodByEntryPointMethodNodeIdByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("methodNodeId") String methodNodeId
    );

    /**
     * 类名 distinct（按项目路径列表）
     */
    @Query("""
        MATCH (m:Method)
        WHERE m.projectPath IN $projectPaths
          AND m.className IS NOT NULL
        RETURN DISTINCT m.className as className
        ORDER BY className
        """)
    List<String> findDistinctClassNamesByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 删除指定项目路径之间的跨服务调用关系
     * 匹配 :CALLS 关系中 callType='EXTERNAL_CALL' 的边（由 LinkStrategy 创建）
     */
    @Query("""
        MATCH (a:Method)-[r:CALLS]->(b:Method)
        WHERE a.projectPath IN $projectPaths AND b.projectPath IN $projectPaths
          AND r.callType = 'EXTERNAL_CALL'
        DELETE r
        RETURN count(r) AS deleted
        """)
    long deleteExternalCallsBetween(@Param("projectPaths") List<String> projectPaths);

    // ==================== 批量多项目查询（N+1 优化） ====================

    /**
     * 批量查询多个项目的调用关系
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method)
        WHERE caller.projectPath IN $projectPaths
        RETURN caller.nodeId as callerId, caller.className as callerClassName,
               caller.methodName as callerMethodName,
               callee.nodeId as calleeId, callee.className as calleeClassName,
               callee.methodName as calleeMethodName,
               r.callType as callType, r.callLine as callLine, r.bridgeType as bridgeType,
               r.sqlId as sqlId, r.targetService as targetService, r.targetEndpoint as targetEndpoint
        """)
    List<CallRelationWithNodes> findAllCallRelationsByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量按桥接类型查询多个项目的调用关系
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method)
        WHERE caller.projectPath IN $projectPaths AND r.bridgeType = $bridgeType
        RETURN caller.nodeId as callerId, caller.className as callerClassName,
               caller.methodName as callerMethodName,
               callee.nodeId as calleeId, callee.className as calleeClassName,
               callee.methodName as calleeMethodName,
               r.callType as callType, r.callLine as callLine, r.bridgeType as bridgeType,
               r.sqlId as sqlId, r.targetService as targetService, r.targetEndpoint as targetEndpoint
        """)
    List<CallRelationWithNodes> findByBridgeTypeAndProjectPaths(@Param("projectPaths") List<String> projectPaths, @Param("bridgeType") String bridgeType);

    /**
     * 批量统计多个项目的调用关系数量
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(callee:Method)
        WHERE caller.projectPath IN $projectPaths
        RETURN COUNT(r)
        """)
    long countCallRelationsByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量按桥接类型统计多个项目的调用关系数量
     */
    @Query("""
        MATCH (caller:Method)-[r:CALLS]->(:Method)
        WHERE caller.projectPath IN $projectPaths AND r.bridgeType = $bridgeType
        RETURN COUNT(r)
        """)
    long countByBridgeTypeAndProjectPaths(@Param("projectPaths") List<String> projectPaths, @Param("bridgeType") String bridgeType);

    /**
     * 批量查询多个项目中调用指定方法的入口点
     */
    @Query("""
        MATCH (ep:EntryPoint)
        WHERE ep.projectPath IN $projectPaths
        WITH ep.methodNodeId as entryMethodId, ep.entryId as entryId,
             ep.entryType as entryType, ep.entryKey as entryKey
        MATCH (entry:Method {nodeId: entryMethodId})
        MATCH path = (entry)-[:CALLS*]->(target:Method {nodeId: $nodeId})
        RETURN DISTINCT entryId, entryType, entryKey
        """)
    List<EntryPointInfo> findEntryPointsCallingMethodByPaths(
        @Param("nodeId") String nodeId,
        @Param("projectPaths") List<String> projectPaths
    );

    // ==================== 类继承关系 (EXTENDS) ====================

    /**
     * 批量创建 EXTENDS 关系（子类 -> 父类）
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (sub:Method)
        WHERE sub.className = rel.subclass AND sub.projectPath IN $projectPaths
        MATCH (sup:Method)
        WHERE sup.className = rel.superclass AND sup.projectPath IN $projectPaths
        WITH sub, sup, rel
        MERGE (sub)-[e:EXTENDS]->(sup)
        SET e.projectPath = rel.projectPath
        """)
    void createExtendsRelations(
        @Param("relations") List<Map<String, Object>> relations,
        @Param("projectPaths") List<String> projectPaths
    );

    /**
     * 统计项目的 EXTENDS 关系数量
     */
    @Query("""
        MATCH (:Method)-[e:EXTENDS]->(:Method)
        WHERE e.projectPath = $projectPath
        RETURN count(e)
        """)
    int countExtendsRelations(@Param("projectPath") String projectPath);

    // ==================== 方法重写关系 (OVERRIDE) ====================

    /**
     * 批量创建 OVERRIDE 关系（子类方法 -> 父类方法）
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (sub:Method)
        WHERE sub.className = rel.subclass
          AND sub.methodName = rel.methodName
          AND sub.projectPath IN $projectPaths
        MATCH (sup:Method)
        WHERE sup.className = rel.superclass
          AND sup.methodName = rel.methodName
          AND sup.projectPath IN $projectPaths
        WITH sub, sup, rel
        MERGE (sub)-[o:OVERRIDE]->(sup)
        SET o.projectPath = rel.projectPath
        """)
    void createOverrideRelations(
        @Param("relations") List<Map<String, Object>> relations,
        @Param("projectPaths") List<String> projectPaths
    );

    /**
     * 统计项目的 OVERRIDE 关系数量
     */
    @Query("""
        MATCH (:Method)-[o:OVERRIDE]->(:Method)
        WHERE o.projectPath = $projectPath
        RETURN count(o)
        """)
    int countOverrideRelations(@Param("projectPath") String projectPath);

    // ==================== 代理类关系 (PROXY) ====================

    /**
     * 批量创建 PROXY 关系（代理类 -> 被代理类）
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (proxy:Method)
        WHERE proxy.className = rel.proxyClass
          AND proxy.projectPath IN $projectPaths
        MATCH (target:Method)
        WHERE target.className = rel.targetClass
          AND target.projectPath IN $projectPaths
        WITH proxy, target, rel
        MERGE (proxy)-[p:PROXY]->(target)
        SET p.projectPath = rel.projectPath, p.proxyType = rel.proxyType
        """)
    void createProxyRelations(
        @Param("relations") List<Map<String, Object>> relations,
        @Param("projectPaths") List<String> projectPaths
    );

    /**
     * 统计项目的 PROXY 关系数量
     */
    @Query("""
        MATCH (:Method)-[p:PROXY]->(:Method)
        WHERE p.projectPath = $projectPath
        RETURN count(p)
        """)
    int countProxyRelations(@Param("projectPath") String projectPath);

    // ==================== 清理项目数据方法 ====================

    /**
     * 删除项目的 EXTENDS 关系
     */
    @Query("""
        MATCH ()-[r:EXTENDS]->() WHERE r.projectPath = $projectPath
        DELETE r
        """)
    void deleteExtendsRelationsByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 删除项目的 OVERRIDE 关系
     */
    @Query("""
        MATCH ()-[r:OVERRIDE]->() WHERE r.projectPath = $projectPath
        DELETE r
        """)
    void deleteOverrideRelationsByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 删除项目的 PROXY 关系
     */
    @Query("""
        MATCH ()-[r:PROXY]->() WHERE r.projectPath = $projectPath
        DELETE r
        """)
    void deleteProxyRelationsByProjectPath(@Param("projectPath") String projectPath);
}
