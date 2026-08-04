package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SqlNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQL 节点 Repository (Neo4j)
 * 提供 SqlNode 的 CRUD 和自定义查询方法
 * 替代 PostgreSQL 的 MyBatisSqlRepository
 *
 * 主键设计：nodeId = projectPath + ":" + sqlId
 */
@Repository
public interface Neo4jSqlNodeRepository extends Neo4jRepository<SqlNode, String> {

    /**
     * 根据 nodeId 查询
     * nodeId 是主键，全局唯一
     */
    Optional<SqlNode> findByNodeId(String nodeId);

    /**
     * 根据 sqlId 和 projectPath 查询
     * 用于在构建调用关系时查找对应的 SqlNode
     */
    @Query("""
        MATCH (s:Sql {sqlId: $sqlId, projectPath: $projectPath})
        RETURN s
        """)
    Optional<SqlNode> findBySqlIdAndProjectPath(
        @Param("sqlId") String sqlId,
        @Param("projectPath") String projectPath
    );

    /**
     * 根据项目路径查询所有 SQL 节点
     * 用于项目数据清理和统计
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        RETURN s
        """)
    List<SqlNode> findByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 根据项目路径和 Mapper 接口查询
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        WHERE s.mapperInterface = $mapperInterface
        RETURN s
        """)
    List<SqlNode> findByMapperInterfaceAndProjectPath(
        @Param("mapperInterface") String mapperInterface,
        @Param("projectPath") String projectPath
    );

    /**
     * 根据语句类型和项目路径查询
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        WHERE s.statementType = $statementType
        RETURN s
        """)
    List<SqlNode> findByStatementTypeAndProjectPath(
        @Param("statementType") String statementType,
        @Param("projectPath") String projectPath
    );

    /**
     * 统计项目下的 SQL 节点数量
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        RETURN COUNT(s)
        """)
    long countByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 删除项目下的所有 SQL 节点（包括关联的 EXECUTES_SQL 关系）
     * 使用 DETACH DELETE 自动删除关联的关系
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        DETACH DELETE s
        """)
    void deleteByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 分批删除项目下的 SQL 节点，避免单事务内存溢出
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        WITH s LIMIT $batchSize
        DETACH DELETE s
        RETURN count(*) AS deleted
        """)
    long deleteByProjectPathBatch(@Param("projectPath") String projectPath, @Param("batchSize") int batchSize);

    /**
     * 批量 MERGE 保存 SQL 节点（幂等，遇到重复 nodeId 会更新而非报错）
     */
    @Query("""
        UNWIND $nodes AS n
        MERGE (s:Sql {nodeId: n.nodeId})
        SET s.sqlId = n.sqlId,
            s.statementType = n.statementType,
            s.sqlStatement = n.sqlStatement,
            s.parameterType = n.parameterType,
            s.resultType = n.resultType,
            s.resultMap = n.resultMap,
            s.mapperInterface = n.mapperInterface,
            s.methodName = n.methodName,
            s.xmlFilePath = n.xmlFilePath,
            s.projectPath = n.projectPath,
            s.language = n.language,
            s.framework = n.framework
        """)
    void mergeAll(@Param("nodes") List<Map<String, Object>> nodes);

    /**
     * 获取项目下所有不同的 Mapper 接口
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        RETURN DISTINCT s.mapperInterface as mapperInterface
        ORDER BY mapperInterface
        """)
    List<String> findDistinctMapperInterfaces(@Param("projectPath") String projectPath);

    /**
     * 统计项目下不同的 Mapper 接口数量
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        RETURN COUNT(DISTINCT s.mapperInterface)
        """)
    long countDistinctMapperInterfaces(@Param("projectPath") String projectPath);

    // ============================================================
    // EXECUTES_SQL 关系操作
    // ============================================================

    /**
     * 批量创建 EXECUTES_SQL 关系
     * 从 Method 节点到 Sql 节点
     * 使用 nodeId 作为 Sql 节点的唯一标识
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (m:Method {nodeId: rel.methodNodeId})
        MATCH (s:Sql {nodeId: rel.sqlNodeId})
        MERGE (m)-[r:EXECUTES_SQL]->(s)
        SET r.callLine = rel.callLine
        """)
    void createExecutesSqlRelations(@Param("relations") List<Map<String, Object>> relations);

    /**
     * 查询方法执行的 SQL 语句
     */
    @Query("""
        MATCH (m:Method {nodeId: $nodeId})-[:EXECUTES_SQL]->(s:Sql)
        RETURN s
        """)
    List<SqlNode> findSqlByMethodNode(@Param("nodeId") String nodeId);

