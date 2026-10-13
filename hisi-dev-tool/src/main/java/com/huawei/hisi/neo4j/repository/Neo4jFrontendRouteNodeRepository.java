package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * FrontendRouteNode Repository (Neo4j)
 * 前端路由节点的 CRUD 与批量 MERGE 保存。
 */
@Repository
public interface Neo4jFrontendRouteNodeRepository extends Neo4jRepository<FrontendRouteNode, String> {

    List<FrontendRouteNode> findByProjectPath(String projectPath);

    /**
     * 按多项目路径批量查询前端路由节点（IN 子句，跨层骨架图用）。
     */
    @Query("""
        MATCH (r:FrontendRoute)
        WHERE r.projectPath IN $projectPaths
        RETURN r
        """)
    List<FrontendRouteNode> findByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量 MERGE 保存前端路由节点（幂等）。
     */
    @Query("""
        UNWIND $nodes AS n
        MERGE (r:FrontendRoute {frontendRouteId: n.frontendRouteId})
        SET r.path = n.path,
            r.name = n.name,
            r.componentName = n.componentName,
            r.projectPath = n.projectPath
        """)
    void mergeAll(@Param("nodes") List<Map<String, Object>> nodes);

    /**
     * 按项目路径删除所有 FrontendRouteNode。
     */
    @Query("MATCH (r:FrontendRoute {projectPath: $projectPath}) DETACH DELETE r")
    void detachDeleteByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 读取某项目下尚无 descriptionEmbedding 的 FrontendRoute 节点（待向量化）。
     */
    @Query("""
        MATCH (r:FrontendRoute {projectPath: $projectPath})
        WHERE r.descriptionEmbedding IS NULL
        RETURN r
        """)
    List<FrontendRouteNode> findWithoutEmbeddingByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 写回 FrontendRoute 的 descriptionEmbedding 向量。
     */
    @Query("""
        MATCH (r:FrontendRoute {frontendRouteId: $frontendRouteId})
        SET r.descriptionEmbedding = $embedding
        """)
    void updateDescriptionEmbedding(@Param("frontendRouteId") String frontendRouteId,
                                    @Param("embedding") List<Double> embedding);

    /**
     * 语义检索：按描述向量索引命中 FrontendRoute 节点（带分数）。
     */
    @Query("""
        CALL db.index.vector.queryNodes('frontendRoute_description_vector_index', $topK, $embedding)
        YIELD node AS r, score
        WHERE r.projectPath IN $projectPaths AND score >= $threshold
        RETURN r.frontendRouteId as frontendRouteId, r.path as path, r.componentName as componentName,
               r.projectPath as projectPath, score
        ORDER BY score DESC
        """)
    List<RouteWithScore> findByDescriptionVectorIndexWithScoreByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    record RouteWithScore(String frontendRouteId, String path, String componentName,
                          String projectPath, Double score) {
        public FrontendRouteNode toFrontendRouteNode() {
            return FrontendRouteNode.builder()
                .frontendRouteId(frontendRouteId)
                .path(path)
                .componentName(componentName)
                .projectPath(projectPath)
                .build();
        }
    }
}
