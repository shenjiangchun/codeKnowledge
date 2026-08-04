package com.huawei.hisi.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码关系实体
 * 存储代码节点之间的关系
 */
@Data
@Builder
public class CodeRelation {
    /** 关系ID (UUID) */
    private String id;

    /** 源节点ID */
    private String sourceId;

    /** 目标节点ID */
    private String targetId;

    /** 关系类型: CALLS/IMPLEMENTS/EXTENDS/THROWS/USES/DEPENDS_ON/OVERRIDES */
    private RelationType type;

    /** 关系权重/强度 (0-1) */
    private Float weight;

    /** 关系发生的行号 */
    private Integer lineNumber;

    /** 关系上下文代码片段 */
    private String context;

    /** 元数据（JSON对象字符串） */
    private String metadata;

    /** 项目目录（数据隔离） */
    private String projectDir;

    /** 创建时间 */
    private LocalDateTime createdAt;
}