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
     * 根据项目路径查询检查点（使用显式 Cypher 查询确保正确匹配）
     * LIMIT 1 ensures single result even if duplicates exist
     */
    @Query("""
        MATCH (c:GenerationCheckpoint {projectPath: $projectPath})
        RETURN c
        LIMIT 1
        """)
    Optional<GenerationCheckpointNode> findByProjectPath(@Param("projectPath") String projectPath);

    /**
     * MERGE-based upsert：按 projectPath 合并，更新其余字段
     * 使用 apoc.create.uuid() 或随机 UUID 确保 checkpointId 存在
     * LIMIT 1 ensures single result even if duplicates exist
     */
    @Query("""
        MERGE (c:GenerationCheckpoint {projectPath: $projectPath})
        ON CREATE SET c.checkpointId = randomUUID()
        SET c.lastCommit = $lastCommit,
            c.lastBranch = $lastBranch,
            c.generatedAt = datetime()
        RETURN c
        LIMIT 1
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
