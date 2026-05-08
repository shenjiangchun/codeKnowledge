package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 图节点模型
 * 用于DAG图展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {
    /**
     * 节点ID（方法节点ID）
     */
    private String id;

    /**
     * 节点名称（方法名）
     */
    private String name;

    /**
     * 类名
     */
    private String className;

    /**
     * 调用深度
     */
    private Integer depth;

    /**
     * 是否在环中
     */
    private Boolean inCycle;

    /**
     * 调用类型
     */
    private String callType;

    /**
     * 方法签名
     */
    private String signature;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 起始行号
     */
    private Integer startLine;

    /**
     * LLM 生成的自然语言描述（可为 null，表示尚未生成）
     */
    private String description;
}
