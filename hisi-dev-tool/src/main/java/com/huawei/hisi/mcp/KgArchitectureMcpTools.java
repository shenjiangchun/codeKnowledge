package com.huawei.hisi.mcp;

import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphV2Controller;
import com.huawei.hisi.mcp.KgBridgeMcpTools.Language;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 进程内 MCP 的知识图谱架构类工具（工具名与入参沿用 Node 版 hisi-mcp-server，对模型一侧透明）：
 * kg_dashboard / kg_dsm / kg_hotspots / kg_domains / kg_service_topology / kg_blast_radius
 * 以及不参与会话范围的 kg_api_consumers / kg_backend_deps。
 *
 * <p>Node 版这些工具都是转调 V2 的 REST 接口（/api/v2/knowledge-graph/...），查询串里只带
 * {@code projectPaths}（逗号拼接）和 {@code language}，V2 再以 projectPath = null 转给 V1 控制器。
 * 这里直接调同一个控制器方法，传参与那条 REST 请求到达控制器时的值一致；
 * 接口上由 @RequestParam(defaultValue = ...) 给的默认值在进程内调用时不会生效，所以在工具里按同样的值补齐。</p>
 *
 * <p>前 6 个工具在 Node 版是“严格范围”（scopeManager：scoped = true、strict = true）：
 * 既没有显式传 projectPaths / projectPath、会话也没设范围时，回 SCOPE_NOT_SET，不查后端。</p>
 *
 * <p>入参 schema 由框架按 Java 形参类型生成、再盖上 {@code @McpToolParam} 的 description / required：
 * 合同里带 enum 的入参（kg_blast_radius 的 {@code language}）用枚举类型声明，schema 里才会有同样的 enum。</p>
 */
@Component
public class KgArchitectureMcpTools {

    /** /dsm 接口上 level 的默认值（V1、V2 都是 @RequestParam(defaultValue = "package")）。 */
    private static final String DEFAULT_DSM_LEVEL = "package";

    /** /hotspots 接口上 limit 的默认值（V1、V2 都是 @RequestParam(defaultValue = "20")）。 */
    private static final int DEFAULT_HOTSPOTS_LIMIT = 20;

    /** /blast-radius/{nodeId} 接口上 maxDepth 的默认值（V1、V2 都是 @RequestParam(defaultValue = "5")）。 */
    private static final int DEFAULT_BLAST_RADIUS_MAX_DEPTH = 5;

    private final McpToolSupport support;
    private final KnowledgeGraphController knowledgeGraph;
    private final KnowledgeGraphV2Controller knowledgeGraphV2;

    public KgArchitectureMcpTools(McpToolSupport support,
                                  KnowledgeGraphController knowledgeGraph,
                                  KnowledgeGraphV2Controller knowledgeGraphV2) {
        this.support = support;
        this.knowledgeGraph = knowledgeGraph;
        this.knowledgeGraphV2 = knowledgeGraphV2;
    }

