package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExceptionPath 单元测试
 *
 * 测试异常路径分析结果模型的功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("ExceptionPath 单元测试")
class ExceptionPathTest {

    @Test
    @DisplayName("测试构建 ExceptionPath - 基本属性")
    void testBuildBasicExceptionPath() {
        ExceptionPath path = ExceptionPath.builder()
                .exceptionType("NullPointerException")
                .location("UserService.getUser")
                .analyzedAt(System.currentTimeMillis())
                .build();

        assertEquals("NullPointerException", path.getExceptionType());
        assertEquals("UserService.getUser", path.getLocation());
        assertNotNull(path.getAnalyzedAt());
    }

    @Test
    @DisplayName("测试构建 ExceptionPath - 包含传播路径")
    void testBuildWithPropagationPaths() {
        PropagationPath path1 = PropagationPath.builder()
                .sourceMethod("UserService.getUser")
                .probability(0.9)
                .build();

        PropagationPath path2 = PropagationPath.builder()
                .sourceMethod("UserService.validateUser")
                .probability(0.7)
                .build();

        ExceptionPath exceptionPath = ExceptionPath.builder()
                .exceptionType("NullPointerException")
                .propagationPaths(List.of(path1, path2))
                .build();

        assertNotNull(exceptionPath.getPropagationPaths());
        assertEquals(2, exceptionPath.getPropagationPaths().size());
    }

    @Test
    @DisplayName("测试 getTopPaths 方法")
    void testGetTopPaths() {
        PropagationPath path1 = PropagationPath.builder()
                .sourceMethod("method1")
                .probability(0.9)
                .build();

        PropagationPath path2 = PropagationPath.builder()
                .sourceMethod("method2")
                .probability(0.8)
                .build();

        PropagationPath path3 = PropagationPath.builder()
                .sourceMethod("method3")
                .probability(0.7)
                .build();

        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(List.of(path1, path2, path3))
                .build();

        // 获取 Top 2
        List<PropagationPath> top2 = exceptionPath.getTopPaths(2);
        assertEquals(2, top2.size());
        assertEquals("method1", top2.get(0).getSourceMethod());
        assertEquals("method2", top2.get(1).getSourceMethod());
    }

    @Test
    @DisplayName("测试 getTopPaths - 请求数量大于实际数量")
    void testGetTopPathsMoreThanAvailable() {
        PropagationPath path1 = PropagationPath.builder()
                .sourceMethod("method1")
                .build();

        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(List.of(path1))
                .build();

        List<PropagationPath> top5 = exceptionPath.getTopPaths(5);
        assertEquals(1, top5.size());
    }

    @Test
    @DisplayName("测试 getTopPaths - 空路径列表")
    void testGetTopPathsEmpty() {
        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(List.of())
                .build();

        List<PropagationPath> result = exceptionPath.getTopPaths(3);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试 getTopPaths - null 路径列表")
    void testGetTopPathsNull() {
        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(null)
                .build();

        List<PropagationPath> result = exceptionPath.getTopPaths(3);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试 getMostLikelyPath 方法")
    void testGetMostLikelyPath() {
        PropagationPath path1 = PropagationPath.builder()
                .sourceMethod("mostLikelyMethod")
                .probability(0.95)
                .build();

        PropagationPath path2 = PropagationPath.builder()
                .sourceMethod("lessLikelyMethod")
                .probability(0.5)
                .build();

        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(List.of(path1, path2))
                .build();

        PropagationPath mostLikely = exceptionPath.getMostLikelyPath();
        assertNotNull(mostLikely);
        assertEquals("mostLikelyMethod", mostLikely.getSourceMethod());
        assertEquals(0.95, mostLikely.getProbability());
    }

    @Test
    @DisplayName("测试 getMostLikelyPath - 空路径列表")
    void testGetMostLikelyPathEmpty() {
        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(List.of())
                .build();

        PropagationPath result = exceptionPath.getMostLikelyPath();
        assertNull(result);
    }

    @Test
    @DisplayName("测试 getMostLikelyPath - null 路径列表")
    void testGetMostLikelyPathNull() {
        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(null)
                .build();

        PropagationPath result = exceptionPath.getMostLikelyPath();
        assertNull(result);
    }

    @Test
    @DisplayName("测试不同异常类型")
    void testDifferentExceptionTypes() {
        ExceptionPath npe = ExceptionPath.builder()
                .exceptionType("NullPointerException")
                .build();
        assertEquals("NullPointerException", npe.getExceptionType());

        ExceptionPath sql = ExceptionPath.builder()
                .exceptionType("SQLException")
                .build();
        assertEquals("SQLException", sql.getExceptionType());

        ExceptionPath illegal = ExceptionPath.builder()
                .exceptionType("IllegalArgumentException")
                .build();
        assertEquals("IllegalArgumentException", illegal.getExceptionType());
    }

    @Test
    @DisplayName("测试空构造和 setter")
    void testEmptyConstructorAndSetter() {
        ExceptionPath path = new ExceptionPath();
        path.setExceptionType("IOException");
        path.setLocation("FileService.read");
        path.setAnalyzedAt(123456789L);

        assertEquals("IOException", path.getExceptionType());
        assertEquals("FileService.read", path.getLocation());
        assertEquals(123456789L, path.getAnalyzedAt());
    }

    @Test
    @DisplayName("测试全参数构造")
    void testAllArgsConstructor() {
        List<PropagationPath> paths = List.of(
                PropagationPath.builder().sourceMethod("method").build()
        );

        ExceptionPath path = new ExceptionPath(
                "NullPointerException",
                "UserService.getUser",
                paths,
                System.currentTimeMillis()
        );

        assertEquals("NullPointerException", path.getExceptionType());
        assertEquals("UserService.getUser", path.getLocation());
        assertEquals(1, path.getPropagationPaths().size());
    }

    @Test
    @DisplayName("测试获取 Top 0")
    void testGetTopZero() {
        PropagationPath path = PropagationPath.builder()
                .sourceMethod("method")
                .build();

        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(List.of(path))
                .build();

        List<PropagationPath> result = exceptionPath.getTopPaths(0);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试负数索引处理")
    void testNegativeIndex() {
        PropagationPath path = PropagationPath.builder().build();
        ExceptionPath exceptionPath = ExceptionPath.builder()
                .propagationPaths(List.of(path))
                .build();

        // getTopPaths 使用 limit，负数会抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            exceptionPath.getTopPaths(-1);
        });
    }
}