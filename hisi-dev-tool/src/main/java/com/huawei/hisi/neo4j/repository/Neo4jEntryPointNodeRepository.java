package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.ServiceEntryGroup;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 入口点节点 Repository (Neo4j)
 * 提供入口点节点的 CRUD 和自定义查询方法
 */
@Repository
public interface Neo4jEntryPointNodeRepository extends Neo4jRepository<EntryPointNode, String> {

    /**
     * 轻量投影，避免加载 briefEmbedding / detailedEmbedding
     */
    interface EntryPointListItem {
        String getEntryId();
        String getEntryType();
        String getEntryKey();
        String getEntryInfo();
        String getMethodNodeId();
        String getProjectPath();
        String getBriefDescription();
        String getDetailedDescription();
        String getServiceName();
    }

    /**
     * 根据入口ID查询
     */
    Optional<EntryPointNode> findByEntryId(String entryId);

    /**
     * 根据项目路径查询所有入口点
     */
    List<EntryPointNode> findByProjectPath(String projectPath);

    /**
     * 根据服务名查询所有入口点
     */
    List<EntryPointNode> findByServiceName(String serviceName);

    /**
     * 根据入口类型查询
     */
    List<EntryPointNode> findByEntryType(String entryType);

    /**
     * 根据入口Key查询
     */
    Optional<EntryPointNode> findByEntryKey(String entryKey);

    /**
     * 根据项目路径和入口类型查询
     */
    List<EntryPointNode> findByProjectPathAndEntryType(String projectPath, String entryType);

    /**
     * 根据项目路径和入口Key查询
     */
    List<EntryPointNode> findByProjectPathAndEntryKey(String projectPath, String entryKey);

    /**
     * 根据多个项目路径和入口Key查询（IN 子句）
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths AND entry.entryKey = $entryKey
        RETURN entry
        """)
    List<EntryPointNode> findByProjectPathsAndEntryKey(@Param("projectPaths") List<String> projectPaths, @Param("entryKey") String entryKey);

    /**
     * 删除项目下的所有入口点
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath})
        DETACH DELETE entry
        """)
    void deleteByProjectPath(String projectPath);

    @Query("""
        MATCH (e:EntryPoint {projectPath: $projectPath})
        SET e.briefDescription = null, e.detailedDescription = null, e.briefEmbedding = null, e.detailedEmbedding = null
        RETURN count(e)
        """)
    long clearDescriptionsAndEmbeddings(String projectPath);

    /**
     * 分批删除项目下的入口点，避免单事务内存溢出
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath})
        WITH entry LIMIT $batchSize
        DETACH DELETE entry
        RETURN count(*) AS deleted
        """)
    long deleteByProjectPathBatch(@Param("projectPath") String projectPath, @Param("batchSize") int batchSize);

    /**
     * 删除指定文件关联的入口点（通过 methodNodeId 关联到 Method 节点的 filePath）
     * 使用 CONTAINS 匹配以处理路径格式差异（正斜杠/反斜杠）
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath})
        WHERE entry.methodNodeId IS NOT NULL
          AND EXISTS {
            MATCH (m:Method {nodeId: entry.methodNodeId})
            WHERE m.filePath = $filePath OR m.filePath CONTAINS $filePath OR $filePath CONTAINS m.filePath
          }
        DETACH DELETE entry
        """)
    void deleteByFilePathAndProjectPath(@Param("filePath") String filePath, @Param("projectPath") String projectPath);

    /**
     * 统计项目下的入口点数量
     */
    long countByProjectPath(String projectPath);

    /**
     * [批量聚合] 按多项目路径列表统计入口点数量（Cypher IN）。
     * 调用方需先用 PathUtils.normalize 把所有路径规范化为正斜杠格式。
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths
        RETURN count(entry)
        """)
    long countByProjectPaths(@org.springframework.data.repository.query.Param("projectPaths") java.util.List<String> projectPaths);

    /**
     * 查询入口点关联的方法节点ID
     * 通过 methodNodeId 字段关联，而非 Neo4j 关系
     */
    @Query("""
        MATCH (entry:EntryPoint {entryId: $entryId})
        RETURN entry.methodNodeId as methodNodeId
        """)
    Optional<String> findMethodNodeIdByEntryId(@Param("entryId") String entryId);

