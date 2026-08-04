package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MethodSemantic 单元测试
 *
 * 测试方法语义模型的构建和方法
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("MethodSemantic 单元测试")
class MethodSemanticTest {

    @Test
    @DisplayName("测试构建 MethodSemantic - 基本属性")
    void testBuildBasicMethodSemantic() {
        MethodSemantic semantic = MethodSemantic.builder()
                .className("com.example.UserService")
                .methodName("login")
                .signature("login(String, String)")
                .returnType("boolean")
                .intent("用户登录验证")
                .complexity(3)
                .category(MethodCategory.BUSINESS_LOGIC)
                .build();

        assertEquals("com.example.UserService", semantic.getClassName());
        assertEquals("login", semantic.getMethodName());
        assertEquals("login(String, String)", semantic.getSignature());
        assertEquals("boolean", semantic.getReturnType());
        assertEquals("用户登录验证", semantic.getIntent());
        assertEquals(3, semantic.getComplexity());
        assertEquals(MethodCategory.BUSINESS_LOGIC, semantic.getCategory());
    }

    @Test
    @DisplayName("测试构建 MethodSemantic - 包含参数信息")
    void testBuildWithParameters() {
        MethodSemantic.ParameterInfo param1 = MethodSemantic.ParameterInfo.builder()
                .name("username")
                .type("String")
                .isGeneric(false)
                .build();

        MethodSemantic.ParameterInfo param2 = MethodSemantic.ParameterInfo.builder()
                .name("user")
                .type("T")
                .isGeneric(true)
                .build();

        MethodSemantic semantic = MethodSemantic.builder()
                .className("UserService")
                .methodName("process")
                .parameters(List.of(param1, param2))
                .build();

        assertNotNull(semantic.getParameters());
        assertEquals(2, semantic.getParameters().size());
        assertEquals("username", semantic.getParameters().get(0).getName());
        assertEquals("String", semantic.getParameters().get(0).getType());
        assertFalse(semantic.getParameters().get(0).isGeneric());
        assertTrue(semantic.getParameters().get(1).isGeneric());
    }

    @Test
    @DisplayName("测试构建 MethodSemantic - 包含异常列表")
    void testBuildWithExceptions() {
        MethodSemantic semantic = MethodSemantic.builder()
                .className("UserService")
                .methodName("getUser")
                .exceptions(List.of("NullPointerException", "SQLException"))
                .build();

        assertNotNull(semantic.getExceptions());
        assertEquals(2, semantic.getExceptions().size());
        assertTrue(semantic.getExceptions().contains("NullPointerException"));
        assertTrue(semantic.getExceptions().contains("SQLException"));
    }

    @Test
    @DisplayName("测试构建 MethodSemantic - 包含关键词列表")
    void testBuildWithKeywords() {
        MethodSemantic semantic = MethodSemantic.builder()
                .className("UserService")
                .methodName("save")
                .keywords(List.of("数据库", "持久化", "保存"))
                .build();

        assertNotNull(semantic.getKeywords());
        assertEquals(3, semantic.getKeywords().size());
        assertTrue(semantic.getKeywords().contains("数据库"));
    }

    @Test
    @DisplayName("测试构建 MethodSemantic - 包含调用方法列表")
    void testBuildWithCalledMethods() {
        MethodSemantic semantic = MethodSemantic.builder()
                .className("OrderService")
                .methodName("createOrder")
                .calledMethods(List.of("UserService.getUser", "PaymentService.pay"))
                .callingMethods(List.of("OrderController.create"))
                .build();

        assertNotNull(semantic.getCalledMethods());
        assertEquals(2, semantic.getCalledMethods().size());
        assertNotNull(semantic.getCallingMethods());
        assertEquals(1, semantic.getCallingMethods().size());
    }

    @Test
    @DisplayName("测试 getUniqueId 方法")
    void testGetUniqueId() {
        MethodSemantic semantic = MethodSemantic.builder()
                .className("com.example.UserService")
                .methodName("login")
                .build();

        String uniqueId = semantic.getUniqueId();
        assertEquals("com.example.UserService#login", uniqueId);
    }

