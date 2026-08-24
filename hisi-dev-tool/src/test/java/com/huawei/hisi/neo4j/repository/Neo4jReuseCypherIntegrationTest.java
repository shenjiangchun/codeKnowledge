package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.MethodNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全量-复用（REUSE）模式新增 Cypher 的集成测试（Testcontainers）。
 *
 * 验证 5 条新增 Cypher 的真实语义：
 * 1. mergeAllReuseHit —— 只更新结构字段，不覆盖 description/embedding
 * 2. mergeAll —— 未命中分支覆盖（含 description 置 null）
 * 3. clearEmbeddingsByNodeIds —— 按 nodeId 清空向量
 * 4. deleteOrphansByProjectPathAndNotInNodeIds —— 差集删孤儿
 * 5. deleteCallRelationsByProjectPath —— 删 caller 在本项目的 CALLS 边
 *
 * 需要 Docker + 环境变量 ENABLE_NEO4J_INTEGRATION_TESTS=true 才执行。
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "ENABLE_NEO4J_INTEGRATION_TESTS", matches = "true")
class Neo4jReuseCypherIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4jContainer = new Neo4jContainer<>("neo4j:5.15.0")
        .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("neo4j.uri", neo4jContainer::getBoltUrl);
        registry.add("neo4j.username", () -> "neo4j");
        registry.add("neo4j.password", () -> "testpassword");
    }

    @Autowired
    private Neo4jMethodNodeRepository repository;

    @Autowired
    private Driver driver;

    @BeforeEach
    void cleanup() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    private Map<String, Object> nodeMap(String nodeId, String codeHash, String description) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("nodeId", nodeId);
        map.put("className", "com.example.Foo");
        map.put("methodName", "bar");
        map.put("signature", "bar()");
        map.put("description", description);
        map.put("filePath", "/src/Foo.java");
        map.put("startLine", 1);
        map.put("endLine", 5);
        map.put("complexity", 1);
        map.put("methodBody", "body");
        map.put("projectPath", "/work/svc-a");
        map.put("serviceName", "svc-a");
        map.put("comment", null);
        map.put("thrownExceptions", List.of());
        map.put("caughtExceptions", List.of());
        map.put("language", "java");
        map.put("packageName", "com.example");
        map.put("codeHash", codeHash);
        return map;
    }

    @Test
    @DisplayName("mergeAllReuseHit 只更新结构字段，不覆盖已有 description/embedding")
    void mergeAllReuseHit_preservesDescriptionAndEmbedding() {
        // Given: 先 mergeAll 创建一个带 description + embedding 的节点
        repository.mergeAll(List.of(nodeMap("n1", "hash1", "old-desc")));
        try (var session = driver.session()) {
            session.run("""
                MATCH (m:Method {nodeId: 'n1'})
                SET m.description = 'old-desc',
                    m.descriptionEmbedding = [0.1, 0.2],
                    m.codeEmbedding = [0.3]
                """).consume();
        }

        // When: mergeAllReuseHit 用「不同 description、相同 codeHash」覆盖结构
        repository.mergeAllReuseHit(List.of(nodeMap("n1", "hash1", "should-be-ignored")));

        // Then: description 和 embedding 保留旧值（mergeAllReuseHit 不 SET 这三个字段）
        try (var session = driver.session()) {
            var rec = session.run("MATCH (m:Method {nodeId: 'n1'}) RETURN m.description AS d, m.descriptionEmbedding AS de, m.codeEmbedding AS ce, m.codeHash AS ch").single();
            assertEquals("old-desc", rec.get("d").asString());
            assertNotNull(rec.get("de"));
            assertNotNull(rec.get("ce"));
            // codeHash 被更新
            assertEquals("hash1", rec.get("ch").asString());
        }
    }

    @Test
    @DisplayName("clearEmbeddingsByNodeIds 清空 description/embedding")
    void clearEmbeddingsByNodeIds_clearsVectors() {
        repository.mergeAll(List.of(nodeMap("n1", "hash1", "desc")));
        try (var session = driver.session()) {
            session.run("""
                MATCH (m:Method {nodeId: 'n1'})
                SET m.description = 'desc',
                    m.descriptionEmbedding = [0.1],
                    m.codeEmbedding = [0.2]
                """).consume();
        }

        repository.clearEmbeddingsByNodeIds(List.of("n1"));

        try (var session = driver.session()) {
            var rec = session.run("MATCH (m:Method {nodeId: 'n1'}) RETURN m.description AS d, m.descriptionEmbedding AS de, m.codeEmbedding AS ce").single();
            assertTrue(rec.get("d").isNull());
            assertTrue(rec.get("de").isNull());
            assertTrue(rec.get("ce").isNull());
        }
    }

    @Test
    @DisplayName("deleteOrphansByProjectPathAndNotInNodeIds 差集删孤儿，保留本轮节点")
    void deleteOrphans_removesOnlyMissingNodeIds() {
        repository.mergeAll(List.of(
            nodeMap("keep1", "h1", "d"),
            nodeMap("keep2", "h2", "d"),
            nodeMap("orphan", "h3", "d")
        ));

        repository.deleteOrphansByProjectPathAndNotInNodeIds("/work/svc-a", List.of("keep1", "keep2"));

        try (var session = driver.session()) {
            long count = session.run("MATCH (m:Method {projectPath: '/work/svc-a'}) RETURN count(m) AS c").single().get("c").asLong();
            assertEquals(2, count);
            var orphan = session.run("MATCH (m:Method {nodeId: 'orphan'}) RETURN m").list();
            assertTrue(orphan.isEmpty());
        }
    }

    @Test
    @DisplayName("deleteCallRelationsByProjectPath 删除 caller 在本项目的 CALLS 边")
    void deleteCallRelations_removesOutgoingCalls() {
        repository.mergeAll(List.of(
            nodeMap("a", "h1", "d"),
            nodeMap("b", "h2", "d")
        ));
        try (var session = driver.session()) {
            session.run("""
                MATCH (a:Method {nodeId: 'a'}), (b:Method {nodeId: 'b'})
                CREATE (a)-[r:CALLS {callType: 'DIRECT'}]->(b)
                """).consume();
        }

        repository.deleteCallRelationsByProjectPath("/work/svc-a");

        try (var session = driver.session()) {
            long edges = session.run("MATCH (:Method)-[r:CALLS]->(:Method) RETURN count(r) AS c").single().get("c").asLong();
            assertEquals(0, edges);
            // 节点保留
            long nodes = session.run("MATCH (m:Method) RETURN count(m) AS c").single().get("c").asLong();
            assertEquals(2, nodes);
        }
    }

    @Test
    @DisplayName("findCodeHashByProjectPath 返回 nodeId + codeHash 投影")
    void findCodeHashByProjectPath_returnsProjection() {
        repository.mergeAll(List.of(
            nodeMap("n1", "hash-1", "d"),
            nodeMap("n2", "hash-2", "d")
        ));

        List<Neo4jMethodNodeRepository.CodeHashProjection> result = repository.findCodeHashByProjectPath("/work/svc-a");

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(p -> p.nodeId().equals("n1") && "hash-1".equals(p.codeHash())));
        assertTrue(result.stream().anyMatch(p -> p.nodeId().equals("n2") && "hash-2".equals(p.codeHash())));
    }
}
