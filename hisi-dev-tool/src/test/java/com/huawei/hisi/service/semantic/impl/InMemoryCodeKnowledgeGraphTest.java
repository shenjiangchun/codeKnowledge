package com.huawei.hisi.service.semantic.impl;

import com.huawei.hisi.service.semantic.model.ExceptionNode;
import com.huawei.hisi.service.semantic.model.MethodCategory;
import com.huawei.hisi.service.semantic.model.MethodNode;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * InMemoryCodeKnowledgeGraph单元测试
 */
public class InMemoryCodeKnowledgeGraphTest {

    private InMemoryCodeKnowledgeGraph graph;

    @Before
    public void setUp() {
        graph = new InMemoryCodeKnowledgeGraph();
    }

    @Test
    public void testAddMethod() {
        MethodNode method = MethodNode.builder()
                .nodeId("method-1")
                .className("com.example.TestService")
                .methodName("testMethod")
                .signature("testMethod()")
                .build();

        graph.addMethod(method);

        assertEquals(1, graph.getMethodCount());
        assertTrue(graph.findMethod("com.example.TestService", "testMethod").isPresent());
    }

    @Test
    public void testFindMethodById() {
        MethodNode method = MethodNode.builder()
                .nodeId("method-unique-id")
                .className("TestService")
                .methodName("testMethod")
                .build();

        graph.addMethod(method);

        Optional<MethodNode> found = graph.findMethodById("method-unique-id");
        assertTrue(found.isPresent());
        assertEquals("testMethod", found.get().getMethodName());
    }

    @Test
    public void testFindMethod_NotExists() {
        Optional<MethodNode> found = graph.findMethod("NonExistClass", "nonExistMethod");
        assertFalse(found.isPresent());
    }

    @Test
    public void testAddException() {
        ExceptionNode exception = ExceptionNode.builder()
                .nodeId("exception-1")
                .exceptionType("java.lang.NullPointerException")
                .simpleName("NullPointerException")
                .build();

        graph.addException(exception);

        assertEquals(1, graph.getExceptionCount());
        assertTrue(graph.findException("java.lang.NullPointerException").isPresent());
    }

    @Test
    public void testAddCallRelation() {
        MethodNode caller = MethodNode.builder()
                .nodeId("caller-1")
                .className("CallerService")
                .methodName("callerMethod")
                .build();

        MethodNode callee = MethodNode.builder()
                .nodeId("callee-1")
                .className("CalleeService")
                .methodName("calleeMethod")
                .build();

        graph.addMethod(caller);
        graph.addMethod(callee);
        graph.addCallRelation("caller-1", "callee-1");

        List<MethodNode> callees = graph.findCallees("caller-1", 1);
        assertEquals(1, callees.size());
        assertEquals("calleeMethod", callees.get(0).getMethodName());

        List<MethodNode> callers = graph.findCallers("callee-1", 1);
        assertEquals(1, callers.size());
        assertEquals("callerMethod", callers.get(0).getMethodName());
    }

    @Test
    public void testAddThrowsRelation() {
        MethodNode method = MethodNode.builder()
                .nodeId("method-1")
                .className("TestService")
                .methodName("testMethod")
                .build();

        graph.addMethod(method);
        graph.addThrowsRelation("method-1", "java.lang.NullPointerException");

        List<MethodNode> throwers = graph.findMethodsThrowingException("java.lang.NullPointerException");
        assertEquals(1, throwers.size());
        assertEquals("testMethod", throwers.get(0).getMethodName());
    }

    @Test
    public void testFindCallers_MultiDepth() {
        MethodNode m1 = MethodNode.builder().nodeId("m1").className("A").methodName("a").build();
        MethodNode m2 = MethodNode.builder().nodeId("m2").className("B").methodName("b").build();
        MethodNode m3 = MethodNode.builder().nodeId("m3").className("C").methodName("c").build();

        graph.addMethod(m1);
        graph.addMethod(m2);
        graph.addMethod(m3);

        graph.addCallRelation("m1", "m2");
        graph.addCallRelation("m2", "m3");

        // 深度1：只有m2调用m3
        List<MethodNode> depth1Callers = graph.findCallers("m3", 1);
        assertEquals(1, depth1Callers.size());

        // 深度2：m2和m1都可能通过调用链间接调用m3
        List<MethodNode> depth2Callers = graph.findCallers("m3", 2);
        assertTrue(depth2Callers.size() >= 1);
    }

