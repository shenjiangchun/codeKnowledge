package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExceptionNode 单元测试
 *
 * 测试异常节点模型的功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("ExceptionNode 单元测试")
class ExceptionNodeTest {

    @Test
    @DisplayName("测试构建 ExceptionNode - 基本属性")
    void testBuildBasicExceptionNode() {
        ExceptionNode node = ExceptionNode.builder()
                .nodeId("npe-001")
                .exceptionType("java.lang.NullPointerException")
                .simpleName("NullPointerException")
                .packageName("java.lang")
                .category(ExceptionNode.ExceptionCategory.RUNTIME)
                .description("空指针异常")
                .build();

        assertEquals("npe-001", node.getNodeId());
        assertEquals("java.lang.NullPointerException", node.getExceptionType());
        assertEquals("NullPointerException", node.getSimpleName());
        assertEquals("java.lang", node.getPackageName());
        assertEquals(ExceptionNode.ExceptionCategory.RUNTIME, node.getCategory());
        assertEquals("空指针异常", node.getDescription());
    }

    @Test
    @DisplayName("测试构建 ExceptionNode - 包含抛出方法列表")
    void testBuildWithThrownByMethods() {
        ExceptionNode node = ExceptionNode.builder()
                .thrownByMethods(List.of("UserService.getUser", "OrderService.createOrder"))
                .build();

        assertNotNull(node.getThrownByMethods());
        assertEquals(2, node.getThrownByMethods().size());
        assertTrue(node.getThrownByMethods().contains("UserService.getUser"));
    }

    @Test
    @DisplayName("测试构建 ExceptionNode - 包含捕获方法列表")
    void testBuildWithCaughtByMethods() {
        ExceptionNode node = ExceptionNode.builder()
                .caughtByMethods(List.of("ExceptionHandler.handle", "GlobalFilter.filter"))
                .build();

        assertNotNull(node.getCaughtByMethods());
        assertEquals(2, node.getCaughtByMethods().size());
    }

    @Test
    @DisplayName("测试构建 ExceptionNode - 包含继承关系")
    void testBuildWithInheritance() {
        ExceptionNode node = ExceptionNode.builder()
                .exceptionType("java.io.FileNotFoundException")
                .parentExceptionType("java.io.IOException")
                .childExceptionTypes(List.of("CustomFileNotFoundException"))
                .build();

        assertEquals("java.io.IOException", node.getParentExceptionType());
        assertEquals(1, node.getChildExceptionTypes().size());
    }

    @Test
    @DisplayName("测试构建 ExceptionNode - 包含扩展属性")
    void testBuildWithProperties() {
        Map<String, Object> properties = Map.of(
                "severity", "HIGH",
                "tags", List.of("common", "runtime"),
                "embedding", new float[]{0.1f, 0.2f, 0.3f}
        );

        ExceptionNode node = ExceptionNode.builder()
                .properties(properties)
                .build();

        assertNotNull(node.getProperties());
        assertEquals("HIGH", node.getProperties().get("severity"));
    }

    @Test
    @DisplayName("测试 isCheckedException - 受检异常")
    void testIsCheckedException() {
        ExceptionNode node = ExceptionNode.builder()
                .category(ExceptionNode.ExceptionCategory.CHECKED)
                .build();

        assertTrue(node.isCheckedException());
        assertFalse(node.isRuntimeException());
        assertFalse(node.isError());
    }

    @Test
    @DisplayName("测试 isRuntimeException - 运行时异常")
    void testIsRuntimeException() {
        ExceptionNode runtimeNode = ExceptionNode.builder()
                .category(ExceptionNode.ExceptionCategory.RUNTIME)
                .build();

        ExceptionNode uncheckedNode = ExceptionNode.builder()
                .category(ExceptionNode.ExceptionCategory.UNCHECKED)
                .build();

        assertTrue(runtimeNode.isRuntimeException());
        assertTrue(uncheckedNode.isRuntimeException());
        assertFalse(runtimeNode.isCheckedException());
        assertFalse(runtimeNode.isError());
    }

    @Test
    @DisplayName("测试 isError - 错误类型")
    void testIsError() {
        ExceptionNode node = ExceptionNode.builder()
                .category(ExceptionNode.ExceptionCategory.ERROR)
                .build();

        assertTrue(node.isError());
        assertFalse(node.isCheckedException());
        assertFalse(node.isRuntimeException());
    }

    @Test
    @DisplayName("测试 getFullName - 有包名")
    void testGetFullNameWithPackage() {
        ExceptionNode node = ExceptionNode.builder()
                .packageName("java.lang")
                .simpleName("NullPointerException")
                .exceptionType("java.lang.NullPointerException")
                .build();

        assertEquals("java.lang.NullPointerException", node.getFullName());
    }

    @Test
    @DisplayName("测试 getFullName - 无包名")
    void testGetFullNameWithoutPackage() {
        ExceptionNode node = ExceptionNode.builder()
                .packageName("")
                .simpleName("CustomException")
                .exceptionType("CustomException")
                .build();

        assertEquals("CustomException", node.getFullName());
    }

    @Test
    @DisplayName("测试 getFullName - null 包名")
    void testGetFullNameNullPackage() {
        ExceptionNode node = ExceptionNode.builder()
                .packageName(null)
                .simpleName("TestException")
                .exceptionType("TestException")
                .build();

        assertEquals("TestException", node.getFullName());
    }

