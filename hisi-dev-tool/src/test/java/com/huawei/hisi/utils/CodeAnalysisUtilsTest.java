package com.huawei.hisi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CodeAnalysisUtils 单元测试")
class CodeAnalysisUtilsTest {

    @Test
    @DisplayName("构建 Java 文件路径 - 正常情况")
    void buildJavaFilePath_shouldReturnCorrectPath() {
        String result = CodeAnalysisUtils.buildJavaFilePath(
            "/project",
            "com.example.MyClass"
        );
        // Windows 路径格式兼容
        assertTrue(result.endsWith("com/example/MyClass.java") || result.endsWith("com\\example\\MyClass.java"));
    }

    @Test
    @DisplayName("构建 Java 文件路径 - 空参数")
    void buildJavaFilePath_withNullParams_shouldReturnEmpty() {
        assertEquals("", CodeAnalysisUtils.buildJavaFilePath(null, "com.example.MyClass"));
        assertEquals("", CodeAnalysisUtils.buildJavaFilePath("/project", null));
    }

    @Test
    @DisplayName("估算 Token 数量 - 正常估算")
    void estimateTokens_shouldEstimateCorrectly() {
        String text = "Hello World Test";
        int tokens = CodeAnalysisUtils.estimateTokens(text);
        assertTrue(tokens >= 3 && tokens <= 5);
    }

    @Test
    @DisplayName("估算 Token 数量 - 空字符串")
    void estimateTokens_withEmptyString_shouldReturnZero() {
        assertEquals(0, CodeAnalysisUtils.estimateTokens(""));
        assertEquals(0, CodeAnalysisUtils.estimateTokens(null));
    }

    @Test
    @DisplayName("检查文件存在 - 不存在的文件")
    void fileExists_withNonExistentFile_shouldReturnFalse() {
        assertFalse(CodeAnalysisUtils.fileExists("/non/existent/path.txt"));
        assertFalse(CodeAnalysisUtils.fileExists(null));
        assertFalse(CodeAnalysisUtils.fileExists(""));
    }

    @Test
    @DisplayName("从堆栈提取类名 - 正常情况")
    void extractClassNameFromStackTrace_shouldExtractCorrectly() {
        String stackLine = "	at com.example.MyClass.myMethod(MyClass.java:10)";
        assertEquals("com.example.MyClass",
            CodeAnalysisUtils.extractClassNameFromStackTrace(stackLine));
    }

    @Test
    @DisplayName("从堆栈提取类名 - 无效格式")
    void extractClassNameFromStackTrace_withInvalidFormat_shouldReturnEmpty() {
        assertEquals("", CodeAnalysisUtils.extractClassNameFromStackTrace(null));
        assertEquals("", CodeAnalysisUtils.extractClassNameFromStackTrace(""));
        assertEquals("", CodeAnalysisUtils.extractClassNameFromStackTrace("no class here"));
    }

    @Test
    @DisplayName("从堆栈提取方法名 - 正常情况")
    void extractMethodNameFromStackTrace_shouldExtractCorrectly() {
        String stackLine = "	at com.example.MyClass.myMethod(MyClass.java:10)";
        assertEquals("myMethod",
            CodeAnalysisUtils.extractMethodNameFromStackTrace(stackLine));
    }

    @Test
    @DisplayName("从堆栈提取行号 - 正常情况")
    void extractLineNumberFromStackTrace_shouldExtractCorrectly() {
        String stackLine = "	at com.example.MyClass.myMethod(MyClass.java:42)";
        assertEquals(42,
            CodeAnalysisUtils.extractLineNumberFromStackTrace(stackLine));
    }

    @Test
    @DisplayName("从堆栈提取行号 - 无效格式")
    void extractLineNumberFromStackTrace_withInvalidFormat_shouldReturnMinusOne() {
        assertEquals(-1, CodeAnalysisUtils.extractLineNumberFromStackTrace(null));
        assertEquals(-1, CodeAnalysisUtils.extractLineNumberFromStackTrace(""));
        assertEquals(-1, CodeAnalysisUtils.extractLineNumberFromStackTrace("no line number"));
    }
}