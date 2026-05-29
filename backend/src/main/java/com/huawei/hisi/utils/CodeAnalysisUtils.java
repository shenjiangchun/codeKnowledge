package com.huawei.hisi.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 代码分析工具类
 * 提供文件路径构建、Token 估算、代码片段读取等公共方法
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public final class CodeAnalysisUtils {

    private CodeAnalysisUtils() {
        // 工具类禁止实例化
    }

    /**
     * 根据类名构建 Java 文件路径
     *
     * @param projectDir 项目根目录
     * @param className  全限定类名
     * @return 绝对文件路径
     */
    public static String buildJavaFilePath(String projectDir, String className) {
        if (projectDir == null || className == null) {
            return "";
        }
        String path = className.replace('.', '/') + ".java";
        return new File(projectDir, path).getAbsolutePath();
    }

    /**
     * 检查文件是否存在
     *
     * @param filePath 文件路径
     * @return 是否存在
     */
    public static boolean fileExists(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        return new File(filePath).exists();
    }

    /**
     * 估算文本 Token 数量
     * 简单估算：每个单词约 1.3 个 token
     *
     * @param text 输入文本
     * @return 估算的 token 数量
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) (text.split("\\s+").length * 1.3);
    }

    /**
     * 读取文件指定行范围的代码上下文
     *
     * @param filePath     文件路径
     * @param lineNum      中心行号
     * @param contextLines 上下文行数（前后各取一半）
     * @return 代码片段字符串
     */
    public static String readCodeContext(String filePath, int lineNum, int contextLines) {
        if (!fileExists(filePath)) {
            return "";
        }

        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath));
            int start = Math.max(0, lineNum - contextLines / 2);
            int end = Math.min(lines.size(), lineNum + contextLines / 2);
            return String.join("\n", lines.subList(start, end));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 从堆栈跟踪中提取类名
     *
     * @param stackTraceLine 堆栈跟踪行，如 "at com.example.MyClass.myMethod(MyClass.java:10)"
     * @return 类名，如 "com.example.MyClass"
     */
    public static String extractClassNameFromStackTrace(String stackTraceLine) {
        if (stackTraceLine == null || stackTraceLine.isEmpty()) {
            return "";
        }

        // 匹配格式: at package.Class.method(Class.java:line)
        int atIdx = stackTraceLine.indexOf("at ");
        if (atIdx == -1) {
            return "";
        }

        String remaining = stackTraceLine.substring(atIdx + 3).trim();
        int parenIdx = remaining.indexOf("(");
        if (parenIdx == -1) {
            return "";
        }

        String methodPart = remaining.substring(0, parenIdx);
        int lastDot = methodPart.lastIndexOf(".");
        if (lastDot == -1) {
            return "";
        }

        return methodPart.substring(0, lastDot);
    }

    /**
     * 从堆栈跟踪中提取方法名
     *
     * @param stackTraceLine 堆栈跟踪行
     * @return 方法名
     */
    public static String extractMethodNameFromStackTrace(String stackTraceLine) {
        if (stackTraceLine == null || stackTraceLine.isEmpty()) {
            return "";
        }

        int atIdx = stackTraceLine.indexOf("at ");
        if (atIdx == -1) {
            return "";
        }

        String remaining = stackTraceLine.substring(atIdx + 3).trim();
        int parenIdx = remaining.indexOf("(");
        if (parenIdx == -1) {
            return "";
        }

        String methodPart = remaining.substring(0, parenIdx);
        int lastDot = methodPart.lastIndexOf(".");
        if (lastDot == -1) {
            return methodPart;
        }

        return methodPart.substring(lastDot + 1);
    }

    /**
     * 从堆栈跟踪中提取行号
     *
     * @param stackTraceLine 堆栈跟踪行
     * @return 行号，解析失败返回 -1
     */
    public static int extractLineNumberFromStackTrace(String stackTraceLine) {
        if (stackTraceLine == null || stackTraceLine.isEmpty()) {
            return -1;
        }

        int colonIdx = stackTraceLine.lastIndexOf(":");
        int parenIdx = stackTraceLine.lastIndexOf(")");

        if (colonIdx == -1 || parenIdx == -1 || colonIdx >= parenIdx) {
            return -1;
        }

        try {
            return Integer.parseInt(stackTraceLine.substring(colonIdx + 1, parenIdx));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}