    /**
     * 查询执行特定 SQL 的方法
     */
    @Query("""
        MATCH (m:Method)-[:EXECUTES_SQL]->(s:Sql {nodeId: $nodeId})
        RETURN m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               m.endLine as endLine, m.complexity as complexity, m.projectPath as projectPath,
               m.serviceName as serviceName
        """)
    List<MethodNode> findMethodsBySqlNode(@Param("nodeId") String nodeId);

    /**
     * 删除项目的所有 EXECUTES_SQL 关系
     * 注意：deleteByProjectPath 已经使用 DETACH DELETE 会自动删除关系
     * 此方法用于单独清理关系时使用
     */
    @Query("""
        MATCH (m:Method {projectPath: $projectPath})-[r:EXECUTES_SQL]->(:Sql)
        DELETE r
        """)
    void deleteExecutesSqlRelationsByProjectPath(@Param("projectPath") String projectPath);

    // ============================================================
    // SQL 向量操作
    // ============================================================

    /**
     * 更新 SQL 向量
     */
    @Query("""
        MATCH (s:Sql {nodeId: $nodeId})
        SET s.sqlEmbedding = $sqlEmbedding
        """)
    void updateSqlEmbedding(
        @Param("nodeId") String nodeId,
        @Param("sqlEmbedding") float[] sqlEmbedding
    );

    /**
     * SQL 向量相似度查询 (使用余弦相似度)
     * 用于 SQL 语义搜索
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath = $projectPath AND s.sqlEmbedding IS NOT NULL AND size(s.sqlEmbedding) = size($embedding)
        WITH s, vector.similarity.cosine(s.sqlEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN s
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<SqlNode> findBySqlVectorSimilarity(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * SQL 向量相似度查询 (带分数返回)
     * 用于 SQL 语义搜索，返回节点和相似度分数
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath = $projectPath AND s.sqlEmbedding IS NOT NULL AND size(s.sqlEmbedding) = size($embedding)
        WITH s, vector.similarity.cosine(s.sqlEmbedding, $embedding) AS similarity
        WHERE similarity >= $threshold
        RETURN s.nodeId as nodeId, s.sqlId as sqlId, s.statementType as statementType,
               s.sqlStatement as sqlStatement, s.parameterType as parameterType,
               s.resultType as resultType, s.resultMap as resultMap,
               s.mapperInterface as mapperInterface, s.methodName as methodName,
               s.xmlFilePath as xmlFilePath, s.projectPath as projectPath,
               s.sqlEmbedding as sqlEmbedding,
               similarity as score
        ORDER BY similarity DESC
        LIMIT $limit
        """)
    List<SqlWithScore> findBySqlVectorSimilarityWithScore(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("limit") int limit
    );

    /**
     * 清除项目的所有 SQL 向量
     * 用于全量重新生成
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        SET s.sqlEmbedding = null
        RETURN count(s) as clearedCount
        """)
    long clearSqlEmbeddings(@Param("projectPath") String projectPath);

    /**
     * 统计项目的 SQL 向量数量
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        WHERE s.sqlEmbedding IS NOT NULL
        RETURN count(s)
        """)
    long countWithSqlEmbedding(@Param("projectPath") String projectPath);

    // ============================================================
    // 图遍历查询（带 SQL 信息）
    // ============================================================

    /**
     * 图遍历结果 DTO（包含 SQL 信息）
     */
    record MethodWithSqlResult(
        String nodeId,
        String className,
        String methodName,
        String signature,
        String filePath,
        Integer startLine,
        Integer depth,
        String sqlId,
        String statementType,
        String sqlStatement,
        String mapperInterface
    ) {}

