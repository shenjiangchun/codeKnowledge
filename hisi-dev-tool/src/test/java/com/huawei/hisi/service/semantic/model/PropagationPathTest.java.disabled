package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PropagationPath 单元测试
 *
 * 测试异常传播路径模型的功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("PropagationPath 单元测试")
class PropagationPathTest {

    @Test
    @DisplayName("测试构建 PropagationPath - 基本属性")
    void testBuildBasicPropagationPath() {
        PropagationPath path = PropagationPath.builder()
                .sourceMethod("com.example.UserService.getUser")
                .probability(0.85)
                .lineNumber(42)
                .sourceClassName("UserService")
                .sourceFileName("UserService.java")
                .hasTryCatch(true)
                .catchBehavior("log")
                .build();

        assertEquals("com.example.UserService.getUser", path.getSourceMethod());
        assertEquals(0.85, path.getProbability());
        assertEquals(42, path.getLineNumber());
        assertEquals("UserService", path.getSourceClassName());
        assertEquals("UserService.java", path.getSourceFileName());
        assertTrue(path.getHasTryCatch());
        assertEquals("log", path.getCatchBehavior());
    }

    @Test
    @DisplayName("测试构建 PropagationPath - 包含调用链")
    void testBuildWithCallChain() {
        List<String> callChain = List.of(
                "Controller.handleRequest",
                "Service.process",
                "Repository.query"
        );

        PropagationPath path = PropagationPath.builder()
                .callChain(callChain)
                .build();

        assertNotNull(path.getCallChain());
        assertEquals(3, path.getCallChain().size());
        assertEquals("Controller.handleRequest", path.getCallChain().get(0));
        assertEquals("Repository.query", path.getCallChain().get(2));
    }

    @Test
    @DisplayName("测试 getChainDepth 方法")
    void testGetChainDepth() {
        PropagationPath path = PropagationPath.builder()
                .callChain(List.of("A", "B", "C", "D"))
                .build();

        assertEquals(4, path.getChainDepth());
    }

    @Test
    @DisplayName("测试 getChainDepth - 空调用链")
    void testGetChainDepthEmpty() {
        PropagationPath path = PropagationPath.builder()
                .callChain(List.of())
                .build();

        assertEquals(0, path.getChainDepth());
    }

    @Test
    @DisplayName("测试 getChainDepth - null 调用链")
    void testGetChainDepthNull() {
        PropagationPath path = PropagationPath.builder()
                .callChain(null)
                .build();

        assertEquals(0, path.getChainDepth());
    }

    @Test
    @DisplayName("测试 isDirectThrow - 直接抛出")
    void testIsDirectThrowTrue() {
        PropagationPath path = PropagationPath.builder()
                .callChain(List.of("Service.method"))
                .build();

        assertTrue(path.isDirectThrow());
    }

    @Test
    @DisplayName("测试 isDirectThrow - 非直接抛出")
    void testIsDirectThrowFalse() {
        PropagationPath path = PropagationPath.builder()
                .callChain(List.of("A.method", "B.method", "C.method"))
                .build();

        assertFalse(path.isDirectThrow());
    }

    @Test
    @DisplayName("测试 formatCallChain 方法")
    void testFormatCallChain() {
        PropagationPath path = PropagationPath.builder()
                .callChain(List.of("A.method", "B.method", "C.method"))
                .build();

        String formatted = path.formatCallChain();
        assertEquals("A.method -> B.method -> C.method", formatted);
    }

    @Test
    @DisplayName("测试 formatCallChain - 空调用链")
    void testFormatCallChainEmpty() {
        PropagationPath path = PropagationPath.builder()
                .callChain(List.of())
                .build();

        String formatted = path.formatCallChain();
        assertEquals("", formatted);
    }

    @Test
    @DisplayName("测试 formatCallChain - null 调用链")
    void testFormatCallChainNull() {
        PropagationPath path = PropagationPath.builder()
                .callChain(null)
                .build();

        String formatted = path.formatCallChain();
        assertEquals("", formatted);
    }

    @Test
    @DisplayName("测试不同的概率值")
    void testDifferentProbabilities() {
        PropagationPath high = PropagationPath.builder().probability(1.0).build();
        assertEquals(1.0, high.getProbability());

        PropagationPath medium = PropagationPath.builder().probability(0.5).build();
        assertEquals(0.5, medium.getProbability());

        PropagationPath low = PropagationPath.builder().probability(0.1).build();
        assertEquals(0.1, low.getProbability());
    }

