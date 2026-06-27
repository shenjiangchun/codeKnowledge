// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/Phase2V2Report.java
package com.huawei.hisi.ram.phase2v2.model;

import com.huawei.hisi.ram.phase2v2.model.SummaryLayer;
import com.huawei.hisi.ram.phase2v2.model.DetailLayer;

/**
 * Phase2 V2 分层报告完整结构。
 */
public record Phase2V2Report(
    /** 第一层：概览 */
    SummaryLayer summaryLayer,

    /** 第二层：详细 */
    DetailLayer detailLayer,

    /** 执行状态 */
    String status,

    /** 用户原始问题 */
    String question
) {}