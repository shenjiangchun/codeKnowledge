// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/DetailLayer.java
package com.huawei.hisi.ram.phase2v2.model;

import java.util.List;

/**
 * 第二层：详细链路报告列表。
 */
public record DetailLayer(
    /** 链路报告摘要列表 */
    List<ChainSummary> chains,

    /** 链路总数 */
    int chainCount,

    /** 分析的总方法数 */
    int totalMethodsAnalyzed,

    /** 代码片段总数 */
    int totalCodeSnippets
) {
    public record ChainSummary(
        String chainId,
        String chainName,
        String summary,
        boolean expandable,
        String reportRef
    ) {}
}