    @Test
    @DisplayName("测试不同的 catch 行为")
    void testDifferentCatchBehaviors() {
        PropagationPath rethrow = PropagationPath.builder()
                .catchBehavior("rethrow")
                .build();
        assertEquals("rethrow", rethrow.getCatchBehavior());

        PropagationPath log = PropagationPath.builder()
                .catchBehavior("log")
                .build();
        assertEquals("log", log.getCatchBehavior());

        PropagationPath ignore = PropagationPath.builder()
                .catchBehavior("ignore")
                .build();
        assertEquals("ignore", ignore.getCatchBehavior());

        PropagationPath wrap = PropagationPath.builder()
                .catchBehavior("wrap")
                .build();
        assertEquals("wrap", wrap.getCatchBehavior());
    }

    @Test
    @DisplayName("测试有 try-catch 的路径")
    void testWithTryCatch() {
        PropagationPath path = PropagationPath.builder()
                .hasTryCatch(true)
                .catchBehavior("log")
                .build();

        assertTrue(path.getHasTryCatch());
        assertNotNull(path.getCatchBehavior());
    }

    @Test
    @DisplayName("测试无 try-catch 的路径")
    void testWithoutTryCatch() {
        PropagationPath path = PropagationPath.builder()
                .hasTryCatch(false)
                .build();

        assertFalse(path.getHasTryCatch());
    }

    @Test
    @DisplayName("测试空构造和 setter")
    void testEmptyConstructorAndSetter() {
        PropagationPath path = new PropagationPath();
        path.setSourceMethod("testMethod");
        path.setProbability(0.75);
        path.setLineNumber(10);
        path.setSourceClassName("TestClass");
        path.setHasTryCatch(false);

        assertEquals("testMethod", path.getSourceMethod());
        assertEquals(0.75, path.getProbability());
        assertEquals(10, path.getLineNumber());
        assertEquals("TestClass", path.getSourceClassName());
        assertFalse(path.getHasTryCatch());
    }

    @Test
    @DisplayName("测试全参数构造")
    void testAllArgsConstructor() {
        List<String> callChain = List.of("A", "B");

        PropagationPath path = new PropagationPath(
                "source.method",
                callChain,
                0.9,
                100,
                "SourceClass",
                "SourceFile.java",
                true,
                "rethrow"
        );

        assertEquals("source.method", path.getSourceMethod());
        assertEquals(2, path.getCallChain().size());
        assertEquals(0.9, path.getProbability());
        assertEquals(100, path.getLineNumber());
        assertEquals("SourceClass", path.getSourceClassName());
        assertEquals("SourceFile.java", path.getSourceFileName());
        assertTrue(path.getHasTryCatch());
        assertEquals("rethrow", path.getCatchBehavior());
    }

    @Test
    @DisplayName("测试长调用链")
    void testLongCallChain() {
        List<String> longChain = List.of(
                "Controller.method1",
                "Service.method2",
                "Service.method3",
                "Repository.method4",
                "DAO.method5",
                "Util.method6",
                "Helper.method7"
        );

        PropagationPath path = PropagationPath.builder()
                .callChain(longChain)
                .build();

        assertEquals(7, path.getChainDepth());
        assertFalse(path.isDirectThrow());
    }

    @Test
    @DisplayName("测试边界行号")
    void testBoundaryLineNumbers() {
        // 最小行号
        PropagationPath minPath = PropagationPath.builder()
                .lineNumber(1)
                .build();
        assertEquals(1, minPath.getLineNumber());

        // 大行号
        PropagationPath maxPath = PropagationPath.builder()
                .lineNumber(10000)
                .build();
        assertEquals(10000, maxPath.getLineNumber());

        // null 行号
        PropagationPath nullPath = PropagationPath.builder()
                .lineNumber(null)
                .build();
        assertNull(nullPath.getLineNumber());
    }

    @Test
    @DisplayName("测试单元素调用链")
    void testSingleElementCallChain() {
        PropagationPath path = PropagationPath.builder()
                .callChain(List.of("Single.method"))
                .build();

        assertEquals(1, path.getChainDepth());
        assertTrue(path.isDirectThrow());
        assertEquals("Single.method", path.formatCallChain());
    }
}