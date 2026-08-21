package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * 前端路由节点实体 (Neo4j)
 * 表示前端 vue-router 路由表中的一条路由。
 *
 * <p>由 {@code FrontendAstParser} 扫描 {@code router/index.ts} 提取。
 * 与 codegraph 的 {@code route} kind（HTTP 端点）语义不同，本节点表达的是
 * 前端页面的 URL 路径导航。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("FrontendRoute")
public class FrontendRouteNode {

    /**
     * 路由唯一标识
     */
    @Id
    @Property("frontendRouteId")
    private String frontendRouteId;

    /**
     * 路由路径（如 /apm-debug）
     */
    @Property("path")
    private String path;

    /**
     * 路由名（vue-router name，可选）
     */
    @Property("name")
    private String name;

    /**
     * 目标组件名（从 component 字段提取，如 ApmDebugView）
     */
    @Property("componentName")
    private String componentName;

    /**
     * 所属前端项目路径
     */
    @Property("projectPath")
    private String projectPath;

    /**
     * 生成唯一的路由节点 ID
     */
    public static String generateFrontendRouteId(String projectPath, String path) {
        return projectPath + ":" + path;
    }
}