    /**
     * 根据URI模式查询HTTP入口点
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.entryType = 'HTTP' AND entry.entryKey CONTAINS $uriPattern
        RETURN entry
        """)
    List<EntryPointNode> findByUriPattern(@Param("uriPattern") String uriPattern);

    /**
     * 根据项目路径和入口Key模糊匹配
     * 用于 HTTP_URI 查询类型
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath})
        WHERE entry.entryKey CONTAINS $entryKey
        RETURN entry
        """)
    List<EntryPointNode> findByProjectPathAndEntryKeyContaining(
        @Param("projectPath") String projectPath,
        @Param("entryKey") String entryKey
    );

    /**
     * 根据 methodNodeId 查找关联的入口点
     * 用于获取方法的入口点信息
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath})
        WHERE entry.methodNodeId = $methodNodeId
        RETURN entry
        """)
    List<EntryPointNode> findByProjectPathAndMethodNodeId(
        @Param("projectPath") String projectPath,
        @Param("methodNodeId") String methodNodeId
    );

    /**
     * 根据项目路径和HTTP方法查询入口点
     * 用于 HTTP_URI 查询类型的精确匹配
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath, entryType: 'HTTP'})
        WHERE entry.entryKey STARTS WITH $httpMethod
        RETURN entry
        """)
    List<EntryPointNode> findByProjectPathAndHttpMethod(
        @Param("projectPath") String projectPath,
        @Param("httpMethod") String httpMethod
    );

    /**
     * 批量根据 methodNodeId 查找关联的入口点
     * 用于 buildSearchResultItems 批量获取入口点摘要，解决 N+1 查询问题
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath})
        WHERE entry.methodNodeId IN $methodNodeIds
        RETURN entry
        """)
    List<EntryPointNode> findByMethodNodeIds(
        @Param("projectPath") String projectPath,
        @Param("methodNodeIds") List<String> methodNodeIds
    );

    /**
     * 查找受指定类变更影响的入口点（正向图遍历）。
     * 从项目入口点出发沿 CALLS 链向下追踪，若调用链中任一方法属于目标类则返回该入口点。
     * CALLS*0.. 支持 length=0（入口点方法本身就在目标类中），同时覆盖内部类（$ 分隔符）。
     */
    @Query("""
        MATCH (ep:EntryPoint {projectPath: $projectPath})
        WHERE ep.methodNodeId IS NOT NULL
        WITH ep
        MATCH (entry:Method {nodeId: ep.methodNodeId})
        MATCH path = (entry)-[:CALLS*0..10]->(target:Method)
        WHERE (target.className = $className OR target.className STARTS WITH $className + '$')
          AND target.projectPath = $projectPath
          AND length(path) <= $maxDepth
        RETURN DISTINCT ep
        """)
    List<EntryPointNode> findEntryPointsAffectingClass(
        @Param("projectPath") String projectPath,
        @Param("className") String className,
        @Param("maxDepth") int maxDepth
    );

    // ==================== 批量多项目查询（N+1 优化） ====================

