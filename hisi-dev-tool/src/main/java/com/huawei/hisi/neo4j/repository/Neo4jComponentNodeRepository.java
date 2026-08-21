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
}
