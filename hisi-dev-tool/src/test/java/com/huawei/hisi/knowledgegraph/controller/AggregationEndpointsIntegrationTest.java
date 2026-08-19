package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.ModuleNode;
import com.huawei.hisi.neo4j.repository.*;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合端点集成测试 — Testcontainers 真实 Neo4j
 *
 * 运行: mvn test -Dtest=AggregationEndpointsIntegrationTest -DENABLE_NEO4J_INTEGRATION_TESTS=true
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "ENABLE_NEO4J_INTEGRATION_TESTS", matches = "true")
@DisplayName("聚合端点集成测试（Testcontainers Neo4j）")
class AggregationEndpointsIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4jContainer = new Neo4jContainer<>("neo4j:5.15.0")
        .withAdminPassword("testpassword");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("neo4j.uri", neo4jContainer::getBoltUrl);
        registry.add("neo4j.username", () -> "neo4j");
        registry.add("neo4j.password", () -> "testpassword");
    }

    @Autowired Driver driver;
    @Autowired Neo4jMethodNodeRepository methodNodeRepo;
    @Autowired ModuleNodeRepository moduleNodeRepo;
    @Autowired KnowledgeGraphController controller;

    static final String PROJ = "/test/proj";

    @BeforeEach
    void setUp() {
        try (var s = driver.session()) {
            s.run("MATCH (n) WHERE n.projectPath = $p DETACH DELETE n", Map.of("p", PROJ));
        }
    }

    @Test
    @DisplayName("dashboard 返回已聚合的领域数据（DomainNode）")
    void dashboard_returnsDomains() {
        // 插入 DomainNode 和 INTERACTS_WITH 关系（领域粒度）
        try (var s = driver.session()) {
            s.run("""
                CREATE (d:DomainNode {domainId: $id1, domainName: '订单域', confidence: 0.8,
                    methodCount: 10, classCount: 3, projectPath: $p})
                """, Map.of("id1", PROJ + ":domain:订单", "p", PROJ));
            s.run("""
                CREATE (d:DomainNode {domainId: $id2, domainName: '支付域', confidence: 0.7,
                    methodCount: 5, classCount: 2, projectPath: $p})
                """, Map.of("id2", PROJ + ":domain:支付", "p", PROJ));
            s.run("""
                MATCH (a:DomainNode {domainId: $src})
                MATCH (b:DomainNode {domainId: $tgt})
                MERGE (a)-[:INTERACTS_WITH {weight: 5}]->(b)
                """, Map.of("src", PROJ + ":domain:订单", "tgt", PROJ + ":domain:支付"));
        }

        var r = controller.getDashboard(null, List.of(PROJ), null);
        assertThat(r.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        var domains = (List<Map<String, Object>>) data.get("domains");
        @SuppressWarnings("unchecked")
        var kpis = (Map<String, Object>) data.get("kpis");

        assertThat(domains).hasSize(2);
        assertThat(kpis.get("totalDomains")).isEqualTo(2);
        assertThat(kpis.get("totalMethods")).isEqualTo(15); // 10+5
    }

    @Test
    @DisplayName("blast-radius 返回上下游影响面")
    void blastRadius_returnsCorrectImpact() {
        // 构造调用链: caller → target → callee
        var caller = MethodNode.builder().nodeId("a.b.Caller.m").className("a.b.Caller")
            .methodName("m").projectPath(PROJ).language("java").build();
        var target = MethodNode.builder().nodeId("a.b.Target.f").className("a.b.Target")
            .methodName("f").projectPath(PROJ).language("java").build();
        var callee = MethodNode.builder().nodeId("a.b.Callee.g").className("a.b.Callee")
            .methodName("g").projectPath(PROJ).language("java").build();

        methodNodeRepo.mergeAll(List.of(
            toMap(caller), toMap(target), toMap(callee)));

        try (var s = driver.session()) {
            s.run("""
                MATCH (a:Method {nodeId: $caller}), (b:Method {nodeId: $target})
                MERGE (a)-[:CALLS {callType: 'DIRECT'}]->(b)
                """, Map.of("caller", "a.b.Caller.m", "target", "a.b.Target.f"));
            s.run("""
                MATCH (a:Method {nodeId: $target}), (b:Method {nodeId: $callee})
                MERGE (a)-[:CALLS {callType: 'DIRECT'}]->(b)
                """, Map.of("target", "a.b.Target.f", "callee", "a.b.Callee.g"));
        }

        var r = controller.getBlastRadius("a.b.Target.f", 5, null, List.of(PROJ));
        assertThat(r.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        var center = (Map<String, Object>) data.get("centerNode");
        assertThat(center.get("className")).isEqualTo("a.b.Target");
        assertThat(center.get("methodName")).isEqualTo("f");

        @SuppressWarnings("unchecked")
        var downstream = (Map<String, Object>) data.get("downstream");
        assertThat((Integer) downstream.get("totalAffectedMethods")).isPositive();

        @SuppressWarnings("unchecked")
        var risk = (Map<String, Object>) data.get("riskSummary");
        assertThat(risk.get("overallRisk")).isIn("LOW", "MEDIUM", "HIGH");
    }

    @Test
    @DisplayName("hotspots 返回文件级热点列表（ChurnNode）")
    void hotspots_returnsFileLevel() {
        // ChurnNode（文件级 riskScore 落点）
        try (var s = driver.session()) {
            s.run("""
                CREATE (c:ChurnNode {nodeId: $id1, filePath: 'a.java', commitCount90d: 10,
                    riskScore: 0.1, projectPath: $p})
                """, Map.of("id1", PROJ + ":a.java", "p", PROJ));
            s.run("""
                CREATE (c:ChurnNode {nodeId: $id2, filePath: 'b.java', commitCount90d: 50,
                    riskScore: 0.9, projectPath: $p})
                """, Map.of("id2", PROJ + ":b.java", "p", PROJ));
        }
        // MethodNode 提供复杂度 + 包名（用于文件级 layerRole）
        var low = MethodNode.builder().nodeId("x.y.Low.m").className("x.y.Low")
            .methodName("m").projectPath(PROJ).language("java")
            .complexity(3).filePath("a.java").packageName("x.y.service").build();
        var high = MethodNode.builder().nodeId("x.y.High.m").className("x.y.High")
            .methodName("m").projectPath(PROJ).language("java")
            .complexity(45).filePath("b.java").packageName("x.y.service").build();

        methodNodeRepo.mergeAll(List.of(toMap(low), toMap(high)));

        var r = controller.getHotspots(null, List.of(PROJ), null, 20);
        assertThat(r.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        var hotspots = (List<Map<String, Object>>) data.get("hotspots");
        assertThat(hotspots).hasSize(2);
        // 应该按 riskScore 降序排列
        assertThat((Double) hotspots.get(0).get("riskScore")).isGreaterThanOrEqualTo(
                   (Double) hotspots.get(1).get("riskScore"));
    }

    @Test
    @DisplayName("methodDetail 返回新增字段 serviceName/language/framework")
    void methodDetail_returnsNewFields() {
        var node = MethodNode.builder().nodeId("test.Foo.bar").className("test.Foo")
            .methodName("bar").projectPath(PROJ).language("java")
            .serviceName("my-service").framework("spring").build();
        methodNodeRepo.mergeAll(List.of(toMap(node)));

        var r = controller.getMethodDetail("test.Foo.bar", null, List.of(PROJ));
        assertThat(r.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        var d = (Map<String, Object>) r.getData();
        assertThat(d.get("serviceName")).isEqualTo("my-service");
        assertThat(d.get("language")).isEqualTo("java");
        assertThat(d.get("framework")).isEqualTo("spring");
    }

    @Test
    @DisplayName("service-topology 返回跨服务拓扑节点和边")
    void serviceTopology_returnsNodesAndEdges() {
        var m1 = MethodNode.builder().nodeId("s1.Foo.a").className("s1.Foo").methodName("a")
            .projectPath(PROJ).language("java").serviceName("svc-a").build();
        var m2 = MethodNode.builder().nodeId("s2.Bar.b").className("s2.Bar").methodName("b")
            .projectPath(PROJ).language("java").serviceName("svc-b").build();
        methodNodeRepo.mergeAll(List.of(toMap(m1), toMap(m2)));

        try (var s = driver.session()) {
            s.run("""
                MATCH (a:Method {nodeId: 's1.Foo.a'}), (b:Method {nodeId: 's2.Bar.b'})
                MERGE (a)-[:CALLS {bridgeType: 'FEIGN', targetService: 'svc-b'}]->(b)
                """);
        }

        var r = controller.getServiceTopology(null, List.of(PROJ), null);
        assertThat(r.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        var services = (List<Map<String, Object>>) data.get("services");
        @SuppressWarnings("unchecked")
        var edges = (List<Map<String, Object>>) data.get("edges");

        assertThat(services).hasSize(2);
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).get("type")).isEqualTo("FEIGN");
    }

    @Test
    @DisplayName("dashboard 无 projectPaths 返回 400")
    void dashboard_noProjectPaths_returns400() {
        var r = controller.getDashboard(null, null, null);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("blast-radius 不存在的方法返回 404")
    void blastRadius_notFound_returns404() {
        var r = controller.getBlastRadius("not.exist", 5, null, List.of(PROJ));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(404);
    }

    // helper
    private Map<String, Object> toMap(MethodNode n) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("nodeId", n.getNodeId());
        m.put("className", n.getClassName());
        m.put("methodName", n.getMethodName());
        m.put("signature", n.getSignature());
        m.put("description", n.getDescription());
        m.put("filePath", n.getFilePath());
        m.put("startLine", n.getStartLine());
        m.put("endLine", n.getEndLine());
        m.put("complexity", n.getComplexity());
        m.put("methodBody", n.getMethodBody());
        m.put("projectPath", n.getProjectPath());
        m.put("serviceName", n.getServiceName());
        m.put("comment", n.getComment());
        m.put("thrownExceptions", n.getThrownExceptions());
        m.put("caughtExceptions", n.getCaughtExceptions());
        m.put("language", n.getLanguage());
        m.put("framework", n.getFramework());
        m.put("packageName", n.getPackageName());
        return m;
    }
}