    @Test
    @DisplayName("测试异常分类枚举")
    void testExceptionCategories() {
        assertEquals(5, ExceptionNode.ExceptionCategory.values().length);
        assertEquals(ExceptionNode.ExceptionCategory.CHECKED,
                ExceptionNode.ExceptionCategory.valueOf("CHECKED"));
        assertEquals(ExceptionNode.ExceptionCategory.UNCHECKED,
                ExceptionNode.ExceptionCategory.valueOf("UNCHECKED"));
        assertEquals(ExceptionNode.ExceptionCategory.RUNTIME,
                ExceptionNode.ExceptionCategory.valueOf("RUNTIME"));
        assertEquals(ExceptionNode.ExceptionCategory.ERROR,
                ExceptionNode.ExceptionCategory.valueOf("ERROR"));
        assertEquals(ExceptionNode.ExceptionCategory.CUSTOM,
                ExceptionNode.ExceptionCategory.valueOf("CUSTOM"));
    }

    @Test
    @DisplayName("测试自定义异常分类")
    void testCustomExceptionCategory() {
        ExceptionNode node = ExceptionNode.builder()
                .category(ExceptionNode.ExceptionCategory.CUSTOM)
                .build();

        assertFalse(node.isCheckedException());
        assertFalse(node.isRuntimeException());
        assertFalse(node.isError());
    }

    @Test
    @DisplayName("测试空构造和 setter")
    void testEmptyConstructorAndSetter() {
        ExceptionNode node = new ExceptionNode();
        node.setNodeId("test-001");
        node.setExceptionType("TestException");
        node.setSimpleName("TestException");
        node.setCategory(ExceptionNode.ExceptionCategory.RUNTIME);

        assertEquals("test-001", node.getNodeId());
        assertEquals("TestException", node.getExceptionType());
        assertEquals(ExceptionNode.ExceptionCategory.RUNTIME, node.getCategory());
    }

    @Test
    @DisplayName("测试全参数构造")
    void testAllArgsConstructor() {
        ExceptionNode node = new ExceptionNode(
                "node-001",
                "java.lang.IllegalArgumentException",
                "IllegalArgumentException",
                "java.lang",
                ExceptionNode.ExceptionCategory.RUNTIME,
                "非法参数异常",
                List.of("Validator.validate"),
                List.of("ExceptionHandler.handle"),
                "java.lang.RuntimeException",
                List.of(),
                Map.of()
        );

        assertEquals("node-001", node.getNodeId());
        assertEquals("java.lang.IllegalArgumentException", node.getExceptionType());
        assertEquals(1, node.getThrownByMethods().size());
    }

    @Test
    @DisplayName("测试常见 Java 异常节点")
    void testCommonJavaExceptions() {
        // NullPointerException
        ExceptionNode npe = ExceptionNode.builder()
                .exceptionType("java.lang.NullPointerException")
                .simpleName("NullPointerException")
                .packageName("java.lang")
                .category(ExceptionNode.ExceptionCategory.RUNTIME)
                .parentExceptionType("java.lang.RuntimeException")
                .build();

        assertTrue(npe.isRuntimeException());
        assertEquals("java.lang.RuntimeException", npe.getParentExceptionType());

        // IOException
        ExceptionNode ioe = ExceptionNode.builder()
                .exceptionType("java.io.IOException")
                .simpleName("IOException")
                .packageName("java.io")
                .category(ExceptionNode.ExceptionCategory.CHECKED)
                .build();

        assertTrue(ioe.isCheckedException());

        // OutOfMemoryError
        ExceptionNode oom = ExceptionNode.builder()
                .exceptionType("java.lang.OutOfMemoryError")
                .simpleName("OutOfMemoryError")
                .packageName("java.lang")
                .category(ExceptionNode.ExceptionCategory.ERROR)
                .build();

        assertTrue(oom.isError());
    }

    @Test
    @DisplayName("测试多个子类异常")
    void testMultipleChildExceptions() {
        ExceptionNode node = ExceptionNode.builder()
                .exceptionType("java.lang.Exception")
                .childExceptionTypes(List.of(
                        "BusinessException",
                        "TechnicalException",
                        "ValidationException"
                ))
                .build();

        assertEquals(3, node.getChildExceptionTypes().size());
    }

    @Test
    @DisplayName("测试同时被多个方法抛出")
    void testMultipleThrownByMethods() {
        ExceptionNode node = ExceptionNode.builder()
                .thrownByMethods(List.of(
                        "ServiceA.method1",
                        "ServiceB.method2",
                        "ServiceC.method3",
                        "Util.helper"
                ))
                .build();

        assertEquals(4, node.getThrownByMethods().size());
    }

    @Test
    @DisplayName("测试空列表属性")
    void testEmptyListProperties() {
        ExceptionNode node = ExceptionNode.builder()
                .thrownByMethods(List.of())
                .caughtByMethods(List.of())
                .childExceptionTypes(List.of())
                .build();

        assertTrue(node.getThrownByMethods().isEmpty());
        assertTrue(node.getCaughtByMethods().isEmpty());
        assertTrue(node.getChildExceptionTypes().isEmpty());
    }
}