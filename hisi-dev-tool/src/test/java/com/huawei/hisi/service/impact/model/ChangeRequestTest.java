package com.huawei.hisi.service.impact.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChangeRequest 单元测试
 *
 * 测试变更请求模型
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("ChangeRequest 单元测试")
class ChangeRequestTest {

    @Test
    @DisplayName("测试构建 ChangeRequest - 基本属性")
    void testBuildBasicChangeRequest() {
        ChangeRequest request = ChangeRequest.builder()
                .className("com.example.UserService")
                .methodName("getUser")
                .projectId("project-001")
                .description("修改用户查询逻辑")
                .build();

        assertEquals("com.example.UserService", request.getClassName());
        assertEquals("getUser", request.getMethodName());
        assertEquals("project-001", request.getProjectId());
        assertEquals("修改用户查询逻辑", request.getDescription());
    }

    @Test
    @DisplayName("测试默认变更类型")
    void testDefaultChangeType() {
        ChangeRequest request = ChangeRequest.builder()
                .className("TestClass")
                .methodName("testMethod")
                .build();

        assertEquals(ChangeRequest.ChangeType.MODIFY, request.getChangeType());
    }

    @Test
    @DisplayName("测试不同变更类型")
    void testDifferentChangeTypes() {
        ChangeRequest addRequest = ChangeRequest.builder()
                .changeType(ChangeRequest.ChangeType.ADD)
                .build();
        assertEquals(ChangeRequest.ChangeType.ADD, addRequest.getChangeType());

        ChangeRequest deleteRequest = ChangeRequest.builder()
                .changeType(ChangeRequest.ChangeType.DELETE)
                .build();
        assertEquals(ChangeRequest.ChangeType.DELETE, deleteRequest.getChangeType());

        ChangeRequest refactorRequest = ChangeRequest.builder()
                .changeType(ChangeRequest.ChangeType.REFACTOR)
                .build();
        assertEquals(ChangeRequest.ChangeType.REFACTOR, refactorRequest.getChangeType());
    }

    @Test
    @DisplayName("测试变更类型枚举")
    void testChangeTypeEnum() {
        assertEquals(4, ChangeRequest.ChangeType.values().length);
        assertEquals(ChangeRequest.ChangeType.ADD, ChangeRequest.ChangeType.valueOf("ADD"));
        assertEquals(ChangeRequest.ChangeType.MODIFY, ChangeRequest.ChangeType.valueOf("MODIFY"));
        assertEquals(ChangeRequest.ChangeType.DELETE, ChangeRequest.ChangeType.valueOf("DELETE"));
        assertEquals(ChangeRequest.ChangeType.REFACTOR, ChangeRequest.ChangeType.valueOf("REFACTOR"));
    }

    @Test
    @DisplayName("测试包含文件路径信息")
    void testWithFilePathInfo() {
        ChangeRequest request = ChangeRequest.builder()
                .className("UserService")
                .methodName("save")
                .filePath("src/main/java/com/example/UserService.java")
                .startLine(50)
                .endLine(75)
                .build();

        assertEquals("src/main/java/com/example/UserService.java", request.getFilePath());
        assertEquals(50, request.getStartLine());
        assertEquals(75, request.getEndLine());
    }

    @Test
    @DisplayName("测试包含代码片段")
    void testWithCodeSnippets() {
        String originalCode = "public void save(User user) { userDao.save(user); }";
        String newCode = "public void save(User user) { validate(user); userDao.save(user); }";

        ChangeRequest request = ChangeRequest.builder()
                .className("UserService")
                .methodName("save")
                .originalCode(originalCode)
                .newCode(newCode)
                .build();

        assertEquals(originalCode, request.getOriginalCode());
        assertEquals(newCode, request.getNewCode());
    }

    @Test
    @DisplayName("测试包含上下文")
    void testWithContext() {
        ChangeRequest request = ChangeRequest.builder()
                .className("UserService")
                .methodName("getUser")
                .context(List.of("用户登录场景", "订单查询场景"))
                .build();

        assertNotNull(request.getContext());
        assertEquals(2, request.getContext().size());
        assertTrue(request.getContext().contains("用户登录场景"));
    }

    @Test
    @DisplayName("测试空构造和 setter")
    void testEmptyConstructorAndSetter() {
        ChangeRequest request = new ChangeRequest();
        request.setClassName("TestClass");
        request.setMethodName("testMethod");
        request.setChangeType(ChangeRequest.ChangeType.ADD);
        request.setDescription("新增方法");
        request.setFilePath("test/Test.java");

        assertEquals("TestClass", request.getClassName());
        assertEquals("testMethod", request.getMethodName());
        assertEquals(ChangeRequest.ChangeType.ADD, request.getChangeType());
    }

    @Test
    @DisplayName("测试全参数构造")
    void testAllArgsConstructor() {
        ChangeRequest request = new ChangeRequest(
                "com.example.OrderService",
                "createOrder",
                "project-001",
                ChangeRequest.ChangeType.ADD,
                "新增订单创建方法",
                "OrderService.java",
                10,
                50,
                null,
                "public void createOrder() {}",
                List.of()
        );

        assertEquals("com.example.OrderService", request.getClassName());
        assertEquals("createOrder", request.getMethodName());
        assertEquals(ChangeRequest.ChangeType.ADD, request.getChangeType());
        assertEquals(10, request.getStartLine());
        assertEquals(50, request.getEndLine());
    }

    @Test
    @DisplayName("测试边界行号")
    void testBoundaryLineNumbers() {
        // 最小行号
        ChangeRequest minRequest = ChangeRequest.builder()
                .startLine(1)
                .endLine(1)
                .build();
        assertEquals(1, minRequest.getStartLine());
        assertEquals(1, minRequest.getEndLine());

        // 大行号
        ChangeRequest maxRequest = ChangeRequest.builder()
                .startLine(1000)
                .endLine(2000)
                .build();
        assertEquals(1000, maxRequest.getStartLine());
        assertEquals(2000, maxRequest.getEndLine());
    }

    @Test
    @DisplayName("测试空行号")
    void testNullLineNumbers() {
        ChangeRequest request = ChangeRequest.builder()
                .className("TestClass")
                .methodName("testMethod")
                .build();

        assertNull(request.getStartLine());
        assertNull(request.getEndLine());
    }

    @Test
    @DisplayName("测试长类名")
    void testLongClassName() {
        String longClassName = "com.example.project.module.submodule.service.impl.UserServiceImpl";
        ChangeRequest request = ChangeRequest.builder()
                .className(longClassName)
                .methodName("process")
                .build();

        assertEquals(longClassName, request.getClassName());
    }

    @Test
    @DisplayName("测试方法签名变更场景")
    void testMethodSignatureChange() {
        ChangeRequest request = ChangeRequest.builder()
                .className("UserService")
                .methodName("getUser")
                .changeType(ChangeRequest.ChangeType.REFACTOR)
                .description("方法签名变更：新增参数 validate")
                .build();

        assertEquals(ChangeRequest.ChangeType.REFACTOR, request.getChangeType());
        assertTrue(request.getDescription().contains("签名"));
    }
}