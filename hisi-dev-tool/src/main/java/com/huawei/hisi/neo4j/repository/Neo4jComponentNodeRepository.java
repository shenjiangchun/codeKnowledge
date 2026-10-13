package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.ComponentNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * ComponentNode Repository (Neo4j)
 * 前端组件节点的 CRUD 与批量 MERGE 保存。
 */
@Repository
public interface Neo4jComponentNodeRepository extends Neo4jRepository<ComponentNode, String> {

    List<ComponentNode> findByProjectPath(String projectPath);

    /**
     * 按多项目路径批量查询组件节点（IN 子句，跨层骨架图用）。
     */
    @Query("""
        MATCH (c:Component)
        WHERE c.projectPath IN $projectPaths
        RETURN c
        """)
    List<ComponentNode> findByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量查询 Component -[:INVOKES]-> ApiClient 边（跨层骨架图用）。
     */
    @Query("""
        MATCH (c:Component)-[r:INVOKES]->(a:ApiClient)
        WHERE c.projectPath IN $projectPaths
        RETURN c.componentId AS componentId, a.apiClientId AS apiClientId
        """)
    List<InvokesEdge> findInvokesEdgesByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    record InvokesEdge(String componentId, String apiClientId) {}

    /**
     * 批量 MERGE 保存组件节点（幂等，遇到重复 componentId 会更新而非报错）。
     * 与 MethodNode.mergeAll / ClassNode.mergeAll 同模式：UNWIND + MERGE。
     */
    @Query("""
        UNWIND $nodes AS n
        MERGE (c:Component {componentId: n.componentId})
        SET c.componentName = n.componentName,
            c.filePath = n.filePath,
            c.projectPath = n.projectPath,
            c.language = n.language,
            c.framework = n.framework,
            c.description = n.description
        """)
    void mergeAll(@Param("nodes") List<Map<String, Object>> nodes);

    /**
     * 按项目路径删除所有 ComponentNode（DETACH DELETE 连带清理 INVOKES 边）。
     */
    @Query("MATCH (c:Component {projectPath: $projectPath}) DETACH DELETE c")
    void detachDeleteByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 批量创建 Component -[:INVOKES]-> ApiClient 边（组件内直调 API 的场景）。
     * 仅当 Component 与 ApiClient 都已落库时才建边。
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (c:Component {componentId: rel.componentId})
        MATCH (a:ApiClient {apiClientId: rel.apiClientId})
        MERGE (c)-[r:INVOKES]->(a)
        """)
    void createInvokesRelations(@Param("relations") List<Map<String, Object>> relations);

    /**
     * 读取某项目下尚无 descriptionEmbedding 的 Component 节点（待向量化）。
     */
    @Query("""
        MATCH (c:Component {projectPath: $projectPath})
        WHERE c.descriptionEmbedding IS NULL
        RETURN c
        """)
    List<ComponentNode> findWithoutEmbeddingByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 写回 Component 的 descriptionEmbedding 向量。
     */
    @Query("""
        MATCH (c:Component {componentId: $componentId})
        SET c.descriptionEmbedding = $embedding
        """)
    void updateDescriptionEmbedding(@Param("componentId") String componentId,
                                    @Param("embedding") List<Double> embedding);

    /**
     * 语义检索：按描述向量索引命中 Component 节点（带分数）。
     */
    @Query("""
        CALL db.index.vector.queryNodes('component_description_vector_index', $topK, $embedding)
        YIELD node AS c, score
        WHERE c.projectPath IN $projectPaths AND score >= $threshold
        RETURN c.componentId as componentId, c.componentName as componentName,
               c.filePath as filePath, c.projectPath as projectPath, c.description as description,
               score
        ORDER BY score DESC
        """)
    List<ComponentWithScore> findByDescriptionVectorIndexWithScoreByProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    record ComponentWithScore(String componentId, String componentName, String filePath,
                              String projectPath, String description, Double score) {
        public ComponentNode toComponentNode() {
            return ComponentNode.builder()
                .componentId(componentId)
                .componentName(componentName)
                .filePath(filePath)
                .projectPath(projectPath)
                .description(description)
                .build();
        }
    }
}
