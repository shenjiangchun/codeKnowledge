// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/SummaryLayer.java
package com.huawei.hisi.ram.phase2v2.model;

import java.util.List;

/**
 * 第一层：领域概览报告。
 */
public record SummaryLayer(
    /** 领域概览描述 */
    String domainOverview,

    /** 整体流程图 SVG (合并所有链路) */
    String overallFlowDiagramSvg,

    /** 关键发现列表 */
    List<KeyFinding> keyFindings,

    /** 跨链路影响分析 */
    List<CrossChainImpact> crossChainImpacts,

    /** 整体建议 */
    List<String> overallRecommendations
) {
    public record KeyFinding(
        int id,
        String type,
        String description,
        List<String> chains
    ) {}

    public record CrossChainImpact(
        String fromChain,
        String toChain,
        String relation,
        String description
    ) {}
}