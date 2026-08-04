package com.huawei.hisi.knowledgegraph.util;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * 方法体压缩工具类
 * 复用自 HisiURIMethodChainToDBServiceImpl 的压缩算法
 */
public final class MethodBodyCompressor {

    /**
     * 压缩方法声明内容
     * @param method 待处理的MethodDeclaration对象
     * @return 压缩后的核心代码字符串
     */
    public static String compress(MethodDeclaration method) {
        if (method == null) {
            return "";
        }

        // 1. 克隆方法对象避免修改原对象
        MethodDeclaration clonedMethod = method.clone();

        // 2. 递归移除所有注释（包括行注释、块注释、文档注释）
        removeAllComments(clonedMethod);

        // 3. 将AST转换为字符串
        String rawCode = clonedMethod.toString();

        // 4. 压缩空白字符和格式，保留最小语法正确性
        return compressWhitespace(rawCode);
    }

    /**
     * 递归移除节点及其所有子节点的注释
     */
    private static void removeAllComments(Node node) {
        // 移除当前节点的注释
        node.removeComment();
        // 递归处理所有子节点
        for (Node child : node.getChildNodes()) {
            removeAllComments(child);
        }
    }

    /**
     * 压缩空白字符，去除多余空格、换行等
     */
    private static String compressWhitespace(String code) {
        // 步骤1：将所有空白字符（换行、制表符、多空格）替换为单个空格
        String compressed = code.replaceAll("\\s+", " ");

        // 步骤2：移除语法符号周围的冗余空格（保证语法正确的前提下最小化）
        compressed = compressed
            // 处理括号
            .replaceAll(" \\(", "(")
            .replaceAll("\\) ", ")")
            .replaceAll(" \\{", "{")
            .replaceAll("} ", "}")
            .replaceAll(" \\[", "[")
            .replaceAll("\\] ", "]")
            // 处理逗号
            .replaceAll(" ,", ",")
            .replaceAll(", ", ",")
            // 处理分号
            .replaceAll(" ;", ";")
            .replaceAll("; ", ";")
            // 处理冒号（泛型、标签等）
            .replaceAll(" :", ":")
            .replaceAll(": ", ":")
            // 处理泛型尖括号
            .replaceAll(" < ", "<")
            .replaceAll(" > ", ">")
            // 处理运算符
            .replaceAll(" \\+ ", "+")
            .replaceAll(" - ", "-")
            .replaceAll(" \\* ", "*")
            .replaceAll(" / ", "/")
            .replaceAll(" = ", "=")
            .replaceAll(" == ", "==")
            .replaceAll(" != ", "!=")
            .replaceAll(" < ", "<")
            .replaceAll(" > ", ">")
            .replaceAll(" <= ", "<=")
            .replaceAll(" >= ", ">=")
            .replaceAll(" && ", "&&")
            .replaceAll(" \\|\\| ", "||")
            .trim();

        return compressed;
    }

    /**
     * 截断过长的字符串
     * @param str 原字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    public static String truncate(String str, int maxLength) {
        if (str == null) return null;
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }
}