    /**
     * 根据入口 Key 获取调用链（包含 SQL 信息）
     * 使用 Neo4j 原生图遍历
     */
    @Query("""
        MATCH (ep:EntryPoint {entryKey: $entryKey, projectPath: $projectPath})
        WITH ep.methodNodeId as entryMethodId
        MATCH (entry:Method {nodeId: entryMethodId})
        MATCH path = (entry)-[:CALLS*0..:#{#maxDepth}]->(m:Method)
        OPTIONAL MATCH (m)-[:EXECUTES_SQL]->(s:Sql)
        WITH m, length(path) as depth, s
        RETURN DISTINCT m.nodeId as nodeId, m.className as className, m.methodName as methodName,
               m.signature as signature, m.filePath as filePath, m.startLine as startLine,
               depth,
               s.sqlId as sqlId, s.statementType as statementType, s.sqlStatement as sqlStatement,
               s.mapperInterface as mapperInterface
        ORDER BY depth, nodeId
        """)
    List<MethodWithSqlResult> getCallChainWithSqlByEntryKey(
        @Param("entryKey") String entryKey,
        @Param("projectPath") String projectPath,
        @Param("maxDepth") int maxDepth
    );

    /**
     * 按语句类型统计
     */
    @Query("""
        MATCH (s:Sql {projectPath: $projectPath})
        WHERE s.statementType = $statementType
        RETURN COUNT(s)
        """)
    long countByStatementTypeAndProjectPath(
        @Param("statementType") String statementType,
        @Param("projectPath") String projectPath
    );

