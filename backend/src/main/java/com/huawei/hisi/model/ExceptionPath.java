package com.huawei.hisi.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常传播路径实体
 * 用于根因分析
 */
@Data
@Builder
public class ExceptionPath {
    /** 路径ID (UUID) */
    private String id;

    /** 异常类型 */
    private String exceptionType;

    /** 源方法节点ID */
    private String sourceMethodId;

    /** 传播路径（JSON数组字符串，包含方法ID列表） */
    private String propagationPath;

    /** 可能性评分 (0-1) */
    private Float likelihood;

    /** 原因模式: NPE_RISK/RESOURCE_LEAK/CONCURRENCY_ISSUE 等 */
    private String causePattern;

    /** 异常路径描述 */
    private String description;

    /** 项目目录（数据隔离） */
    private String projectDir;

    /** 创建时间 */
    private LocalDateTime createdAt;
}