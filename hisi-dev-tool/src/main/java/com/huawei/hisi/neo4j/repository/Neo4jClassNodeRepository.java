package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.ClassNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ClassNode Repository (Neo4j)
 * 类节点的 CRUD、HAS_METHOD 边、以及类级语义检索。
 */
@Repository
public interface Neo4jClassNodeRepository extends Neo4jRepository<ClassNode, String> {

    Optional<ClassNode> findByClassId(String classId);

    List<ClassNode> findByProjectPath(String projectPath);

    @Query("MATCH (c:Class) WHERE c.projectPath IN $projectPaths RETURN c")
    List<ClassNode> findByProjectPathIn(@Param("projectPaths") List<String> projectPaths);

    void deleteByProjectPath(String projectPath);

    /**
     * 更新类职责（LLM 补全用）。
     */
    @Query("MATCH (c:Class {classId: $classId}) SET c.classRole = $classRole, c.classRoleSource = $classRoleSource")
    void updateClassRole(
        @Param("classId") String classId,
        @Param("classRole") String classRole,
        @Param("classRoleSource") String classRoleSource);

    /**
     * 批量 MERGE 保存类节点（幂等，遇到重复 classId 会更新而非报错）。
     * 与 MethodNode.mergeAll 同模式：UNWIND + MERGE，不走 Spring Data 事务。
     */
    @Query("""
        UNWIND $nodes AS n
        MERGE (c:Class {classId: n.classId})
        SET c.className = n.className,
            c.packageName = n.packageName,
            c.projectPath = n.projectPath,
            c.filePath = n.filePath,
            c.language = n.language,
            c.classRole = n.classRole,
            c.classRoleSource = n.classRoleSource
        """)
    void mergeAll(@Param("nodes") List<Map<String, Object>> nodes);

    /**
     * 建立 ClassNode -[:HAS_METHOD]-> Method 边（按 className 匹配该类所有方法）。
     */
    @Query("""
        MATCH (c:Class {classId: $classId})
        MATCH (m:Method {projectPath: $projectPath, className: $className})
        MERGE (c)-[:HAS_METHOD]->(m)
        """)
    void connectHasMethod(
        @Param("classId") String classId,
        @Param("projectPath") String projectPath,
        @Param("className") String className);

    /**
     * 按项目路径删除所有 ClassNode（DETACH DELETE 连带清理 BELONGS_TO / HAS_METHOD 边）。
     */
    @Query("MATCH (c:Class {projectPath: $projectPath}) DETACH DELETE c")
    void detachDeleteByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 按文件路径删除类节点（增量构建用，删除变更文件的类，避免幽灵类节点）。
     */
    @Query("MATCH (c:Class {projectPath: $projectPath, filePath: $filePath}) DETACH DELETE c")
    void detachDeleteByFilePathAndProjectPath(
        @Param("projectPath") String projectPath,
        @Param("filePath") String filePath);

    /**
     * 批量创建类级 import 边（ClassNode -[:IMPORTS]-> ClassNode）。
     * 仅当 source 和 target 都是已落库的 ClassNode 时才建边。
     */
    @Query("""
        UNWIND $relations AS rel
        MATCH (source:Class {classId: rel.sourceClassId})
        MATCH (target:Class {classId: rel.targetClassId})
        MERGE (source)-[r:IMPORTS]->(target)
        SET r.projectPath = rel.projectPath
        """)
    void createClassImportsRelations(@Param("relations") List<Map<String, Object>> relations);

    /**
     * 类级向量相似度查询（用 ClassNode.descriptionEmbedding 向量索引）。
     */
    @Query("""
        CALL db.index.vector.queryNodes('classNode_description_vector_index', $topK, $embedding)
        YIELD node AS c, score
        WHERE c.projectPath IN $projectPaths AND score >= $threshold
        RETURN c
        ORDER BY score DESC
        """)
    List<ClassNode> findByDescriptionVectorIndex(
        @Param("projectPaths") List<String> projectPaths,
        @Param("embedding") List<Double> embedding,
        @Param("threshold") double threshold,
        @Param("topK") int topK);
}
