package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 调用链图响应DTO
 * 用于DAG图展示，包含节点、边和环信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallChainGraphResponse {
    /**
     * 入口点节点ID
     */
    private String entryId;

    /**
     * 入口类型
     */
    private String entryType;

    /**
     * 入口标识
     */
    private String entryKey;

    /**
     * 最大深度
     */
    private Integer maxDepth;

    /**
     * 节点总数
     */
    private Integer totalNodes;

    /**
     * DAG图节点列表
     */
    private List<GraphNode> nodes;

    /**
     * DAG图边列表
     */
    private List<GraphEdge> edges;

    /**
     * 检测到的环
     */
    private List<CallCycleInfo> cycles;

    /**
     * 环数量
     */
    private Integer cycleCount;

    /**
     * 在环中的节点ID集合
     */
    private Set<String> nodesInCycle;
}
