package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.ModuleNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModuleNodeRepository extends Neo4jRepository<ModuleNode, String> {

    @Query("MATCH (m:ModuleNode {projectPath: $projectPath}) DETACH DELETE m RETURN count(m)")
    long deleteByProjectPath(@Param("projectPath") String projectPath);

    @Query("MATCH (m:ModuleNode {level: 'build-module'}) WHERE m.projectPath IN $projectPaths RETURN m")
    List<ModuleNode> findBuildModulesByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    @Query("MATCH (m:ModuleNode {level: 'build-module', projectPath: $projectPath}) DETACH DELETE m RETURN count(m)")
    long deleteBuildModulesByProjectPath(@Param("projectPath") String projectPath);
}
