package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.MethodNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MethodNodeRepository 集成测试 (Testcontainers)
 *
 * 此测试需要 Docker 环境支持。
 * 运行此测试前请确保：
 * 1. Docker Desktop 已安装并运行
 * 2. 设置环境变量: ENABLE_NEO4J_INTEGRATION_TESTS=true
 *
 * 或者使用以下命令运行：
 * mvn test -Dtest=Neo4jIntegrationTest -DENABLE_NEO4J_INTEGRATION_TESTS=true
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "ENABLE_NEO4J_INTEGRATION_TESTS", matches = "true")
class Neo4jIntegrationTest {

    // 使用 Testcontainers 启动 Neo4j 容器
    @Container
    static Neo4jContainer<?> neo4jContainer = new Neo4jContainer<>("neo4j:5.15.0")
        .withAdminPassword("testpassword");

    // 动态配置 Neo4j 连接参数
    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("neo4j.uri", neo4jContainer::getBoltUrl);
        registry.add("neo4j.username", () -> "neo4j");
        registry.add("neo4j.password", () -> "testpassword");
    }

    @Autowired
    private Neo4jMethodNodeRepository repository;

    @Autowired
    private Neo4jTemplate neo4jTemplate;

    @Autowired
    private Driver driver;

    private MethodNode testMethod1;
    private MethodNode testMethod2;
    private MethodNode testMethod3;

    @BeforeEach
    void setUp() {
        // 清理数据库
        cleanupDatabase();

        // 创建测试数据
        testMethod1 = MethodNode.builder()
            .nodeId("com.example.service.UserService.getUser.abc123")
            .className("com.example.service.UserService")
            .methodName("getUser")
            .signature("getUser(String userId)")
            .filePath("src/main/java/com/example/service/UserService.java")
            .startLine(45)
            .endLine(80)
            .complexity(3)
            .projectPath("/projects/user-service")
            .serviceName("user-service")
            .comment("获取用户信息")
            .build();

        testMethod2 = MethodNode.builder()
            .nodeId("com.example.service.UserService.validateUser.def456")
            .className("com.example.service.UserService")
            .methodName("validateUser")
            .signature("validateUser(String userId)")
            .filePath("src/main/java/com/example/service/UserService.java")
            .startLine(82)
            .endLine(95)
            .complexity(2)
            .projectPath("/projects/user-service")
            .serviceName("user-service")
            .comment("验证用户")
            .build();

        testMethod3 = MethodNode.builder()
            .nodeId("com.example.dao.UserDao.findById.xyz789")
            .className("com.example.dao.UserDao")
            .methodName("findById")
            .signature("findById(String id)")
            .filePath("src/main/java/com/example/dao/UserDao.java")
            .startLine(20)
            .endLine(35)
            .complexity(1)
            .projectPath("/projects/user-service")
            .serviceName("user-service")
            .comment("根据ID查找用户")
            .build();

        // 保存测试方法节点
        testMethod1 = repository.save(testMethod1);
        testMethod2 = repository.save(testMethod2);
        testMethod3 = repository.save(testMethod3);
    }

    private void cleanupDatabase() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    // ==================== 测试 1: testSaveAndFindById ====================

    @Test
    @DisplayName("集成测试1: 保存并查询节点")
    void testSaveAndFindById() {
        // Given
        MethodNode newMethod = MethodNode.builder()
            .nodeId("com.example.controller.UserController.getUser.controller1")
            .className("com.example.controller.UserController")
            .methodName("getUser")
            .signature("getUser(@PathVariable String userId)")
            .filePath("src/main/java/com/example/controller/UserController.java")
            .startLine(30)
            .endLine(45)
            .complexity(2)
            .projectPath("/projects/user-service")
            .serviceName("user-service")
            .build();

        // When - 保存节点
        MethodNode saved = repository.save(newMethod);

        // Then - 验证保存成功
        assertNotNull(saved);
        assertEquals(newMethod.getNodeId(), saved.getNodeId());

        // When - 通过 ID 查询
        Optional<MethodNode> found = repository.findById(saved.getNodeId());

        // Then - 验证查询成功
        assertTrue(found.isPresent());
        assertEquals(newMethod.getClassName(), found.get().getClassName());
        assertEquals(newMethod.getMethodName(), found.get().getMethodName());
    }

    // ==================== 测试 2: testFindByNodeId ====================

    @Test
    @DisplayName("集成测试2: 按 nodeId 查询节点")
    void testFindByNodeId() {
        // When
        Optional<MethodNode> found = repository.findByNodeId(testMethod1.getNodeId());

        // Then
        assertTrue(found.isPresent());
        assertEquals("com.example.service.UserService", found.get().getClassName());
        assertEquals("getUser", found.get().getMethodName());
    }

    // ==================== 测试 3: testFindByProjectPath ====================

    @Test
    @DisplayName("集成测试3: 按项目路径查询所有节点")
    void testFindByProjectPath() {
        // When
        List<MethodNode> methods = repository.findByProjectPath("/projects/user-service");

        // Then
        assertEquals(3, methods.size());
        assertTrue(methods.stream().allMatch(m -> m.getProjectPath().equals("/projects/user-service")));
    }

    // ==================== 测试 4: testFindCallers ====================

    @Test
    @DisplayName("集成测试4: 查询调用者")
    void testFindCallers() {
        // Given - 创建调用关系
        createCallRelation(testMethod1.getNodeId(), testMethod2.getNodeId(), "DIRECT", 50);
        createCallRelation(testMethod3.getNodeId(), testMethod2.getNodeId(), "DIRECT", 25);

        // When
        List<MethodNode> callers = repository.findCallers(testMethod2.getNodeId());

        // Then
        assertEquals(2, callers.size());
    }

    // ==================== 测试 5: testFindCallees ====================

    @Test
    @DisplayName("集成测试5: 查询被调用者")
    void testFindCallees() {
        // Given - 创建调用关系
        createCallRelation(testMethod1.getNodeId(), testMethod2.getNodeId(), "DIRECT", 50);
        createCallRelation(testMethod1.getNodeId(), testMethod3.getNodeId(), "DIRECT", 55);

        // When
        List<MethodNode> callees = repository.findCallees(testMethod1.getNodeId());

        // Then
        assertEquals(2, callees.size());
    }

    /**
     * 创建调用关系
     */
    private void createCallRelation(String callerId, String calleeId, String callType, int callLine) {
        try (var session = driver.session()) {
            session.run("""
                MATCH (caller:Method {nodeId: $callerId})
                MATCH (callee:Method {nodeId: $calleeId})
                CREATE (caller)-[r:CALLS {callType: $callType, callLine: $callLine}]->(callee)
                """,
                org.neo4j.driver.Values.parameters(
                    "callerId", callerId,
                    "calleeId", calleeId,
                    "callType", callType,
                    "callLine", callLine
                ))
                .consume();
        }
    }
}
