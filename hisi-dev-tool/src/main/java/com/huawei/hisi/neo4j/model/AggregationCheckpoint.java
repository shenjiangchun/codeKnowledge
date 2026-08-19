package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * 聚合 Stage 独立 checkpoint 节点
 * 每个 Stage 有自己的 checkpoint，某 Stage 失败不影响其他 Stage 的结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("AggregationCheckpoint")
public class AggregationCheckpoint {

    @Id
    @Property("checkpointId")
    private String checkpointId;  // projectPath + ":" + stageName (自动生成)

    @Property("projectPath")
    private String projectPath;

    @Property("stageName")
    private String stageName;  // "ModuleStats" | "DSM" | "Hotspot" | "Churn" | "Community" | "DomainName"

    @Property("status")
    private String status;  // "SUCCESS" | "FAILED" | "RUNNING"

    @Property("lastSuccessAt")
    private String lastSuccessAt;  // ISO datetime

    @Property("errorMessage")
    private String errorMessage;

    @Property("dataHash")
    private String dataHash;  // 用于判断是否需要重算
}
