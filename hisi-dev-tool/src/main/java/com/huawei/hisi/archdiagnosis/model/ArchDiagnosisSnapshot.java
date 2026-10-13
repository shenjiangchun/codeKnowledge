package com.huawei.hisi.archdiagnosis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 架构诊断快照（SQLite 本地元数据）。
 *
 * <p>报告端点成功后自动落库：复选 N 个项目时落 N 条，每条 {@code scope} = 单个 projectPath。
 * {@code reportJson} 存全量报告体，供历史导出还原；历史趋势查询不回传该字段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchDiagnosisSnapshot {

    private Long id;
    private String scope;
    private Integer healthScore;
    private String grade;
    private Integer highCount;
    private Integer mediumCount;
    private Integer lowCount;
    private String reportJson;
    private String createdAt;
}