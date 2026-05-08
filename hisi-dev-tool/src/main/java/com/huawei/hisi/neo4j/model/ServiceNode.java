package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;

/**
 * 服务节点实体 (Neo4j)
 * 表示微服务架构中的一个服务
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Service")
public class ServiceNode {

    /**
     * 服务唯一标识
     */
    @Id
    @Property("serviceId")
    private String serviceId;

    /**
     * 服务名称
     */
    @Property("serviceName")
    private String serviceName;

    /**
     * 服务描述
     */
    @Property("description")
    private String description;

    /**
     * 项目路径
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
     * 技术栈: SPRING_BOOT/SPRING_CLOUD/DUBBO等
     */
    @Property("techStack")
    private String techStack;

    /**
     * 服务版本
     */
    @Property("version")
    private String version;

    /**
     * 服务包含的方法节点
     */
    @Relationship(type = "HAS_METHOD", direction = Relationship.Direction.OUTGOING)
    private List<MethodNode> methods;

    /**
     * 服务包含的入口点
     */
    @Relationship(type = "HAS_ENTRY", direction = Relationship.Direction.OUTGOING)
    private List<EntryPointNode> entryPoints;

    /**
     * 技术栈常量
     */
    public static final String TECH_SPRING_BOOT = "SPRING_BOOT";
    public static final String TECH_SPRING_CLOUD = "SPRING_CLOUD";
    public static final String TECH_DUBBO = "DUBBO";
    public static final String TECH_MICRONAUT = "MICRONAUT";
}