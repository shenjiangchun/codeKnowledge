package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.EntryPointNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 入口点节点 Repository (Neo4j)
 * 提供入口点节点的 CRUD 和自定义查询方法
 */
@Repository
public interface Neo4jEntryPointNodeRepository extends Neo4jRepository<EntryPointNode, String> {

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

    /**
     * 删除指定文件关联的入口点（通过 methodNodeId 关联到 Method 节点的 filePath）
     */
    @Query("""
        MATCH (entry:EntryPoint {projectPath: $projectPath})
        WHERE entry.methodNodeId IS NOT NULL
          AND EXISTS {
            MATCH (m:Method {nodeId: entry.methodNodeId})
            WHERE m.filePath = $filePath
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
     * 分页查询多个项目的入口点
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths
        RETURN entry
        ORDER BY entry.entryKey
        SKIP $skip LIMIT $limit
        """)
    List<EntryPointNode> findByProjectPathsPaged(
        @Param("projectPaths") List<String> projectPaths,
        @Param("skip") long skip,
        @Param("limit") int limit
    );

    /**
     * 分页按类型查询多个项目的入口点
     */
    @Query("""
        MATCH (entry:EntryPoint)
        WHERE entry.projectPath IN $projectPaths AND entry.entryType = $entryType
        RETURN entry
        ORDER BY entry.entryKey
        SKIP $skip LIMIT $limit
        """)
    List<EntryPointNode> findByProjectPathsAndEntryTypePaged(
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
}
