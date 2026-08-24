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
}
