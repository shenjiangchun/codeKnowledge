package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.DomainNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DomainNodeRepository extends Neo4jRepository<DomainNode, String> {

    @Query("MATCH (d:DomainNode {projectPath: $projectPath}) DETACH DELETE d RETURN count(d)")
    long deleteByProjectPath(@Param("projectPath") String projectPath);
}
