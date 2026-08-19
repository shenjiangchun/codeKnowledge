package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.ChurnNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChurnNodeRepository extends Neo4jRepository<ChurnNode, String> {

    @Query("MATCH (c:ChurnNode {projectPath: $projectPath}) DETACH DELETE c RETURN count(c)")
    long deleteByProjectPath(@Param("projectPath") String projectPath);
}
