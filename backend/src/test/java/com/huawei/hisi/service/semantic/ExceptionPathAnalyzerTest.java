package com.huawei.hisi.service.semantic;

import com.huawei.hisi.config.ExceptionInheritanceConfig;
import com.huawei.hisi.service.semantic.model.ExceptionNode;
import com.huawei.hisi.service.semantic.model.ExceptionPath;
import com.huawei.hisi.service.semantic.model.MethodCategory;
import com.huawei.hisi.service.semantic.model.MethodNode;
import com.huawei.hisi.service.semantic.model.PropagationPath;
import com.huawei.hisi.service.semantic.impl.InMemoryCodeKnowledgeGraph;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * ExceptionPathAnalyzer单元测试
 */
public class ExceptionPathAnalyzerTest {

    private InMemoryCodeKnowledgeGraph graph;
    private ExceptionInheritanceConfig exceptionConfig;
    private ExceptionPathAnalyzer analyzer;

    @Before
    public void setUp() {
        graph = new InMemoryCodeKnowledgeGraph();
        exceptionConfig = new ExceptionInheritanceConfig();
        analyzer = new ExceptionPathAnalyzer(graph, exceptionConfig);

        // 构建测试数据
        setupTestData();
    }

    private void setupTestData() {
        // 创建方法节点
        MethodNode userService = MethodNode.builder()
                .nodeId("method-1")
                .className("com.example.UserService")
                .methodName("login")
                .signature("login(String username, String password)")
                .thrownExceptions(List.of("java.lang.NullPointerException",
                        "java.lang.IllegalArgumentException"))
                .callsMethods(List.of("method-2"))
                .complexity(5)
                .category(MethodCategory.BUSINESS_LOGIC)
                .filePath("src/main/java/com/example/UserService.java")
                .startLineNumber(50)
                .build();

        MethodNode authService = MethodNode.builder()
                .nodeId("method-2")
                .className("com.example.AuthService")
                .methodName("validateToken")
                .signature("validateToken(String token)")
                .thrownExceptions(List.of("java.lang.NullPointerException"))
                .callsMethods(List.of("method-3"))
                .calledByMethods(List.of("method-1"))
                .complexity(3)
                .category(MethodCategory.BUSINESS_LOGIC)
                .filePath("src/main/java/com/example/AuthService.java")
                .startLineNumber(30)
                .build();

        MethodNode tokenService = MethodNode.builder()
                .nodeId("method-3")
                .className("com.example.TokenService")
                .methodName("parseToken")
                .signature("parseToken(String token)")
                .thrownExceptions(List.of("java.lang.NullPointerException"))
                .calledByMethods(List.of("method-2"))
                .complexity(2)
                .category(MethodCategory.UTIL_METHOD)
                .filePath("src/main/java/com/example/TokenService.java")
                .startLineNumber(20)
                .build();

        // 创建异常节点
        ExceptionNode npeException = ExceptionNode.builder()
                .nodeId("exception-1")
                .exceptionType("java.lang.NullPointerException")
                .simpleName("NullPointerException")
                .packageName("java.lang")
                .category(ExceptionNode.ExceptionCategory.RUNTIME)
                .description("Null pointer reference")
                .thrownByMethods(List.of("method-1", "method-2", "method-3"))
                .build();

        ExceptionNode iaeException = ExceptionNode.builder()
                .nodeId("exception-2")
                .exceptionType("java.lang.IllegalArgumentException")
                .simpleName("IllegalArgumentException")
                .packageName("java.lang")
                .category(ExceptionNode.ExceptionCategory.RUNTIME)
                .description("Illegal argument passed")
                .thrownByMethods(List.of("method-1"))
                .build();

        // 添加到图谱
        graph.addMethod(userService);
        graph.addMethod(authService);
        graph.addMethod(tokenService);
        graph.addException(npeException);
        graph.addException(iaeException);

        // 添加调用关系
        graph.addCallRelation("method-1", "method-2");
        graph.addCallRelation("method-2", "method-3");

        // 添加throws关系
        graph.addThrowsRelation("method-1", "java.lang.NullPointerException");
        graph.addThrowsRelation("method-1", "java.lang.IllegalArgumentException");
        graph.addThrowsRelation("method-2", "java.lang.NullPointerException");
        graph.addThrowsRelation("method-3", "java.lang.NullPointerException");
    }

    @Test
    public void testAnalyzeExceptionPath_Basic() {
        ExceptionPath result = analyzer.analyzeExceptionPath(
                "java.lang.NullPointerException",
                "com.example.UserService.login");

        assertNotNull(result);
        assertEquals("java.lang.NullPointerException", result.getExceptionType());
        assertEquals("com.example.UserService.login", result.getLocation());
        assertNotNull(result.getPropagationPaths());
        assertFalse(result.getPropagationPaths().isEmpty());
    }

    @Test
    public void testAnalyzeExceptionPath_ReturnsMostLikelyPath() {
        ExceptionPath result = analyzer.analyzeExceptionPath(
                "java.lang.NullPointerException",
                "com.example.UserService.login");

        PropagationPath mostLikely = result.getMostLikelyPath();
        assertNotNull(mostLikely);
        assertTrue(mostLikely.getProbability() > 0);
    }

    @Test
    public void testAnalyzeExceptionPath_Top3Paths() {
        ExceptionPath result = analyzer.analyzeExceptionPath(
                "java.lang.NullPointerException",
                "com.example.UserService.login");

        List<PropagationPath> top3 = result.getTopPaths(3);
        assertNotNull(top3);
        assertTrue(top3.size() <= 3);

        // 验证路径按概率排序
        for (int i = 0; i < top3.size() - 1; i++) {
            assertTrue(top3.get(i).getProbability() >= top3.get(i + 1).getProbability());
        }
    }

