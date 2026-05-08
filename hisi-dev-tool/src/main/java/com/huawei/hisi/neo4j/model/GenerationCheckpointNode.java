package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.time.Instant;

/**
 * 生成检查点节点 (Neo4j)
 * 记录每次知识图谱/向量生成的检查点信息，用于增量刷新 V2。
 * 通过 projectPath 定位唯一检查点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("GenerationCheckpoint")
public class GenerationCheckpointNode {

    /**
     * 检查点唯一标识（自动生成 UUID）
     */
    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    @Property("checkpointId")
    private String checkpointId;

    /**
     * 项目路径（本地绝对路径）
     */
    @Property("projectPath")
    private String projectPath;

    /**
     * 最后一次提交的 commit hash
     */
    @Property("lastCommit")
    private String lastCommit;

    /**
     * 最后一次提交所在分支
     */
    @Property("lastBranch")
    private String lastBranch;

    /**
     * 生成时间
     */
    @Property("generatedAt")
    private Instant generatedAt;

}