    /**
     * 批量查询多个项目的入口点
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths
        RETURN entry
        """)
    List<EntryPointNode> findByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量按类型查询多个项目的入口点
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths AND entry.entryType = $entryType
        RETURN entry
        """)
    List<EntryPointNode> findByProjectPathsAndEntryType(@Param("projectPaths") List<String> projectPaths, @Param("entryType") String entryType);

    /**
     * 分页查询多个项目的入口点（投影，不含 embedding）
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths
        RETURN entry.entryId AS entryId, entry.entryType AS entryType, entry.entryKey AS entryKey,
               entry.entryInfo AS entryInfo, entry.methodNodeId AS methodNodeId, entry.projectPath AS projectPath,
               entry.briefDescription AS briefDescription, entry.detailedDescription AS detailedDescription,
               entry.serviceName AS serviceName
        ORDER BY entry.entryKey
        SKIP $skip LIMIT $limit
        """)
    List<EntryPointListItem> findByProjectPathsPaged(
        @Param("projectPaths") List<String> projectPaths,
        @Param("skip") long skip,
        @Param("limit") int limit
    );

    /**
     * 分页按类型查询多个项目的入口点（投影，不含 embedding）
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths AND entry.entryType = $entryType
        RETURN entry.entryId AS entryId, entry.entryType AS entryType, entry.entryKey AS entryKey,
               entry.entryInfo AS entryInfo, entry.methodNodeId AS methodNodeId, entry.projectPath AS projectPath,
               entry.briefDescription AS briefDescription, entry.detailedDescription AS detailedDescription,
               entry.serviceName AS serviceName
        ORDER BY entry.entryKey
        SKIP $skip LIMIT $limit
        """)
    List<EntryPointListItem> findByProjectPathsAndEntryTypePaged(
        @Param("projectPaths") List<String> projectPaths,
        @Param("entryType") String entryType,
        @Param("skip") long skip,
        @Param("limit") int limit
    );

    /**
     * 按类型统计多个项目的入口点数量
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths AND entry.entryType = $entryType
        RETURN count(entry)
        """)
    long countByProjectPathsAndEntryType(
        @Param("projectPaths") List<String> projectPaths,
        @Param("entryType") String entryType
    );

    /**
     * 获取多个项目下所有不同的入口类型
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths
        RETURN DISTINCT entry.entryType
        ORDER BY entry.entryType
        """)
    List<String> findDistinctEntryTypesByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 更新入口点的描述和向量
     */
    @Query("""
        MATCH (e:EntryPoint {entryId: $entryId})
        SET e.briefDescription = $briefDescription,
            e.detailedDescription = $detailedDescription,
            e.briefEmbedding = $briefEmbedding,
            e.detailedEmbedding = $detailedEmbedding
        """)
    void updateDescriptionAndEmbedding(
        @Param("entryId") String entryId,
        @Param("briefDescription") String briefDescription,
        @Param("detailedDescription") String detailedDescription,
        @Param("briefEmbedding") List<Double> briefEmbedding,
        @Param("detailedEmbedding") List<Double> detailedEmbedding
    );

    /**
     * 按 serviceName 聚合查询入口点
     */
    @Query("""
        MATCH (e:EntryPoint)
        WHERE e.projectPath IN $projectPaths
        RETURN e.serviceName as serviceName,
               collect({
                   entryId: e.entryId,
                   entryType: e.entryType,
                   entryKey: e.entryKey,
                   briefDescription: e.briefDescription
               }) as entries,
               count(e) as totalCount
        ORDER BY serviceName
        """)
    List<ServiceEntryGroup> findByProjectPathsGroupedByServiceName(@Param("projectPaths") List<String> projectPaths);

    /**
     * 按 serviceName 分页聚合查询入口点
     */
    @Query("""
        MATCH (e:EntryPoint)
        WHERE e.projectPath IN $projectPaths
        WITH e.serviceName as serviceName, collect({
            entryId: e.entryId,
            entryType: e.entryType,
            entryKey: e.entryKey,
            briefDescription: e.briefDescription
        }) as entries, count(e) as totalCount
        RETURN serviceName, entries, totalCount
        ORDER BY serviceName
        SKIP $skip LIMIT $limit
        """)
    List<ServiceEntryGroup> findByProjectPathsGroupedByServiceNamePaged(
        @Param("projectPaths") List<String> projectPaths,
        @Param("skip") long skip,
        @Param("limit") int limit
    );

    /**
     * 统计多个项目下的服务分组数量
     */
    @Query("""
        MATCH (e:EntryPoint)
        WHERE e.projectPath IN $projectPaths
        RETURN count(DISTINCT e.serviceName)
        """)
    long countServiceNamesByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量 MERGE 保存入口点节点（幂等，遇到重复 entryId 会更新而非报错）
     */
    @Query("""
        UNWIND $nodes AS n
        MERGE (e:EntryPoint {entryId: n.entryId})
        SET e.entryType = n.entryType,
            e.entryKey = n.entryKey,
            e.entryInfo = n.entryInfo,
            e.methodNodeId = n.methodNodeId,
            e.projectPath = n.projectPath,
            e.briefDescription = n.briefDescription,
            e.detailedDescription = n.detailedDescription,
            e.serviceName = n.serviceName,
            e.language = n.language
        """)
    void mergeAll(@Param("nodes") List<Map<String, Object>> nodes);
}