    @Test
    public void testAnalyzeExceptionPath_ProbabilityCalculation() {
        ExceptionPath result = analyzer.analyzeExceptionPath(
                "java.lang.NullPointerException",
                "com.example.UserService.login");

        // 验证所有概率在有效范围内
        for (PropagationPath path : result.getPropagationPaths()) {
            assertTrue(path.getProbability() >= 0.0);
            assertTrue(path.getProbability() <= 1.0);
        }
    }

    @Test
    public void testAnalyzeExceptionPath_IllegalArgumentException() {
        ExceptionPath result = analyzer.analyzeExceptionPath(
                "java.lang.IllegalArgumentException",
                "com.example.UserService.login");

        assertNotNull(result);
        assertEquals("java.lang.IllegalArgumentException", result.getExceptionType());
    }

    @Test
    public void testGetTopExceptionSources() {
        List<String> topSources = analyzer.getTopExceptionSources(
                "java.lang.NullPointerException",
                "com.example.UserService.login",
                3);

        assertNotNull(topSources);
        assertTrue(topSources.size() <= 3);
    }

    @Test
    public void testAnalyzeBatchExceptionPaths() {
        List<ExceptionPath> results = analyzer.analyzeBatchExceptionPaths(
                List.of("java.lang.NullPointerException", "java.lang.IllegalArgumentException"),
                "com.example.UserService.login");

        assertNotNull(results);
        assertEquals(2, results.size());
    }

    @Test
    public void testIsReady() {
        assertTrue(analyzer.isReady());
    }

    @Test
    public void testGetStatusInfo() {
        String statusInfo = analyzer.getStatusInfo();
        assertNotNull(statusInfo);
        assertTrue(statusInfo.contains("方法节点数"));
        assertTrue(statusInfo.contains("异常节点数"));
    }

    @Test
    public void testAnalyzeExceptionPath_EmptyGraph() {
        InMemoryCodeKnowledgeGraph emptyGraph = new InMemoryCodeKnowledgeGraph();
        ExceptionInheritanceConfig emptyExceptionConfig = new ExceptionInheritanceConfig();
        ExceptionPathAnalyzer emptyAnalyzer = new ExceptionPathAnalyzer(emptyGraph, emptyExceptionConfig);

        ExceptionPath result = emptyAnalyzer.analyzeExceptionPath(
                "java.lang.NullPointerException",
                "com.example.SomeService.method");

        assertNotNull(result);
        // 空图谱时应该返回推断路径
        assertFalse(result.getPropagationPaths().isEmpty());
    }

    @Test
    public void testPropagationPath_FormatCallChain() {
        PropagationPath path = PropagationPath.builder()
                .sourceMethod("com.example.UserService.login")
                .callChain(List.of("com.example.UserService.login", "com.example.AuthService.validateToken"))
                .probability(0.8)
                .build();

        String formatted = path.formatCallChain();
        assertNotNull(formatted);
        assertTrue(formatted.contains("UserService.login"));
        assertTrue(formatted.contains("AuthService.validateToken"));
    }

    @Test
    public void testPropagationPath_IsDirectThrow() {
        // 直接抛出
        PropagationPath directPath = PropagationPath.builder()
                .sourceMethod("method")
                .callChain(List.of("method"))
                .probability(1.0)
                .build();

        assertTrue(directPath.isDirectThrow());

        // 非直接抛出
        PropagationPath indirectPath = PropagationPath.builder()
                .sourceMethod("methodA")
                .callChain(List.of("methodA", "methodB", "methodC"))
                .probability(0.5)
                .build();

        assertFalse(indirectPath.isDirectThrow());
    }

    @Test
    public void testExceptionNode_IsRuntimeException() {
        ExceptionNode npe = ExceptionNode.builder()
                .category(ExceptionNode.ExceptionCategory.RUNTIME)
                .build();

        assertTrue(npe.isRuntimeException());
        assertFalse(npe.isCheckedException());
    }

    @Test
    public void testExceptionNode_CheckedException() {
        ExceptionNode checked = ExceptionNode.builder()
                .category(ExceptionNode.ExceptionCategory.CHECKED)
                .build();

        assertTrue(checked.isCheckedException());
        assertFalse(checked.isRuntimeException());
    }

    @Test
    public void testMethodNode_ThrowsException() {
        MethodNode method = MethodNode.builder()
                .nodeId("test-1")
                .className("TestClass")
                .methodName("testMethod")
                .thrownExceptions(List.of("java.lang.NullPointerException"))
                .build();

        assertTrue(method.throwsException("java.lang.NullPointerException"));
        assertFalse(method.throwsException("java.lang.IOException"));
    }

    @Test
    public void testMethodNode_IsExceptionSource() {
        MethodNode exceptionSource = MethodNode.builder()
                .nodeId("test-1")
                .thrownExceptions(List.of("java.lang.Exception"))
                .build();

        assertTrue(exceptionSource.isExceptionSource());

        MethodNode noException = MethodNode.builder()
                .nodeId("test-2")
                .thrownExceptions(null)
                .build();

        assertFalse(noException.isExceptionSource());
    }

    @Test
    public void testTop3HitRate_Measurement() {
        // 模拟历史故障案例分析Top3命中率
        // 在实际场景中应该 > 70%

        ExceptionPath result = analyzer.analyzeExceptionPath(
                "java.lang.NullPointerException",
                "com.example.UserService.login");

        List<PropagationPath> top3 = result.getTopPaths(3);

        // 验证至少有一条高概率路径
        boolean hasHighProbabilityPath = top3.stream()
                .anyMatch(p -> p.getProbability() >= 0.7);

        assertTrue("Top3应包含至少一条高概率路径", hasHighProbabilityPath);
    }
}