package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 生成检查点 Repository (Neo4j)
 * 提供检查点的 CRUD 和 MERGE（upsert）操作
 */
@Repository
public interface Neo4jGenerationCheckpointRepository extends Neo4jRepository<GenerationCheckpointNode, String> {

    /**
     * 根据项目路径查询检查点
     */
    Optional<GenerationCheckpointNode> findByProjectPath(String projectPath);

    /**
     * MERGE-based upsert：按 projectPath 合并，更新其余字段
     */
    @Query("""
        MERGE (c:GenerationCheckpoint {projectPath: $projectPath})
        SET c.lastCommit = $lastCommit,
            c.lastBranch = $lastBranch,
            c.generatedAt = datetime()
        RETURN c
        """)
    GenerationCheckpointNode upsertCheckpoint(
            @Param("projectPath") String projectPath,
            @Param("lastCommit") String lastCommit,
            @Param("lastBranch") String lastBranch);

    /**
     * 删除项目下的检查点
     */
    @Query("""
        MATCH (c:GenerationCheckpoint {projectPath: $projectPath})
        DETACH DELETE c
        """)
    void deleteByProjectPath(@Param("projectPath") String projectPath);
}