    @Test
    @DisplayName("测试 getFullSignature 方法")
    void testGetFullSignature() {
        MethodSemantic semantic = MethodSemantic.builder()
                .className("com.example.UserService")
                .methodName("login")
                .signature("login(String username, String password)")
                .build();

        String fullSignature = semantic.getFullSignature();
        assertEquals("com.example.UserService.login(String username, String password)", fullSignature);
    }

    @Test
    @DisplayName("测试空参数构造")
    void testEmptyConstructor() {
        MethodSemantic semantic = new MethodSemantic();
        assertNull(semantic.getClassName());
        assertNull(semantic.getMethodName());
    }

    @Test
    @DisplayName("测试所有属性设置")
    void testSetAllProperties() {
        MethodSemantic semantic = new MethodSemantic();
        semantic.setClassName("TestClass");
        semantic.setMethodName("testMethod");
        semantic.setSignature("testMethod()");
        semantic.setReturnType("void");
        semantic.setIntent("测试方法");
        semantic.setComplexity(1);
        semantic.setCategory(MethodCategory.TEST_METHOD);
        semantic.setSourceCode("public void testMethod() {}");
        semantic.setKeywords(List.of("test"));
        semantic.setExceptions(List.of());
        semantic.setCalledMethods(List.of());
        semantic.setCallingMethods(List.of());

        assertEquals("TestClass", semantic.getClassName());
        assertEquals("testMethod", semantic.getMethodName());
        assertEquals("void", semantic.getReturnType());
        assertEquals(MethodCategory.TEST_METHOD, semantic.getCategory());
    }

    @Test
    @DisplayName("测试 ParameterInfo 构建")
    void testParameterInfoBuild() {
        MethodSemantic.ParameterInfo param = MethodSemantic.ParameterInfo.builder()
                .name("input")
                .type("String")
                .isGeneric(false)
                .build();

        assertEquals("input", param.getName());
        assertEquals("String", param.getType());
        assertFalse(param.isGeneric());
    }

    @Test
    @DisplayName("测试高复杂度方法")
    void testHighComplexityMethod() {
        MethodSemantic semantic = MethodSemantic.builder()
                .className("ComplexService")
                .methodName("complexLogic")
                .complexity(15) // 高复杂度
                .build();

        assertEquals(15, semantic.getComplexity());
    }

    @Test
    @DisplayName("测试不同分类的方法")
    void testDifferentCategories() {
        // 业务逻辑方法
        MethodSemantic business = MethodSemantic.builder()
                .className("Service")
                .methodName("process")
                .category(MethodCategory.BUSINESS_LOGIC)
                .build();
        assertEquals(MethodCategory.BUSINESS_LOGIC, business.getCategory());

        // 数据访问方法
        MethodSemantic dataAccess = MethodSemantic.builder()
                .className("Repository")
                .methodName("findById")
                .category(MethodCategory.DATA_ACCESS)
                .build();
        assertEquals(MethodCategory.DATA_ACCESS, dataAccess.getCategory());

        // API接口方法
        MethodSemantic api = MethodSemantic.builder()
                .className("Controller")
                .methodName("handleRequest")
                .category(MethodCategory.API_ENDPOINT)
                .build();
        assertEquals(MethodCategory.API_ENDPOINT, api.getCategory());
    }

    @Test
    @DisplayName("测试源代码设置")
    void testSourceCodeSetting() {
        String sourceCode = """
            public boolean login(String username, String password) {
                User user = userDao.findByUsername(username);
                if (user == null) {
                    return false;
                }
                return user.getPassword().equals(password);
            }
            """;

        MethodSemantic semantic = MethodSemantic.builder()
                .className("UserService")
                .methodName("login")
                .sourceCode(sourceCode)
                .build();

        assertNotNull(semantic.getSourceCode());
        assertTrue(semantic.getSourceCode().contains("userDao"));
    }
}