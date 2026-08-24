package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * 前端 API 调用点节点实体 (Neo4j)
 * 表示前端代码中对后端接口的一次 HTTP 调用（axios request / fetch）。
 *
 * <p>由 {@code FrontendAstParser} 扫描前端源码提取，作为「前端组件 → 后端接口」
 * 跨层关系的中间节点：{@code Component -[:INVOKES]-> ApiClient -[:INVOKES_API]-> EntryPoint}。</p>
 *
 * <p>apiClientId = projectPath + ":" + sourceFile + ":" + method + " " + url，
 * 保证同一文件内同一 URL+方法 的调用点唯一。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("ApiClient")
public class ApiClientNode {

    /**
     * API 调用点唯一标识
     */
    @Id
    @Property("apiClientId")
    private String apiClientId;

    /**
     * HTTP 方法: GET/POST/PUT/DELETE/PATCH
     */
    @Property("method")
    private String method;

    /**
     * URL（相对 baseURL 的路径，如 /v2/knowledge-graph/dashboard）
     */
    @Property("url")
    private String url;

    /**
     * 发起调用的源文件路径
     */
    @Property("sourceFile")
    private String sourceFile;

    /**
     * 发起调用的组件名（组件内直调时有值；api/*.ts 封装层调用时为空）
     */
    @Property("componentName")
    private String componentName;

    /**
     * 所属前端项目路径
     */
    @Property("projectPath")
    private String projectPath;

    /**
     * 编程语言: typescript/javascript
     */
    @Property("language")
    private String language;

    /**
     * 生成唯一的 API 调用点 ID
     */
    public static String generateApiClientId(String projectPath, String sourceFile, String method, String url) {
        return projectPath + ":" + sourceFile + ":" + method + " " + url;
    }
}
