package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.List;

/**
 * 入口点节点实体 (Neo4j)
 * 表示代码的入口点，如HTTP接口、定时任务、消息消费者等
 *
 * 注意：入口点与方法的关系通过 methodNodeId 字段关联，
 * 而非 Neo4j 关系，以避免框架自动加载不存在的关系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("EntryPoint")
public class EntryPointNode {

    /**
     * 入口点唯一标识
     */
    @Id
    @Property("entryId")
    private String entryId;

    /**
     * 入口类型: HTTP/SCHEDULED/MQ_CONSUMER/GRPC/RMI
     */
    @Property("entryType")
    private String entryType;

    /**
     * 入口标识 (URI/cron表达式/队列名等)
     */
    @Property("entryKey")
    private String entryKey;

    /**
     * 入口详细信息 (JSON格式)
     */
    @Property("entryInfo")
    private String entryInfo;

    /**
     * 所属项目路径
     */
    @Property("projectPath")
    private String projectPath;

    /**
     * 编程语言: java/python/...
     */
    @Property("language")
    private String language;

    /**
     * 框架: spring/fastapi/django/...
     */
    @Property("framework")
    private String framework;

    /**
     * 所属服务名
     */
    @Property("serviceName")
    private String serviceName;

    /**
     * 关联的方法节点ID
     * 通过字段存储而非Neo4j关系，避免框架自动加载
     */
    @Property("methodNodeId")
    private String methodNodeId;

    /**
     * 简要描述（30字以内，一句话概括入口核心功能）
     */
    @Property("briefDescription")
    private String briefDescription;

    /**
     * 详细描述（100-200字，包含业务场景、处理流程、数据流转）
     */
    @Property("detailedDescription")
    private String detailedDescription;

    /**
     * 简要描述向量（用于语义检索）
     */
    @Property("briefEmbedding")
    private List<Double> briefEmbedding;

    /**
     * 详细描述向量（用于语义检索）
     */
    @Property("detailedEmbedding")
    private List<Double> detailedEmbedding;

    /**
     * 入口类型常量
     */
    public static final String TYPE_HTTP = "HTTP";
    public static final String TYPE_SCHEDULED = "SCHEDULED";
    public static final String TYPE_MQ_CONSUMER = "MQ_CONSUMER";
    public static final String TYPE_GRPC = "GRPC";
    public static final String TYPE_RMI = "RMI";
    public static final String TYPE_FASTAPI_ROUTE = "FASTAPI_ROUTE";
    public static final String TYPE_FLASK_ROUTE = "FLASK_ROUTE";
    public static final String TYPE_DJANGO_VIEW = "DJANGO_VIEW";
    public static final String TYPE_CELERY_TASK = "CELERY_TASK";
    public static final String TYPE_FEIGN_CLIENT = "FEIGN_CLIENT";
    public static final String TYPE_MAIN = "MAIN";
}