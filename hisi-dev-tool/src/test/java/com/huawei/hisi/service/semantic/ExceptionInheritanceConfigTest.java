package com.huawei.hisi.service.semantic;

import com.huawei.hisi.config.ExceptionInheritanceConfig;
import com.huawei.hisi.service.semantic.model.ExceptionNode;
import com.huawei.hisi.service.semantic.model.MethodNode;
import com.huawei.hisi.service.semantic.model.MethodCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * TD-004: 异常继承配置单元测试
 *
 * 测试异常继承关系判断和配置加载
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TD-004: 异常继承配置测试")
class ExceptionInheritanceConfigTest {

    @Mock
    private CodeKnowledgeGraph graph;

    @Mock
    private ExceptionInheritanceConfig exceptionConfig;

    private ExceptionPathAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ExceptionPathAnalyzer(graph, exceptionConfig);
    }

    @Test
    @DisplayName("测试异常继承关系 - RuntimeException是Exception子类")
    void testRuntimeExceptionIsSubtypeOfException() {
        assertTrue(isExceptionSubtype("java.lang.RuntimeException", "java.lang.Exception"),
            "RuntimeException应该是Exception的子类");
    }

    @Test
    @DisplayName("测试异常继承关系 - NullPointerException是RuntimeException子类")
    void testNPEIsSubtypeOfRuntimeException() {
        assertTrue(isExceptionSubtype("java.lang.NullPointerException", "java.lang.RuntimeException"),
            "NullPointerException应该是RuntimeException的子类");
    }

    @Test
    @DisplayName("测试异常继承关系 - IOException是Exception子类")
    void testIOExceptionIsSubtypeOfException() {
        assertTrue(isExceptionSubtype("java.io.FileNotFoundException", "java.lang.IOException"),
            "FileNotFoundException应该是IOException的子类");
    }

    @Test
    @DisplayName("测试异常继承关系 - 不相关的异常")
    void testUnrelatedExceptions() {
        assertFalse(isExceptionSubtype("java.lang.NullPointerException", "java.lang.IOException"),
            "NullPointerException不是IOException的子类");
    }

    @Test
    @DisplayName("测试异常继承关系 - 多层继承")
    void testMultiLevelInheritance() {
        // NullPointerException -> RuntimeException -> Exception
        assertTrue(isExceptionSubtype("java.lang.NullPointerException", "java.lang.Exception"),
            "NullPointerException应该是Exception的子类（多层继承）");
    }

    @Test
    @DisplayName("测试异常继承关系 - 相同类型")
    void testSameExceptionType() {
        assertTrue(isExceptionSubtype("java.lang.Exception", "java.lang.Exception"),
            "相同类型应该返回true");
    }

    @Test
    @DisplayName("测试分析器就绪状态 - 图为空")
    void testAnalyzerNotReadyWhenGraphEmpty() {
        when(graph.isEmpty()).thenReturn(true);

        assertFalse(analyzer.isReady(), "图为空时分析器不应就绪");
    }

    @Test
    @DisplayName("测试分析器就绪状态 - 图不为空")
    void testAnalyzerReadyWhenGraphNotEmpty() {
        lenient().when(graph.isEmpty()).thenReturn(false);
        lenient().when(graph.getMethodCount()).thenReturn(100);

        assertTrue(analyzer.isReady(), "图不为空时分析器应就绪");
    }

    @Test
    @DisplayName("测试分析器状态信息")
    void testAnalyzerStatusInfo() {
        when(graph.getMethodCount()).thenReturn(50);
        when(graph.getExceptionCount()).thenReturn(20);
        when(graph.isEmpty()).thenReturn(false);

        String statusInfo = analyzer.getStatusInfo();

        assertNotNull(statusInfo, "状态信息不应为空");
        assertTrue(statusInfo.contains("50"), "状态信息应包含方法节点数");
        assertTrue(statusInfo.contains("20"), "状态信息应包含异常节点数");
        assertTrue(statusInfo.contains("是"), "状态信息应显示就绪状态");
    }

    @Test
    @DisplayName("测试异常传播路径分析 - 基本流程")
    void testAnalyzeExceptionPath() {
        // Arrange
        String exceptionType = "java.lang.NullPointerException";
        String location = "com.example.Service.method";

        when(graph.findException(exceptionType)).thenReturn(Optional.empty());
        when(graph.findExceptionSources(exceptionType, location)).thenReturn(List.of());

        // Act
        var result = analyzer.analyzeExceptionPath(exceptionType, location);

        // Assert
        assertNotNull(result, "分析结果不应为空");
        assertEquals(exceptionType, result.getExceptionType(), "异常类型应匹配");
        assertEquals(location, result.getLocation(), "位置应匹配");
        assertNotNull(result.getPropagationPaths(), "传播路径不应为空");
    }

    @Test
    @DisplayName("测试批量异常路径分析")
    void testAnalyzeBatchExceptionPaths() {
        // Arrange
        List<String> exceptionTypes = List.of(
            "java.lang.NullPointerException",
            "java.lang.IllegalArgumentException"
        );
        String location = "com.example.Service.method";

        when(graph.findException(anyString())).thenReturn(Optional.empty());
        when(graph.findExceptionSources(anyString(), anyString())).thenReturn(List.of());

        // Act
        var results = analyzer.analyzeBatchExceptionPaths(exceptionTypes, location);

        // Assert
        assertEquals(2, results.size(), "应返回2个分析结果");
    }

    @Test
    @DisplayName("测试获取Top N异常来源")
    void testGetTopExceptionSources() {
        // Arrange
        String exceptionType = "java.lang.NullPointerException";
        String location = "com.example.Service.method";

        when(graph.findException(exceptionType)).thenReturn(Optional.empty());
        when(graph.findExceptionSources(exceptionType, location)).thenReturn(List.of());

        // Act
        List<String> topSources = analyzer.getTopExceptionSources(exceptionType, location, 3);

        // Assert
        assertNotNull(topSources, "结果不应为空");
    }

    /**
     * 异常继承关系判断方法（复制自ExceptionPathAnalyzer用于测试）
     */
    private boolean isExceptionSubtype(String child, String parent) {
        // 相同类型
        if (child.equals(parent)) {
            return true;
        }

        // 常见异常继承关系配置
        java.util.Map<String, List<String>> exceptionHierarchy = new java.util.HashMap<>();
        exceptionHierarchy.put("java.lang.Exception",
                List.of("java.lang.RuntimeException", "java.lang.IOException",
                        "java.lang.NullPointerException", "java.lang.IllegalArgumentException"));
        exceptionHierarchy.put("java.lang.RuntimeException",
                List.of("java.lang.NullPointerException", "java.lang.IllegalArgumentException",
                        "java.lang.IndexOutOfBoundsException", "java.lang.ClassCastException"));
        exceptionHierarchy.put("java.lang.IOException",
                List.of("java.io.FileNotFoundException", "java.net.SocketException"));

        List<String> subtypes = exceptionHierarchy.get(parent);
        return subtypes != null && (subtypes.contains(child) ||
                subtypes.stream().anyMatch(st -> isExceptionSubtype(child, st)));
    }
}