    @McpTool(name = "kg_dashboard",
            description = "获取项目架构仪表盘聚合数据：模块列表、依赖关系、KPI摘要、风险列表。一次性获取项目全景视图，适合快速了解项目结构和技术债。")
    public Object kgDashboard(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤: java | python (可选)", required = false) String language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_dashboard");
        }
        // GET /api/v2/knowledge-graph/dashboard
        return McpToolSupport.envelope("kg_dashboard",
                knowledgeGraph.getDashboard(null, projectPathsOf(scope), queryText(language)));
    }

    @McpTool(name = "kg_dsm",
            description = "获取 DSM 依赖结构矩阵，返回 N×N 模块依赖关系和循环依赖/分层违规标记。用于依赖分析和架构治理，可视化模块间的耦合关系。")
    public Object kgDsm(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "语言过滤 (可选)", required = false) String language,
            @McpToolParam(description = "粒度: package (默认) | build-module", required = false) String level) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_dsm");
        }
        // GET /api/v2/knowledge-graph/dsm —— Node 版只在 level 有值时才传，接口对“没传 / 空串”都取默认 package
        String dsmLevel = queryText(level) == null ? DEFAULT_DSM_LEVEL : level;
        return McpToolSupport.envelope("kg_dsm",
                knowledgeGraph.getDsm(null, projectPathsOf(scope), queryText(language), dsmLevel));
    }

    @McpTool(name = "kg_hotspots",
            description = "获取项目热点文件列表，按风险分降序排列（复杂度×变更频率×耦合度）。用于识别需要重构的高风险代码区域和测试优先级排序。")
    public Object kgHotspots(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "语言过滤 (可选)", required = false) String language,
            @McpToolParam(description = "返回数量上限 (默认 20)", required = false) Integer limit) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_hotspots");
        }
        // GET /api/v2/knowledge-graph/hotspots —— Node 版是 if (params.limit)：不传或传 0 都走接口默认值 20
        int hotspotLimit = limit == null || limit == 0 ? DEFAULT_HOTSPOTS_LIMIT : limit;
        return McpToolSupport.envelope("kg_hotspots",
                knowledgeGraph.getHotspots(null, projectPathsOf(scope), queryText(language), hotspotLimit));
    }

    @McpTool(name = "kg_domains",
            description = "获取自动检测的领域/DDD边界及其跨域交互关系。返回项目的领域划分、领域间依赖和违规标记。用于理解模块化结构和架构边界。")
    public Object kgDomains(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "语言过滤 (可选)", required = false) String language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_domains");
        }
        // GET /api/v2/knowledge-graph/domains
        return McpToolSupport.envelope("kg_domains",
                knowledgeGraph.getDomains(null, projectPathsOf(scope), queryText(language)));
    }

    @McpTool(name = "kg_service_topology",
            description = "获取微服务拓扑图：服务节点、跨服务依赖边（Feign/MQ/HTTP）、调用频率")
    public Object kgServiceTopology(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "语言过滤 (可选)", required = false) String language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_service_topology");
        }
        // GET /api/v2/knowledge-graph/service-topology
        return McpToolSupport.envelope("kg_service_topology",
                knowledgeGraph.getServiceTopology(null, projectPathsOf(scope), queryText(language)));
    }

    @McpTool(name = "kg_blast_radius",
            description = "计算指定方法的爆炸半径：下游影响面、上游调用者、受影响的API入口点、风险摘要。用于评估代码变更的影响范围和安全风险评估。需要 nodeId。")
    public Object kgBlastRadius(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "方法节点ID", required = true) String nodeId,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language,
            @McpToolParam(description = "遍历深度 (默认 5)", required = false) Integer maxDepth) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_blast_radius");
        }
        if (isBlank(nodeId)) {
            return McpToolSupport.error("kg_blast_radius", "nodeId 不能为空");
        }
        // GET /api/v2/knowledge-graph/blast-radius/{nodeId} —— Node 版是 if (params.maxDepth)：不传或传 0 都走接口默认值 5；
        // 这个接口不接收 language
        int depth = maxDepth == null || maxDepth == 0 ? DEFAULT_BLAST_RADIUS_MAX_DEPTH : maxDepth;
        return McpToolSupport.envelope("kg_blast_radius",
                knowledgeGraph.getBlastRadius(nodeId, depth, null, projectPathsOf(scope)));
    }

    @McpTool(name = "kg_api_consumers",
            description = "查某后端接口（EntryPoint）的前端调用方列表。沿 INVOKES_API 边反向，返回调用该接口的前端 ApiClient（url/sourceFile/componentName）。用于回答「改这个后端接口会影响哪些前端组件」。需要 entryId。")
    public Object kgApiConsumers(
            @McpToolParam(description = "后端入口点 ID（EntryPoint.entryId）", required = true) String entryId) {
        if (isBlank(entryId)) {
            return McpToolSupport.error("kg_api_consumers", "entryId 不能为空");
        }
        // GET /api/v2/knowledge-graph/api-consumers —— V2 自己实现的查询，不参与会话范围
        return McpToolSupport.envelope("kg_api_consumers", knowledgeGraphV2.getApiConsumers(entryId));
    }

    @McpTool(name = "kg_backend_deps",
            description = "查某前端 ApiClient 调用的后端接口列表。沿 INVOKES_API 边正向，返回该前端调用点命中的后端 EntryPoint（entryId/entryKey/entryType）。用于回答「这个前端组件调用了哪些后端接口」。需要 apiClientId。")
    public Object kgBackendDeps(
            @McpToolParam(description = "前端 API 调用点 ID（ApiClient.apiClientId）", required = true) String apiClientId) {
        if (isBlank(apiClientId)) {
            return McpToolSupport.error("kg_backend_deps", "apiClientId 不能为空");
        }
        // GET /api/v2/knowledge-graph/backend-deps —— V2 自己实现的查询，不参与会话范围
        return McpToolSupport.envelope("kg_backend_deps", knowledgeGraphV2.getBackendDeps(apiClientId));
    }

    // ── 与 Node 版对齐的取值规则 ──

    /**
     * Node 版 resolveProjectParams + buildQueryParams 的等价规则：
     * 显式传了 projectPaths 就用它；否则只传了 projectPath 时凑成一项；都没有就是 null（查询串里不带，原样交给控制器）。
     */
    private static List<String> projectPathsOf(McpToolSupport.BothScope scope) {
        List<String> paths = scope.projectPaths();
        if (paths != null && !paths.isEmpty()) {
            return paths;
        }
        String single = scope.projectPath();
        return single == null || single.isBlank() ? null : List.of(single);
    }

    /** 可选的字符串查询参数：Node 版只在有值时才往查询串里放，所以空串等价于“没传”（接口收到 null）。 */
    private static String queryText(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
