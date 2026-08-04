package com.huawei.hisi.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码嵌入向量实体
 * 存储代码节点的语义向量表示
 */
@Data
@Builder
public class CodeEmbedding {
    /** 嵌入ID (UUID) */
    private String id;

    /** 关联的代码节点ID */
    private String nodeId;

    /** 语义嵌入向量（JSON数组字符串） */
    private String embedding;

    /** 生成嵌入的模型名称 */
    private String embeddingModel;

    /** 向量维度 */
    private Integer dimensions;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}