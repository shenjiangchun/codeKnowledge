package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.LogChunkNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LogChunk Neo4j Repository
 * 用于日志向量检索和相似度匹配
 *
 * Task 5: Repository for LogChunk node operations
 */
@Repository
public interface Neo4jLogChunkRepository extends Neo4jRepository<LogChunkNode, String> {

    /**
     * 根据nodeId查找节点
     */
    Optional<LogChunkNode> findByNodeId(String nodeId);

    /**
     * 根据指纹查找节点
     */
    Optional<LogChunkNode> findByFingerprint(String fingerprint);

    /**
     * 根据项目路径查找所有节点
     */
    List<LogChunkNode> findByProjectPath(String projectPath);

    /**
     * 向量相似度检索
     *
     * @param queryVector 查询向量
     * @param threshold 相似度阈值 (e.g., 0.85)
     * @param limit 返回数量限制
     * @return 相似日志列表
     */
    @Query("""
        CALL db.index.vector.queryNodes('logEmbedding', $limit, $queryVector)
        YIELD node AS l, score
        WHERE score >= $threshold
        RETURN l.nodeId AS nodeId, l.errorType AS errorType, l.message AS message,
               l.fingerprint AS fingerprint, l.reportId AS reportId, score
        """)
    List<Map<String, Object>> findSimilarByVector(
        @Param("queryVector") List<Double> queryVector,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );
}