package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * ApiClientNode Repository (Neo4j)
 * 前端 API 调用点节点的 CRUD 与批量 MERGE 保存。
 */
@Repository
public interface Neo4jApiClientNodeRepository extends Neo4jRepository<ApiClientNode, String> {

    List<ApiClientNode> findByProjectPath(String projectPath);

    /**
     * 批量 MERGE 保存 API 调用点节点（幂等）。
     */
    @Query("""
        UNWIND $nodes AS n
        MERGE (a:ApiClient {apiClientId: n.apiClientId})
        SET a.method = n.method,
            a.url = n.url,
            a.sourceFile = n.sourceFile,
            a.componentName = n.componentName,
            a.projectPath = n.projectPath,
            a.language = n.language
        """)
    void mergeAll(@Param("nodes") List<Map<String, Object>> nodes);

    /**
     * 按项目路径删除所有 ApiClientNode。
     */
    @Query("MATCH (a:ApiClient {projectPath: $projectPath}) DETACH DELETE a")
    void detachDeleteByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 批量创建 ApiClient -[:INVOKES_API]-> EntryPoint 跨层边（静态 URL 匹配命中后）。
     * 仅当 source ApiClient 与 target EntryPoint 都已落库时才建边。
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (a:ApiClient {apiClientId: rel.apiClientId})
        MATCH (e:EntryPoint {entryId: rel.entryId})
        MERGE (a)-[r:INVOKES_API]->(e)
        SET r.method = rel.method
        """)
    void createInvokesApiRelations(@Param("relations") List<Map<String, Object>> relations);

    /**
     * 删除某项目下所有 ApiClient 的 INVOKES_API 边（跨层链接重跑前清理）。
     */
    @Query("""
        MATCH (a:ApiClient {projectPath: $projectPath})-[r:INVOKES_API]->()
        DELETE r
        """)
    void deleteInvokesApiRelationsByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 查某后端 EntryPoint 的前端调用方（沿 INVOKES_API 边反向）。
     * 返回前端 ApiClient 的 url / sourceFile / componentName。
     */
    @Query("""
        MATCH (a:ApiClient)-[r:INVOKES_API]->(e:EntryPoint {entryId: $entryId})
        RETURN a.url AS url, a.sourceFile AS sourceFile, a.componentName AS componentName
        """)
    List<Map<String, Object>> findApiConsumersByEntryId(@Param("entryId") String entryId);

    /**
     * 查某前端 ApiClient 调用的后端接口（沿 INVOKES_API 边正向）。
     * 返回后端 EntryPoint 的 entryId / entryKey / entryType。
     */
    @Query("""
        MATCH (a:ApiClient {apiClientId: $apiClientId})-[r:INVOKES_API]->(e:EntryPoint)
        RETURN e.entryId AS entryId, e.entryKey AS entryKey, e.entryType AS entryType
        """)
    List<Map<String, Object>> findBackendDepsByApiClientId(@Param("apiClientId") String apiClientId);
}
