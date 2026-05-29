package com.huawei.hisi.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码节点实体
 * 存储代码元素的语义信息（类、方法、字段等）
 */
@Data
@Builder
public class CodeNode {
    /** 节点ID (UUID) */
    private String id;

    /** 节点类型: CLASS/METHOD/FIELD/EXCEPTION/ANNOTATION */
    private NodeType type;

    /** 简单名称 */
    private String name;

    /** 全限定名 */
    private String fullName;

    /** 方法签名（仅方法节点） */
    private String signature;

    /** 源代码内容 */
    private String sourceCode;

    /** LLM分析的意图/用途 */
    private String intent;

    /** 简要摘要 */
    private String summary;

    /** 语义嵌入向量（JSON数组字符串） */
    private String embedding;

    /** 源文件路径 */
    private String filePath;

    /** 起始行号 */
    private Integer lineStart;

    /** 结束行号 */
    private Integer lineEnd;

    /** 包名 */
    private String packageName;

    /** 所属类名 */
    private String className;

    /** 修饰符列表（JSON数组字符串） */
    private String modifiers;

    /** 注解列表（JSON数组字符串） */
    private String annotations;

    /** 方法参数（JSON数组字符串） */
    private String parameters;

    /** 返回类型 */
    private String returnType;

    /** 声明异常列表（JSON数组字符串） */
    private String exceptions;

    /** 元数据（JSON对象字符串） */
    private String metadata;

    /** 项目目录（数据隔离） */
    private String projectDir;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}