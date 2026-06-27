// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainReport.java
package com.huawei.hisi.ram.phase2v2.model;

import java.util.List;
import java.util.Map;

/**
 * 单条链路的完整分析报告，由 ChainAnalysisAgent 产出。
 */
public record ChainReport(
    /** 链路唯一标识 */
    String chainId,

    /** 链路名称 */
    String chainName,

    /** 入口点信息 */
    EntryPointInfo entryPoint,

    /** 分析结果 */
    AnalysisResult analysis,

    /** KG 原始数据 (供 Orchestrator 合并) */
    KgRawData kgData,

    /** 执行状态 */
    String status,

    /** 错误信息 (如有) */
    String error
) {
    public record EntryPointInfo(
        String type,
        String className,
        String methodName,
        String nodeId
    ) {}

    public record AnalysisResult(
        /** 摘要 (≤100 字) */
        String summary,

        /** 调用链流程图 SVG */
        String callChainFlowSvg,

        /** 时序图 SVG */
        String sequenceDiagramSvg,

        /** 状态流转图 SVG (如适用) */
        String stateDiagramSvg,

        /** 代码片段列表 */
        List<CodeSnippet> codeSnippets,

        /** 建议列表 */
        List<Recommendation> recommendations,

        /** 置信度评估 */
        Confidence confidence
    ) {}

    public record CodeSnippet(
        String nodeId,
        String className,
        String methodName,
        String filePath,
        String snippet,
        String relevance
    ) {}

    public record Recommendation(
        int sequence,
        String action,
        String target,
        String reason
    ) {}

    public record Confidence(
        String level,
        KgCoverage kgCoverage,
        List<String> limitations
    ) {}

    public record KgCoverage(
        boolean upstreamComplete,
        boolean downstreamComplete,
        int codeBodiesLoaded,
        List<String> missingInfo
    ) {}

    public record KgRawData(
        List<Map<String, Object>> upstreamChains,
        List<Map<String, Object>> downstreamChains,
        List<Map<String, Object>> methodBodies,
        List<Map<String, Object>> bridgePoints
    ) {}
}