package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 调用环信息模型
 * 存储检测到的循环调用路径
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallCycleInfo {
    /**
     * 环唯一标识
     */
    private String cycleId;

    /**
     * 环路径（节点ID列表，首尾相同表示闭环）
     */
    private List<String> cyclePath;

    /**
     * 环起点节点ID
     */
    private String startNodeId;

    /**
     * 环长度（节点数量）
     */
    private Integer cycleLength;
}
