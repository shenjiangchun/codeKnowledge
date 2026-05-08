package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 图边模型
 * 表示方法之间的调用关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {
    /**
     * 源节点ID（调用方）
     */
    private String source;

    /**
     * 目标节点ID（被调用方）
     */
    private String target;

    /**
     * 调用类型: DIRECT/STATIC/INTERFACE/VIRTUAL/LAMBDA/CONSTRUCTOR
     */
    private String callType;

    /**
     * 调用行号
     */
    private Integer callLine;

    /**
     * 是否是环边（构成环的边）
     */
    private Boolean isCycleEdge;
}
