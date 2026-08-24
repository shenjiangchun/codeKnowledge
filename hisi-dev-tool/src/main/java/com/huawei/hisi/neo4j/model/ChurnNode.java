package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * 文件变更频率节点（Git 变更历史聚合）
 * 文件粒度，通过 {@code CHURNS_AT} 关系关联 MethodNode
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("ChurnNode")
public class ChurnNode {

    @Id
    @Property("nodeId")
    private String nodeId;  // projectPath + ":" + filePath

    @Property("filePath")
    private String filePath;

    @Property("commitCount90d")
    private Integer commitCount90d;

    @Property("linesChanged90d")
    private Integer linesChanged90d;

    @Property("lastCommitAt")
    private String lastCommitAt;

    @Property("authorCount90d")
    private Integer authorCount90d;

    @Property("projectPath")
    private String projectPath;

    /**
     * 文件级风险分 (0.0-1.0)
     * Hotspot Stage 按文件聚合写入（文件级唯一落点，不写 MethodNode）
     */
    @Property("riskScore")
    private Double riskScore;
}
