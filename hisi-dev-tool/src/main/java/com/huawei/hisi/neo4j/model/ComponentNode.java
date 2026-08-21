package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * 前端组件节点实体 (Neo4j)
 * 表示前端项目中的一个 Vue/React 组件（或页面）。
 *
 * <p>由 codegraph sidecar 的 {@code component} kind 映射而来，替代旧版
 * 「前端 component 抹平为后端 Method」的降级语义，让前端组件成为图谱的一等实体。</p>
 *
 * <p>componentId = projectPath + ":" + componentName，与后端 Method/Class 的稳定标识规则一致。
 * 组件内部的 API 调用点（ApiClient）与组件的关系由
 * {@code Component -[:INVOKES]-> ApiClient} 边表达（关系由 Repository 自定义查询维护，
 * 不在本实体中定义，避免 Spring Data Neo4j 自动加载导致 N+1）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Component")
public class ComponentNode {

    /**
     * 组件唯一标识
     * 格式：projectPath + ":" + componentName
     */
    @Id
    @Property("componentId")
    private String componentId;

    /**
     * 组件名（文件/组件名，如 MyComponent）
     */
    @Property("componentName")
    private String componentName;

    /**
     * 源文件路径
     */
    @Property("filePath")
    private String filePath;

    /**
     * 所属项目路径（前端实际目录）
     */
    @Property("projectPath")
    private String projectPath;

    /**
     * 编程语言: typescript/javascript/...
     */
    @Property("language")
    private String language;

    /**
     * 框架: vue/react/...
     */
    @Property("framework")
    private String framework;

    /**
     * 组件描述（codegraph docstring / 注释）
     */
    @Property("description")
    private String description;

    /**
     * 生成唯一的组件节点 ID
     * @param projectPath 前端项目路径
     * @param componentName 组件名
     * @return 唯一节点 ID
     */
    public static String generateComponentId(String projectPath, String componentName) {
        return projectPath + ":" + componentName;
    }
}
