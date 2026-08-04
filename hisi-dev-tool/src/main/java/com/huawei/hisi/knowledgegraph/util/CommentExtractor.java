package com.huawei.hisi.knowledgegraph.util;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 注释提取工具类
 * 从Java源文件提取方法注释，支持Javadoc和行注释
 */
public final class CommentExtractor {

    private static final Logger logger = LoggerFactory.getLogger(CommentExtractor.class);

    /**
     * 从Java源文件提取方法注释
     *
     * @param javaFile Java源文件路径
     * @return 方法名到注释摘要的映射
     */
    public static Map<String, String> extractMethodComments(Path javaFile) {
        Map<String, String> comments = new HashMap<>();

        if (javaFile == null || !Files.exists(javaFile)) {
            return comments;
        }

        try {
            String content = Files.readString(javaFile);
            CompilationUnit cu = StaticJavaParser.parse(content);

            // 查找所有类
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                // 查找类中的所有方法
                classDecl.getMethods().forEach(method -> {
                    String methodName = method.getNameAsString();
                    String comment = extractMethodComment(method);
                    comments.put(methodName, comment);
                });
            });

        } catch (IOException e) {
            logger.error("Failed to read file: {}", javaFile, e);
        } catch (Exception e) {
            logger.error("Failed to parse file: {}", javaFile, e);
        }

        return comments;
    }

    /**
     * 从方法声明提取注释
     *
     * @param method 方法声明
     * @return 注释摘要
     */
    private static String extractMethodComment(MethodDeclaration method) {
        // 1. 首先尝试获取Javadoc注释
        Optional<JavadocComment> javadoc = method.getJavadocComment();
        if (javadoc.isPresent()) {
            String description = parseJavadoc(javadoc.get());
            if (!description.isEmpty()) {
                return description;
            }
        }

        // 2. 尝试获取方法前的行注释
        Optional<Comment> comment = method.getComment();
        if (comment.isPresent() && comment.get() instanceof LineComment lineComment) {
            String lineCommentText = lineComment.getContent().trim();
            if (!lineCommentText.isEmpty()) {
                return lineCommentText;
            }
        }

        // 3. 尝试获取紧邻方法前的行注释（不在方法上的情况）
        String precedingLineComment = findPrecedingLineComment(method);
        if (precedingLineComment != null && !precedingLineComment.isEmpty()) {
            return precedingLineComment;
        }

        // 4. 如果没有注释，从方法名推断
        return inferFromMethodName(method.getNameAsString());
    }

    /**
     * 查找方法声明前的行注释
     */
    private static String findPrecedingLineComment(MethodDeclaration method) {
        if (!method.getBegin().isPresent()) {
            return null;
        }

        // 获取方法所在编译单元
        Optional<CompilationUnit> cuOpt = method.findCompilationUnit();
        if (cuOpt.isEmpty()) {
            return null;
        }

        CompilationUnit cu = cuOpt.get();
        int methodLine = method.getBegin().get().line;

        // 查找方法紧前一行或两行的行注释
        for (Comment comment : cu.getAllComments()) {
            if (comment instanceof LineComment lineComment) {
                int commentLine = comment.getBegin().get().line;
                // 行注释在方法前1-2行
                if (commentLine >= methodLine - 2 && commentLine < methodLine) {
                    String content = lineComment.getContent().trim();
                    if (!content.isEmpty()) {
                        return content;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 解析Javadoc注释，提取描述部分
     *
     * @param javadocComment Javadoc注释对象
     * @return 注释描述文本
     */
    public static String parseJavadoc(JavadocComment javadocComment) {
        if (javadocComment == null) {
            return "";
        }

        String content = javadocComment.getContent();

        // 提取描述部分（第一个句子或第一个非标签行）
        StringBuilder description = new StringBuilder();
        String[] lines = content.split("\n");

        for (String line : lines) {
            String trimmedLine = line.trim();

            // 跳过空行
            if (trimmedLine.isEmpty()) {
                continue;
            }

            // 跳过以 * 开头的行前缀
            if (trimmedLine.startsWith("*")) {
                trimmedLine = trimmedLine.substring(1).trim();
            }

            // 跳过以 @ 开头的标签行（包括行内标签）
            if (trimmedLine.startsWith("@")) {
                break;
            }

            // 如果行内包含 @ 标签，只取标签前的内容
            int atIndex = trimmedLine.indexOf("@");
            if (atIndex > 0) {
                trimmedLine = trimmedLine.substring(0, atIndex).trim();
            }

            if (!trimmedLine.isEmpty()) {
                if (description.length() > 0) {
                    description.append(" ");
                }
                description.append(trimmedLine);

                // 如果以句号结尾，提取第一个句子
                if (trimmedLine.endsWith("。") || trimmedLine.endsWith(".")) {
                    break;
                }
            }
        }

        String result = description.toString().trim();

        // 如果描述过长，只取第一个句子
        int periodIndex = result.indexOf("。");
        if (periodIndex > 0 && periodIndex < result.length() - 1) {
            result = result.substring(0, periodIndex + 1);
        } else {
            periodIndex = result.indexOf(". ");
            if (periodIndex > 0) {
                result = result.substring(0, periodIndex + 1);
            }
        }

        return result;
    }

    /**
     * 从方法名推断功能描述
     *
     * @param methodName 方法名
     * @return 推断的功能描述
     */
    public static String inferFromMethodName(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return "";
        }

        // 常见动词前缀映射
        if (methodName.startsWith("get")) {
            String rest = extractRest(methodName, "get");
            return "获取" + rest;
        }
        if (methodName.startsWith("set")) {
            String rest = extractRest(methodName, "set");
            return "设置" + rest;
        }
        if (methodName.startsWith("find")) {
            String rest = extractRest(methodName, "find");
            return "查询" + rest;
        }
        if (methodName.startsWith("create")) {
            String rest = extractRest(methodName, "create");
            return "创建" + rest;
        }
        if (methodName.startsWith("add")) {
            String rest = extractRest(methodName, "add");
            return "添加" + rest;
        }
        if (methodName.startsWith("delete")) {
            String rest = extractRest(methodName, "delete");
            return "删除" + rest;
        }
        if (methodName.startsWith("remove")) {
            String rest = extractRest(methodName, "remove");
            return "移除" + rest;
        }
        if (methodName.startsWith("update")) {
            String rest = extractRest(methodName, "update");
            return "更新" + rest;
        }
        if (methodName.startsWith("validate")) {
            String rest = extractRest(methodName, "validate");
            return "校验" + rest;
        }
        if (methodName.startsWith("process")) {
            String rest = extractRest(methodName, "process");
            return "处理" + rest;
        }
        if (methodName.startsWith("send")) {
            String rest = extractRest(methodName, "send");
            return "发送" + rest;
        }
        if (methodName.startsWith("save")) {
            String rest = extractRest(methodName, "save");
            return "保存" + rest;
        }
        if (methodName.startsWith("load")) {
            String rest = extractRest(methodName, "load");
            return "加载" + rest;
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            String rest = extractRest(methodName, "is");
            return "是否" + rest;
        }
        if (methodName.startsWith("has") && methodName.length() > 3) {
            String rest = extractRest(methodName, "has");
            return "是否包含" + rest;
        }
        if (methodName.startsWith("init")) {
            String rest = extractRest(methodName, "init");
            return "初始化" + rest;
        }
        if (methodName.startsWith("execute")) {
            String rest = extractRest(methodName, "execute");
            if (rest.isEmpty()) {
                return "执行";
            }
            return "执行" + rest;
        }
        if (methodName.startsWith("handle")) {
            String rest = extractRest(methodName, "handle");
            return "处理" + rest;
        }
        if (methodName.startsWith("check")) {
            String rest = extractRest(methodName, "check");
            return "检查" + rest;
        }
        if (methodName.startsWith("convert")) {
            String rest = extractRest(methodName, "convert");
            return "转换" + rest;
        }
        if (methodName.startsWith("parse")) {
            String rest = extractRest(methodName, "parse");
            return "解析" + rest;
        }
        if (methodName.startsWith("format")) {
            String rest = extractRest(methodName, "format");
            return "格式化" + rest;
        }
        if (methodName.startsWith("build")) {
            String rest = extractRest(methodName, "build");
            return "构建" + rest;
        }
        if (methodName.startsWith("generate")) {
            String rest = extractRest(methodName, "generate");
            return "生成" + rest;
        }

        // 如果没有匹配的前缀，返回原方法名
        return methodName;
    }

    /**
     * 提取方法名前缀后的剩余部分并进行驼峰转中文处理
     */
    private static String extractRest(String methodName, String prefix) {
        if (methodName.length() <= prefix.length()) {
            return "";
        }
        String rest = methodName.substring(prefix.length());
        // 如果首字母大写，转为小写描述
        if (rest.isEmpty()) {
            return "";
        }
        // 简单处理：保持原有形式，不做驼峰分割
        // 可以根据需要进一步优化
        return rest;
    }
}