    @Test
    public void testFindCallees_MultiDepth() {
        MethodNode m1 = MethodNode.builder().nodeId("m1").className("A").methodName("a").build();
        MethodNode m2 = MethodNode.builder().nodeId("m2").className("B").methodName("b").build();
        MethodNode m3 = MethodNode.builder().nodeId("m3").className("C").methodName("c").build();

        graph.addMethod(m1);
        graph.addMethod(m2);
        graph.addMethod(m3);

        graph.addCallRelation("m1", "m2");
        graph.addCallRelation("m2", "m3");

        // 深度1：只有m2
        List<MethodNode> depth1Callees = graph.findCallees("m1", 1);
        assertEquals(1, depth1Callees.size());

        // 深度2：m2和m3
        List<MethodNode> depth2Callees = graph.findCallees("m1", 2);
        assertTrue(depth2Callees.size() >= 1);
    }

    @Test
    public void testFindCallPath() {
        MethodNode m1 = MethodNode.builder().nodeId("m1").className("A").methodName("a").build();
        MethodNode m2 = MethodNode.builder().nodeId("m2").className("B").methodName("b").build();
        MethodNode m3 = MethodNode.builder().nodeId("m3").className("C").methodName("c").build();

        graph.addMethod(m1);
        graph.addMethod(m2);
        graph.addMethod(m3);

        graph.addCallRelation("m1", "m2");
        graph.addCallRelation("m2", "m3");

        List<MethodNode> path = graph.findCallPath("m1", "C.c");
        assertFalse(path.isEmpty());
        assertEquals("a", path.get(0).getMethodName());
    }

    @Test
    public void testClear() {
        MethodNode method = MethodNode.builder().nodeId("m1").className("A").methodName("a").build();
        graph.addMethod(method);

        assertEquals(1, graph.getMethodCount());

        graph.clear();

        assertEquals(0, graph.getMethodCount());
        assertTrue(graph.isEmpty());
    }

    @Test
    public void testIsEmpty() {
        assertTrue(graph.isEmpty());

        MethodNode method = MethodNode.builder().nodeId("m1").className("A").methodName("a").build();
        graph.addMethod(method);

        assertFalse(graph.isEmpty());
    }

    @Test
    public void testFindExceptionSources() {
        MethodNode m1 = MethodNode.builder()
                .nodeId("m1")
                .className("UserService")
                .methodName("login")
                .thrownExceptions(List.of("java.lang.NullPointerException"))
                .build();

        MethodNode m2 = MethodNode.builder()
                .nodeId("m2")
                .className("AuthService")
                .methodName("validate")
                .thrownExceptions(List.of("java.lang.NullPointerException"))
                .build();

        graph.addMethod(m1);
        graph.addMethod(m2);
        graph.addCallRelation("m2", "m1");

        graph.addThrowsRelation("m1", "java.lang.NullPointerException");
        graph.addThrowsRelation("m2", "java.lang.NullPointerException");

        List<MethodNode> sources = graph.findExceptionSources(
                "java.lang.NullPointerException",
                "AuthService.validate");

        assertFalse(sources.isEmpty());
    }

    @Test
    public void testGetExceptionCount() {
        assertEquals(0, graph.getExceptionCount());

        ExceptionNode e1 = ExceptionNode.builder()
                .nodeId("e1")
                .exceptionType("java.lang.NullPointerException")
                .build();

        graph.addException(e1);

        assertEquals(1, graph.getExceptionCount());
    }

    @Test
    public void testFindMethodBySignature() {
        MethodNode method = MethodNode.builder()
                .nodeId("m1")
                .className("com.example.UserService")
                .methodName("login")
                .signature("login(String, String)")
                .build();

        graph.addMethod(method);

        Optional<MethodNode> found = graph.findMethodBySignature("com.example.UserService.login");
        assertTrue(found.isPresent());
    }
}