    /**
     * SQL 向量相似度查询 (使用 Neo4j 向量索引)
     * 用于 SQL_SNIPPET 查询类型（向量索引可用时）
     */
    @Query("""
        CALL db.index.vector.queryNodes('sql_vector_index', $topK, $embedding)
        YIELD node AS s, score
        WHERE s.projectPath = $projectPath AND score >= $threshold
        RETURN s
        ORDER BY score DESC
        """)
    List<SqlNode> findBySqlVectorIndex(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * SQL 向量相似度查询 (使用 Neo4j 向量索引，带分数返回)
     * 用于 SQL_SNIPPET 查询类型（向量索引可用时），返回节点和相似度分数
     */
    @Query("""
        CALL db.index.vector.queryNodes('sql_vector_index', $topK, $embedding)
        YIELD node AS s, score
        WHERE s.projectPath = $projectPath AND score >= $threshold
        RETURN s.nodeId as nodeId, s.sqlId as sqlId, s.statementType as statementType,
               s.sqlStatement as sqlStatement, s.parameterType as parameterType,
               s.resultType as resultType, s.resultMap as resultMap,
               s.mapperInterface as mapperInterface, s.methodName as methodName,
               s.xmlFilePath as xmlFilePath, s.projectPath as projectPath,
               s.sqlEmbedding as sqlEmbedding,
               score
        ORDER BY score DESC
        """)
    List<SqlWithScore> findBySqlVectorIndexWithScore(
        @Param("projectPath") String projectPath,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK
    );

    /**
     * SQL 向量搜索结果 DTO (包含 SQL 节点和相似度分数)
     */
    record SqlWithScore(
        String nodeId,
        String sqlId,
        String statementType,
        String sqlStatement,
        String parameterType,
        String resultType,
        String resultMap,
        String mapperInterface,
        String methodName,
        String xmlFilePath,
        String projectPath,
        float[] sqlEmbedding,
        Double score
    ) {
        /**
         * 转换为 SqlNode 对象
         */
        public SqlNode toSqlNode() {
            return SqlNode.builder()
                    .nodeId(nodeId)
                    .sqlId(sqlId)
                    .statementType(statementType)
                    .sqlStatement(sqlStatement)
                    .parameterType(parameterType)
                    .resultType(resultType)
                    .resultMap(resultMap)
                    .mapperInterface(mapperInterface)
                    .methodName(methodName)
                    .xmlFilePath(xmlFilePath)
                    .projectPath(projectPath)
                    .sqlEmbedding(sqlEmbedding)
                    .build();
        }
    }

    // ==================== 批量上下文查询（N+1 问题优化） ====================

    /**
     * 批量根据 methodNodeId 查找关联的 SQL 节点
     * 用于 buildSearchResultItems 批量获取 SQL 摘要，解决 N+1 查询问题
     */
    @Query("""
        MATCH (m:Method)-[:EXECUTES_SQL]->(s:Sql)
        WHERE m.nodeId IN $methodNodeIds
        RETURN m.nodeId as methodNodeId, s
        """)
    List<SqlNodeByMethod> findByMethodNodeIds(@Param("methodNodeIds") List<String> methodNodeIds);

    /**
     * 批量根据 sqlNodeId 查找关联的方法节点
     * 用于 searchBySqlSnippetWithScores 批量反查方法，解决 N+1 查询问题
     */
    @Query("""
        MATCH (m:Method)-[:EXECUTES_SQL]->(s:Sql)
        WHERE s.nodeId IN $sqlNodeIds
        RETURN s.nodeId as sqlNodeId, m
        """)
    List<MethodBySqlNode> findMethodsBySqlNodeIds(@Param("sqlNodeIds") List<String> sqlNodeIds);

    /**
     * SQL 节点按方法分组 DTO
     */
    record SqlNodeByMethod(
        String methodNodeId,
        String nodeId,
        String sqlId,
        String statementType,
        String sqlStatement,
        String parameterType,
        String resultType,
        String resultMap,
        String mapperInterface,
        String methodName,
        String xmlFilePath,
        String projectPath
    ) {}

    /**
     * 方法节点按 SQL 分组 DTO
     */
    record MethodBySqlNode(
        String sqlNodeId,
        String methodNodeId
    ) {}

    // ==================== 批量多项目查询（N+1 优化） ====================

    /**
     * 批量查询多个项目的 SQL 节点
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath IN $projectPaths
        RETURN s
        """)
    List<SqlNode> findByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量查询多个项目的不同 Mapper 接口
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath IN $projectPaths
        RETURN DISTINCT s.mapperInterface as mapperInterface
        ORDER BY mapperInterface
        """)
    List<String> findDistinctMapperInterfacesByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量按 Mapper 接口查询多个项目的 SQL 节点
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath IN $projectPaths AND s.mapperInterface = $mapperInterface
        RETURN s
        """)
    List<SqlNode> findByMapperInterfaceAndProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("mapperInterface") String mapperInterface
    );

    /**
     * 批量按语句类型查询多个项目的 SQL 节点
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath IN $projectPaths AND s.statementType = $statementType
        RETURN s
        """)
    List<SqlNode> findByStatementTypeAndProjectPaths(
        @Param("projectPaths") List<String> projectPaths,
        @Param("statementType") String statementType
    );

    /**
     * 批量统计多个项目的 SQL 节点数量
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath IN $projectPaths
        RETURN COUNT(s)
        """)
    long countByProjectPaths(@Param("projectPaths") List<String> projectPaths);

    /**
     * 批量统计多个项目的不同 Mapper 接口数量
     */
    @Query("""
        MATCH (s:Sql)
        WHERE s.projectPath IN $projectPaths
        RETURN COUNT(DISTINCT s.mapperInterface)
        """)
    long countDistinctMapperInterfacesByProjectPaths(@Param("projectPaths") List<String> projectPaths);
}
