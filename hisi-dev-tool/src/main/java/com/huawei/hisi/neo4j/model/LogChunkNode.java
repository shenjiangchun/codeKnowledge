package com.huawei.hisi.neo4j.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.List;

/**
 * 日志块节点实体 (Neo4j)
 * 用于存储日志的向量表示，支持相似度检索
 *
 * Task 5: LogChunk node for log root cause analysis
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("LogChunk")
public class LogChunkNode {

    /**
     * 节点唯一标识 (fingerprint-based)
     */
    @Id
    @Property("nodeId")
    private String nodeId;

    /**
     * 错误类型 (e.g., NullPointerException)
     */
    @Property("errorType")
    private String errorType;

    /**
     * 错误消息摘要
     */
    @Property("message")
    private String message;

    /**
     * 堆栈跟踪
     */
    @Property("stackTrace")
    private String stackTrace;

    /**
     * 错误指纹 (MD5 hash for dedup)
     */
    @Property("fingerprint")
    private String fingerprint;

    /**
     * 向量嵌入 (2048 dimensions from embedding-3)
     */
    @Property("embedding")
    private List<Double> embedding;

    /**
     * 项目路径
     */
    @Property("projectPath")
    private String projectPath;

    /**
     * 关联的SQLite reportId
     */
    @Property("reportId")
    private Long reportId;

    /**
     * 创建时间
     */
    @Property("createdAt")
    private String createdAt;
}