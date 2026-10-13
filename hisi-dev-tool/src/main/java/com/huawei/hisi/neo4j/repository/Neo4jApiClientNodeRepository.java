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
     * 按多项目路径批量查询 API 调用点节点（IN 子句，跨层骨架图用）。
     */
    @Query("""
        MATCH (a:ApiClient)
        WHERE a.projectPath IN $projectPaths
        RETURN a
        """)
    List<ApiClientNode> findByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量查询 ApiClient -[:INVOKES_API]-> EntryPoint 跨层边（跨层骨架图用）。
     */
    @Query("""
        MATCH (a:ApiClient)-[r:INVOKES_API]->(e:EntryPoint)
        WHERE a.projectPath IN $projectPaths
        RETURN a.apiClientId AS apiClientId, e.entryId AS entryId, r.method AS method
        """)
    List<InvokesApiEdge> findInvokesApiEdgesByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    record InvokesApiEdge(String apiClientId, String entryId, String method) {}

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

    /**
     * 读取某项目下尚无 descriptionEmbedding 的 ApiClient 节点（待向量化）。
     */
    @Query("""
        MATCH (a:ApiClient {projectPath: $projectPath})
        WHERE a.descriptionEmbedding IS NULL
        RETURN a
        """)
    List<ApiClientNode> findWithoutEmbeddingByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 写回 ApiClient 的 descriptionEmbedding 向量。
     */
    @Query("""
        MATCH (a:ApiClient {apiClientId: $apiClientId})
        SET a.descriptionEmbedding = $embedding
        """)
    void updateDescriptionEmbedding(@Param("apiClientId") String apiClientId,
                                    @Param("embedding") List<Double> embedding);

    /**
     * 语义检索：按描述向量索引命中 ApiClient 节点（带分数）。
     */
    @Query("""
        CALL db.index.vector.queryNodes('apiClient_description_vector_index', $topK, $embedding)
        YIELD node AS a, score
        WHERE a.projectPath IN $projectPaths AND score >= $threshold
        RETURN a.apiClientId as apiClientId, a.method as method, a.url as url,
               a.sourceFile as sourceFile, a.componentName as componentName, a.projectPath as projectPath,
               score
        ORDER BY score DESC
        """)
    List<ApiClientWithScore> findByDescriptionVectorIndexWithScoreByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    record ApiClientWithScore(String apiClientId, String method, String url,
                              String sourceFile, String componentName, String projectPath,
                              Double score) {
        public ApiClientNode toApiClientNode() {
            return ApiClientNode.builder()
                .apiClientId(apiClientId)
                .method(method)
                .url(url)
                .sourceFile(sourceFile)
                .componentName(componentName)
                .projectPath(projectPath)
                .build();
        }
    }
}
