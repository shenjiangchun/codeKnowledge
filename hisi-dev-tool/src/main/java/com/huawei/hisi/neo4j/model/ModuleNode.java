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
 * 模块聚合节点（包/构建模块级别的聚合统计）
 * 通过 {@code DEPENDS_ON} 关系连接其他 ModuleNode，通过 {@code CONTAINS} 关系关联 MethodNode
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("ModuleNode")
public class ModuleNode {

    @Id
    @Property("moduleId")
    private String moduleId;  // projectPath + ":" + moduleName (或直接使用 packageName)

    @Property("moduleName")
    private String moduleName;

    @Property("level")
    private String level;  // "build-module" | "package"

    @Property("methodCount")
    private Integer methodCount;

    @Property("classCount")
    private Integer classCount;

    @Property("entryPointCount")
    private Integer entryPointCount;

    @Property("avgComplexity")
    private Double avgComplexity;

    @Property("inDegree")
    private Integer inDegree;

    @Property("outDegree")
    private Integer outDegree;

    @Property("instability")
    private Double instability;  // outDegree / (inDegree + outDegree)

    @Property("layerRole")
    private String layerRole;  // CONTROLLER | SERVICE | REPOSITORY | MAPPER | DATA | UTILITY | UNKNOWN

    @Property("projectPath")
    private String projectPath;

    @Property("language")
    private String language;

    /**
     * Spring 注解角色统计，JSON 格式
     * 如 {"@RestController":3, "@Service":12}
     */
    @Property("springRoles")
    private String springRoles;

    // ==================== build-module 级专用字段 ====================

    /** Maven groupId（build-module 级才有） */
    @Property("groupId")
    private String groupId;

    /** Maven artifactId（build-module 级才有） */
    @Property("artifactId")
    private String artifactId;

    /** Maven version（build-module 级才有） */
    @Property("version")
    private String version;

    /** 一跳依赖坐标列表（元素为 groupId:artifactId:version），仅 build-module 级使用 */
    @Property("dependencyCoordinates")
    private List<String> dependencyCoordinates;
}
