// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainContext.java
package com.huawei.hisi.ram.phase2v2.model;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;

import java.util.List;

/**
 * 单条链路的上下文数据，由 Orchestrator 创建并传递给 ChainAnalysisAgent。
 */
public record ChainContext(
    /** 链路唯一标识 */
    String chainId,

    /** 链路名称 (如: "订单创建链路") */
    String chainName,

    /** 链路入口点 */
    Entry entryPoint,

    /** 用户原始问题 */
    String question,

    /** 项目路径 */
    String projectPath,

    /** 父 session ID (Phase1) */
    String parentSessionId,

    /** 链路复杂度 */
    ChainComplexity complexity,

    /** 允许的工具集 */
    List<String> allowedTools,

    /** Phase1 继承的宏观数据 (可选) */
    Phase1InheritedData inheritedData
) {
    /**
     * Phase1 继承的数据 (entryPoints, bridgeStats, coreMethods)。
     */
    public record Phase1InheritedData(
        List<Entry> entryPoints,
        long totalBridges,
        long feignCount,
        long mqCount,
        List<String> coreMethodNodeIds
    ) {}
}