package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MethodStructure 单元测试
 *
 * 测试方法结构模型的功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("MethodStructure 单元测试")
class MethodStructureTest {

    @Test
    @DisplayName("测试构建 MethodStructure - 基本属性")
    void testBuildBasicMethodStructure() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("process")
                .signature("process(String input)")
                .returnType("String")
                .cyclomaticComplexity(3)
                .bodyLineCount(15)
                .isStatic(false)
                .isPublic(true)
                .isConstructor(false)
                .build();

        assertEquals("process", structure.getMethodName());
        assertEquals("process(String input)", structure.getSignature());
        assertEquals("String", structure.getReturnType());
        assertEquals(3, structure.getCyclomaticComplexity());
        assertEquals(15, structure.getBodyLineCount());
        assertFalse(structure.isStatic());
        assertTrue(structure.isPublic());
        assertFalse(structure.isConstructor());
    }

    @Test
    @DisplayName("测试构建 MethodStructure - 包含参数")
    void testBuildWithParameters() {
        MethodSemantic.ParameterInfo param = MethodSemantic.ParameterInfo.builder()
                .name("input")
                .type("String")
                .isGeneric(false)
                .build();

        MethodStructure structure = MethodStructure.builder()
                .methodName("test")
                .parameters(List.of(param))
                .build();

        assertNotNull(structure.getParameters());
        assertEquals(1, structure.getParameters().size());
        assertEquals("input", structure.getParameters().get(0).getName());
    }

    @Test
    @DisplayName("测试构建 MethodStructure - 包含异常列表")
    void testBuildWithThrownExceptions() {
        MethodStructure structure = MethodStructure.builder()
                .methodName(" riskyOperation")
                .thrownExceptions(List.of("IOException", "SQLException"))
                .build();

        assertNotNull(structure.getThrownExceptions());
        assertEquals(2, structure.getThrownExceptions().size());
        assertTrue(structure.getThrownExceptions().contains("IOException"));
    }

    @Test
    @DisplayName("测试构建 MethodStructure - 包含方法调用")
    void testBuildWithMethodCalls() {
        MethodStructure.MethodCallInfo call1 = MethodStructure.MethodCallInfo.builder()
                .targetClassName("UserService")
                .targetMethodName("getUser")
                .lineNumber(10)
                .build();

        MethodStructure.MethodCallInfo call2 = MethodStructure.MethodCallInfo.builder()
                .targetClassName("LogService")
                .targetMethodName("log")
                .lineNumber(12)
                .build();

        MethodStructure structure = MethodStructure.builder()
                .methodName("processOrder")
                .methodCalls(List.of(call1, call2))
                .build();

        assertNotNull(structure.getMethodCalls());
        assertEquals(2, structure.getMethodCalls().size());
        assertEquals("UserService", structure.getMethodCalls().get(0).getTargetClassName());
        assertEquals(10, structure.getMethodCalls().get(0).getLineNumber());
    }

    @Test
    @DisplayName("测试构建 MethodStructure - 包含注解")
    void testBuildWithAnnotations() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("apiMethod")
                .annotations(List.of("@GetMapping", "@ResponseBody", "@Transactional"))
                .build();

        assertNotNull(structure.getAnnotations());
        assertEquals(3, structure.getAnnotations().size());
        assertTrue(structure.getAnnotations().contains("@GetMapping"));
    }

    @Test
    @DisplayName("测试静态方法")
    void testStaticMethod() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("staticHelper")
                .isStatic(true)
                .isPublic(true)
                .build();

        assertTrue(structure.isStatic());
        assertTrue(structure.isPublic());
    }

    @Test
    @DisplayName("测试构造方法")
    void testConstructorMethod() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("UserService")
                .isConstructor(true)
                .isPublic(true)
                .build();

        assertTrue(structure.isConstructor());
        assertEquals("UserService", structure.getMethodName());
    }

    @Test
    @DisplayName("测试私有方法")
    void testPrivateMethod() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("privateHelper")
                .isPublic(false)
                .build();

        assertFalse(structure.isPublic());
    }

    @Test
    @DisplayName("测试高复杂度方法")
    void testHighComplexityMethod() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("complexLogic")
                .cyclomaticComplexity(20)
                .bodyLineCount(100)
                .build();

        assertEquals(20, structure.getCyclomaticComplexity());
        assertEquals(100, structure.getBodyLineCount());
    }

    @Test
    @DisplayName("测试 MethodCallInfo 构建")
    void testMethodCallInfoBuild() {
        MethodStructure.MethodCallInfo call = MethodStructure.MethodCallInfo.builder()
                .targetClassName("DatabaseService")
                .targetMethodName("query")
                .lineNumber(25)
                .build();

        assertEquals("DatabaseService", call.getTargetClassName());
        assertEquals("query", call.getTargetMethodName());
        assertEquals(25, call.getLineNumber());
    }

    @Test
    @DisplayName("测试空参数构造")
    void testEmptyConstructor() {
        MethodStructure structure = new MethodStructure();
        assertNull(structure.getMethodName());
        assertNull(structure.getSignature());
        assertNull(structure.getReturnType());
    }

    @Test
    @DisplayName("测试 setter 和 getter")
    void testSetterGetter() {
        MethodStructure structure = new MethodStructure();
        structure.setMethodName("test");
        structure.setSignature("test()");
        structure.setReturnType("void");
        structure.setCyclomaticComplexity(1);
        structure.setBodyLineCount(5);
        structure.setStatic(true);
        structure.setPublic(false);
        structure.setConstructor(false);

        assertEquals("test", structure.getMethodName());
        assertEquals("test()", structure.getSignature());
        assertEquals("void", structure.getReturnType());
        assertEquals(1, structure.getCyclomaticComplexity());
        assertEquals(5, structure.getBodyLineCount());
        assertTrue(structure.isStatic());
        assertFalse(structure.isPublic());
    }

    @Test
    @DisplayName("测试无返回类型方法（void）")
    void testVoidReturnType() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("doSomething")
                .returnType("void")
                .build();

        assertEquals("void", structure.getReturnType());
    }

    @Test
    @DisplayName("测试复杂返回类型")
    void testComplexReturnType() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("getUsers")
                .returnType("List<User>")
                .build();

        assertEquals("List<User>", structure.getReturnType());
    }

    @Test
    @DisplayName("测试零复杂度方法")
    void testZeroComplexity() {
        MethodStructure structure = MethodStructure.builder()
                .methodName("simpleGetter")
                .cyclomaticComplexity(0)
                .bodyLineCount(1)
                .build();

        assertEquals(0, structure.getCyclomaticComplexity());
    }

    @Test
    @DisplayName("测试多个注解顺序")
    void testAnnotationOrder() {
        List<String> annotations = List.of("@Override", "@Transactional", "@Cacheable");
        MethodStructure structure = MethodStructure.builder()
                .annotations(annotations)
                .build();

        assertEquals("@Override", structure.getAnnotations().get(0));
        assertEquals("@Transactional", structure.getAnnotations().get(1));
        assertEquals("@Cacheable", structure.getAnnotations().get(2));
    }

    @Test
    @DisplayName("测试方法调用在不同行号")
    void testMethodCallsAtDifferentLines() {
        MethodStructure structure = MethodStructure.builder()
                .methodCalls(List.of(
                        MethodStructure.MethodCallInfo.builder().lineNumber(1).build(),
                        MethodStructure.MethodCallInfo.builder().lineNumber(100).build()
                ))
                .build();

        assertEquals(1, structure.getMethodCalls().get(0).getLineNumber());
        assertEquals(100, structure.getMethodCalls().get(1).getLineNumber());
    }
}