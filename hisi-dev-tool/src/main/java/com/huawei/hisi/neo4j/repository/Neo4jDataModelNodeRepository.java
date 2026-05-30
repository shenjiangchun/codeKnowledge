package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.DataModelNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface Neo4jDataModelNodeRepository extends Neo4jRepository<DataModelNode, String> {

    List<DataModelNode> findByProjectPath(String projectPath);

    long countByProjectPath(String projectPath);

    void deleteByProjectPath(String projectPath);

    @Query("""
        UNWIND $relations as rel
        MATCH (m:Method {nodeId: rel.methodNodeId})
        MATCH (dm:DataModel {nodeId: rel.dataModelNodeId})
        MERGE (m)-[r:USES_MODEL]->(dm)
        SET r.usageType = rel.usageType
    """)
    void createUsesModelRelations(@Param("relations") List<Map<String, Object>> relations);

    @Query("""
        MATCH (:Method)-[r:USES_MODEL]->(dm:DataModel)
        WHERE dm.projectPath = $projectPath
        DELETE r
    """)
    void deleteUsesModelRelationsByProjectPath(@Param("projectPath") String projectPath);

    @Query("""
        MATCH (m:Method)-[r:USES_MODEL]->(dm:DataModel)
        WHERE dm.className = $className AND dm.projectPath IN $projectPaths
        RETURN m.nodeId as nodeId, m.className as className,
               m.methodName as methodName, r.usageType as usageType
    """)
    List<DataModelUsage> findMethodsUsingDataModel(
        @Param("className") String className,
        @Param("projectPaths") List<String> projectPaths);

    @Query("""
        MATCH (:Method)-[r:USES_MODEL]->(:DataModel {projectPath: $projectPath})
        RETURN COUNT(r)
    """)
    int countUsesModelRelations(@Param("projectPath") String projectPath);

    record DataModelUsage(String nodeId, String className, String methodName, String usageType) {}
}
