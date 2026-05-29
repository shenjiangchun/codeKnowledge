package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TD-005: try-catch检测单元测试
 *
 * 测试异常捕获检测和嵌套try-catch检测
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("TD-005: try-catch检测测试")
class TryCatchDetectionTest {

    private TryCatchBlockInfo tryCatchBlock;

    @BeforeEach
    void setUp() {
        tryCatchBlock = TryCatchBlockInfo.builder()
                .methodNodeId("method-123")
                .tryStartLine(10)
                .tryEndLine(20)
                .hasFinally(false)
                .catchClauses(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("测试基本try-catch块构建")
    void testBasicTryCatchBlockBuild() {
        assertNotNull(tryCatchBlock, "try-catch块应成功构建");
        assertEquals("method-123", tryCatchBlock.getMethodNodeId(), "方法节点ID应匹配");
        assertEquals(10, tryCatchBlock.getTryStartLine(), "try块起始行应匹配");
        assertEquals(20, tryCatchBlock.getTryEndLine(), "try块结束行应匹配");
        assertFalse(tryCatchBlock.getHasFinally(), "不应有finally块");
    }

    @Test
    @DisplayName("测试catch子句添加")
    void testAddCatchClause() {
        TryCatchBlockInfo.CatchClauseInfo catchClause = TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.NullPointerException")
                .exceptionVariable("e")
                .catchStartLine(21)
                .catchEndLine(25)
                .behavior(TryCatchBlockInfo.CatchBehavior.LOG)
                .build();

        tryCatchBlock.getCatchClauses().add(catchClause);

        assertEquals(1, tryCatchBlock.getCatchClauses().size(), "应有1个catch子句");
        assertEquals("java.lang.NullPointerException",
            tryCatchBlock.getCatchClauses().get(0).getExceptionType(), "异常类型应匹配");
    }

    @Test
    @DisplayName("测试catchesException方法 - 精确匹配")
    void testCatchesExceptionExactMatch() {
        TryCatchBlockInfo.CatchClauseInfo catchClause = TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.NullPointerException")
                .behavior(TryCatchBlockInfo.CatchBehavior.LOG)
                .build();

        tryCatchBlock.getCatchClauses().add(catchClause);

        assertTrue(tryCatchBlock.catchesException("java.lang.NullPointerException"),
            "应能捕获NullPointerException");
    }

    @Test
    @DisplayName("测试catchesException方法 - 未匹配")
    void testCatchesExceptionNoMatch() {
        TryCatchBlockInfo.CatchClauseInfo catchClause = TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.NullPointerException")
                .behavior(TryCatchBlockInfo.CatchBehavior.LOG)
                .build();

        tryCatchBlock.getCatchClauses().add(catchClause);

        assertFalse(tryCatchBlock.catchesException("java.lang.IOException"),
            "不应捕获IOException");
    }

    @Test
    @DisplayName("测试catchesException方法 - 无catch子句")
    void testCatchesExceptionNoClauses() {
        TryCatchBlockInfo emptyBlock = TryCatchBlockInfo.builder()
                .catchClauses(null)
                .build();

        assertFalse(emptyBlock.catchesException("java.lang.Exception"),
            "无catch子句时不应捕获任何异常");
    }

    @Test
    @DisplayName("测试getCatchBehavior方法")
    void testGetCatchBehavior() {
        TryCatchBlockInfo.CatchClauseInfo catchClause = TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.IOException")
                .behavior(TryCatchBlockInfo.CatchBehavior.RETHROW)
                .build();

        tryCatchBlock.getCatchClauses().add(catchClause);

        assertEquals(TryCatchBlockInfo.CatchBehavior.RETHROW,
            tryCatchBlock.getCatchBehavior("java.lang.IOException"),
            "应返回RETHROW行为");
    }

    @Test
    @DisplayName("测试getCatchBehavior方法 - 未找到")
    void testGetCatchBehaviorNotFound() {
        TryCatchBlockInfo.CatchClauseInfo catchClause = TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.IOException")
                .behavior(TryCatchBlockInfo.CatchBehavior.RETHROW)
                .build();

        tryCatchBlock.getCatchClauses().add(catchClause);

        assertNull(tryCatchBlock.getCatchBehavior("java.lang.NullPointerException"),
            "未找到匹配的catch行为应返回null");
    }

    @Test
    @DisplayName("测试多个catch子句")
    void testMultipleCatchClauses() {
        List<TryCatchBlockInfo.CatchClauseInfo> clauses = List.of(
            TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.NullPointerException")
                .behavior(TryCatchBlockInfo.CatchBehavior.LOG)
                .build(),
            TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.IOException")
                .behavior(TryCatchBlockInfo.CatchBehavior.RETHROW)
                .build(),
            TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.SQLException")
                .behavior(TryCatchBlockInfo.CatchBehavior.HANDLE)
                .build()
        );

        TryCatchBlockInfo multiCatchBlock = TryCatchBlockInfo.builder()
                .catchClauses(new ArrayList<>(clauses))
                .build();

        assertEquals(3, multiCatchBlock.getCatchClauses().size(), "应有3个catch子句");
        assertTrue(multiCatchBlock.catchesException("java.lang.NullPointerException"), "应捕获NPE");
        assertTrue(multiCatchBlock.catchesException("java.lang.IOException"), "应捕获IOException");
        assertTrue(multiCatchBlock.catchesException("java.lang.SQLException"), "应捕获SQLException");
    }

    @Test
    @DisplayName("测试CatchBehavior枚举值")
    void testCatchBehaviorEnum() {
        assertEquals(6, TryCatchBlockInfo.CatchBehavior.values().length, "应有6种catch行为");
        assertEquals(TryCatchBlockInfo.CatchBehavior.RETHROW, TryCatchBlockInfo.CatchBehavior.valueOf("RETHROW"));
        assertEquals(TryCatchBlockInfo.CatchBehavior.LOG, TryCatchBlockInfo.CatchBehavior.valueOf("LOG"));
        assertEquals(TryCatchBlockInfo.CatchBehavior.IGNORE, TryCatchBlockInfo.CatchBehavior.valueOf("IGNORE"));
        assertEquals(TryCatchBlockInfo.CatchBehavior.WRAP, TryCatchBlockInfo.CatchBehavior.valueOf("WRAP"));
        assertEquals(TryCatchBlockInfo.CatchBehavior.HANDLE, TryCatchBlockInfo.CatchBehavior.valueOf("HANDLE"));
        assertEquals(TryCatchBlockInfo.CatchBehavior.UNKNOWN, TryCatchBlockInfo.CatchBehavior.valueOf("UNKNOWN"));
    }

    @Test
    @DisplayName("测试finally块")
    void testFinallyBlock() {
        TryCatchBlockInfo blockWithFinally = TryCatchBlockInfo.builder()
                .methodNodeId("method-456")
                .tryStartLine(10)
                .tryEndLine(20)
                .hasFinally(true)
                .finallyStartLine(30)
                .finallyEndLine(35)
                .build();

        assertTrue(blockWithFinally.getHasFinally(), "应有finally块");
        assertEquals(30, blockWithFinally.getFinallyStartLine(), "finally块起始行应正确");
        assertEquals(35, blockWithFinally.getFinallyEndLine(), "finally块结束行应正确");
    }

    @Test
    @DisplayName("测试嵌套try-catch检测")
    void testNestedTryCatchDetection() {
        // 外层try-catch
        TryCatchBlockInfo outerBlock = TryCatchBlockInfo.builder()
                .methodNodeId("method-outer")
                .tryStartLine(10)
                .tryEndLine(50)
                .catchClauses(List.of(
                    TryCatchBlockInfo.CatchClauseInfo.builder()
                        .exceptionType("java.lang.Exception")
                        .behavior(TryCatchBlockInfo.CatchBehavior.LOG)
                        .build()
                ))
                .build();

        // 内层try-catch（模拟嵌套）
        TryCatchBlockInfo innerBlock = TryCatchBlockInfo.builder()
                .methodNodeId("method-inner")
                .tryStartLine(20)
                .tryEndLine(35)
                .catchClauses(List.of(
                    TryCatchBlockInfo.CatchClauseInfo.builder()
                        .exceptionType("java.lang.NullPointerException")
                        .behavior(TryCatchBlockInfo.CatchBehavior.HANDLE)
                        .build()
                ))
                .build();

        // 验证外层能捕获Exception
        assertTrue(outerBlock.catchesException("java.lang.Exception"), "外层应捕获Exception");

        // 验证内层能捕获NullPointerException
        assertTrue(innerBlock.catchesException("java.lang.NullPointerException"), "内层应捕获NPE");

        // 验证内层不能捕获非声明的异常
        assertFalse(innerBlock.catchesException("java.lang.IOException"), "内层不应捕获IOException");
    }

    @Test
    @DisplayName("测试try块内方法调用")
    void testTryBlockMethodCalls() {
        List<String> methodCalls = List.of(
            "com.example.Service.doSomething",
            "com.example.Repository.getData",
            "com.example.Utils.process"
        );

        TryCatchBlockInfo blockWithCalls = TryCatchBlockInfo.builder()
                .tryBlockMethodCalls(methodCalls)
                .build();

        assertEquals(3, blockWithCalls.getTryBlockMethodCalls().size(), "应有3个方法调用");
        assertTrue(blockWithCalls.getTryBlockMethodCalls().contains("com.example.Service.doSomething"),
            "应包含Service.doSomething方法调用");
    }

    @Test
    @DisplayName("测试WRAP行为 - 包装异常类型")
    void testWrapBehaviorWithWrappedType() {
        TryCatchBlockInfo.CatchClauseInfo wrapClause = TryCatchBlockInfo.CatchClauseInfo.builder()
                .exceptionType("java.lang.IOException")
                .behavior(TryCatchBlockInfo.CatchBehavior.WRAP)
                .wrappedExceptionType("com.example.BusinessException")
                .build();

        tryCatchBlock.getCatchClauses().add(wrapClause);

        assertEquals(TryCatchBlockInfo.CatchBehavior.WRAP,
            tryCatchBlock.getCatchClauses().get(0).getBehavior(), "行为应为WRAP");
        assertEquals("com.example.BusinessException",
            tryCatchBlock.getCatchClauses().get(0).getWrappedExceptionType(), "包装后的异常类型应正确");
    }

    @Test
    @DisplayName("测试扩展属性")
    void testProperties() {
        java.util.Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("isCritical", true);
        properties.put("maxRetries", 3);

        TryCatchBlockInfo blockWithProps = TryCatchBlockInfo.builder()
                .properties(properties)
                .build();

        assertTrue((Boolean) blockWithProps.getProperties().get("isCritical"), "扩展属性isCritical应为true");
        assertEquals(3, blockWithProps.getProperties().get("maxRetries"), "扩展属性maxRetries应为3");
    }
}