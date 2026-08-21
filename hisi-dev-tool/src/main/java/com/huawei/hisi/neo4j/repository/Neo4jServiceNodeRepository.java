package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.ServiceNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务节点 Repository (Neo4j)
 * 提供服务节点的 CRUD 和自定义查询方法
 */
@Repository
public interface Neo4jServiceNodeRepository extends Neo4jRepository<ServiceNode, String> {

    /**
     * 根据服务ID查询
     */
    Optional<ServiceNode> findByServiceId(String serviceId);

    /**
     * 根据服务名查询
     */
    Optional<ServiceNode> findByServiceName(String serviceName);

    /**
     * 根据项目路径查询
     */
    Optional<ServiceNode> findByProjectPath(String projectPath);

    /**
     * 根据技术栈查询
     */
    List<ServiceNode> findByTechStack(String techStack);

    /**
     * 删除服务节点
     */
    void deleteByServiceId(String serviceId);

    /**
     * 按项目路径删除所有服务节点（DETACH DELETE 连带清理 HAS_METHOD / HAS_ENTRY 边）。
     */
    @Query("MATCH (s:Service {projectPath: $projectPath}) DETACH DELETE s")
    void deleteByProjectPath(@Param("projectPath") String projectPath);

    /**
     * 查询服务包含的所有方法节点
     */
    @Query("""
        MATCH (service:Service {serviceId: $serviceId})-[:HAS_METHOD]->(method:Method)
        RETURN method
        """)
    List<com.huawei.hisi.neo4j.model.MethodNode> findMethodsByServiceId(@Param("serviceId") String serviceId);

    /**
     * 查询服务包含的所有入口点
     */
    @Query("""
        MATCH (service:Service {serviceId: $serviceId})-[:HAS_ENTRY]->(entry:EntryPoint)
        RETURN entry
        """)
    List<com.huawei.hisi.neo4j.model.EntryPointNode> findEntriesByServiceId(@Param("serviceId") String serviceId);

    /**
     * 统计服务的方法数量
     */
    @Query("""
        MATCH (service:Service {serviceId: $serviceId})-[:HAS_METHOD]->(method:Method)
        RETURN count(method)
        """)
    long countMethodsByServiceId(@Param("serviceId") String serviceId);

    /**
     * 统计服务的入口点数量
     */
    @Query("""
        MATCH (service:Service {serviceId: $serviceId})-[:HAS_ENTRY]->(entry:EntryPoint)
        RETURN count(entry)
        """)
    long countEntriesByServiceId(@Param("serviceId") String serviceId);

    /**
     * 批量 MERGE 保存服务节点（幂等，遇到重复 serviceId 会更新而非报错）
     */
    @Query("""
        UNWIND $nodes AS n
        MERGE (s:Service {serviceId: n.serviceId})
        SET s.serviceName = n.serviceName,
            s.description = n.description,
            s.projectPath = n.projectPath,
            s.language = n.language,
            s.framework = n.framework,
            s.techStack = n.techStack,
            s.version = n.version
        """)
    void mergeAll(@Param("nodes") List<Map<String, Object>> nodes);
}
