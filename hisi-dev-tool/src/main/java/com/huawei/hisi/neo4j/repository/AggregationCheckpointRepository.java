package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.AggregationCheckpoint;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AggregationCheckpointRepository extends Neo4jRepository<AggregationCheckpoint, String> {

    @Query("MATCH (a:AggregationCheckpoint {projectPath: $projectPath, stageName: $stageName}) RETURN a")
    Optional<AggregationCheckpoint> findByProjectPathAndStageName(
        @Param("projectPath") String projectPath, @Param("stageName") String stageName);

    @Query("MATCH (a:AggregationCheckpoint {projectPath: $projectPath}) RETURN a ORDER BY a.stageName")
    List<AggregationCheckpoint> findAllByProjectPath(@Param("projectPath") String projectPath);

    @Query("MATCH (a:AggregationCheckpoint {projectPath: $projectPath}) DETACH DELETE a RETURN count(a)")
    long deleteByProjectPath(@Param("projectPath") String projectPath);
}
