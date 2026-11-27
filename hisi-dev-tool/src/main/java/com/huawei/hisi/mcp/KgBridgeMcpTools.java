package com.huawei.hisi.mcp;

import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 进程内 MCP 的知识图谱桥接类工具（工具名与入参沿用 Node 版 hisi-mcp-server，对模型一侧透明）：
 * kg_bridges / kg_bridge_stats / kg_implementations / kg_mybatis_sql / kg_feign_chain / kg_mq_chain。
 *
 * <p>Node 版这 6 个工具都是转调 V2 的 REST 接口（/api/v2/knowledge-graph/...），查询串里只带
 * {@code projectPaths}（逗号拼接）和 {@code language}，V2 再以 projectPath = null 转给 V1 控制器。
 * 这里直接调同一个 V1 控制器方法，传参与那条 REST 请求到达控制器时的值一致。</p>
 *
 * <p>这 6 个工具在 Node 版是“软范围”（scopeManager：scoped = true、strict = false）：
 * 没有显式传 projectPaths / projectPath 时用会话范围；两者都没有也不在这里拦，把 null 原样交给控制器，
 * 由控制器自己的参数校验回错误。</p>
 *
 * <p>{@code language} 在入参里有声明（合同如此），但这几个 REST 接口都不接收它，所以收下后不往下传。</p>
 *
 * <p>入参 schema 由框架按 Java 形参类型生成、再盖上 {@code @McpToolParam} 的 description / required：
 * 合同里带 enum 的入参（{@code language}、{@code statementType}）用枚举类型声明，schema 里才会有同样的 enum。</p>
 */
@Component
public class KgBridgeMcpTools {

    /** feign / mq 调用链接口上 maxDepth 的默认值（V1、V2 都是 @RequestParam(defaultValue = "10")，Node 版不传）。 */
    private static final int FEIGN_MQ_DEFAULT_MAX_DEPTH = 10;

    /** 合同里 language 的取值；形参用它声明，入参 schema 才带 enum。框架按名字转换，取值不在枚举里会直接报错。 */
    public enum Language {
        java, python, typescript
    }

    /** 合同里 statementType 的取值；后端按大小写敏感精确匹配，常量名就是发给接口的值。 */
    public enum StatementType {
        SELECT, INSERT, UPDATE, DELETE
    }

    private final McpToolSupport support;
    private final KnowledgeGraphController knowledgeGraph;

    public KgBridgeMcpTools(McpToolSupport support, KnowledgeGraphController knowledgeGraph) {
        this.support = support;
        this.knowledgeGraph = knowledgeGraph;
    }

    @McpTool(name = "kg_bridges",
            description = "获取节点的桥接点，找出连接不同模块或服务的关键节点（跨包/跨模块调用）。定位架构中的耦合点，帮助评估重构风险。需要 nodeId。")
    public Object kgBridges(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "节点ID", required = true) String nodeId,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, false);
        if (isBlank(nodeId)) {
            return McpToolSupport.error("kg_bridges", "nodeId 不能为空");
        }
        if (!scope.isSet()) {
            // Node 版走的 v2 接口把 projectPaths 列为必填，没传会 400；v1 的这个方法不校验项目、会按 nodeId 全局查，
            // 所以这里自己拦住，保持与原来一致
            return McpToolSupport.error("kg_bridges", "缺少必需参数: projectPaths");
        }
        // GET /api/v2/knowledge-graph/call-chain/{nodeId}/bridges
        return McpToolSupport.envelope("kg_bridges",
                knowledgeGraph.getMethodBridges(nodeId, null, projectPathsOf(scope)));
    }

    @McpTool(name = "kg_bridge_stats",
            description = "获取项目的桥接点统计信息，包括Feign、MQ、HTTP、JPA、MAPPER 等跨服务/跨层调用统计。返回各类型桥接点数量和占比，用于架构健康度评估。")
    public Object kgBridgeStats(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, false);
        // GET /api/v2/knowledge-graph/bridge-stats
        return McpToolSupport.envelope("kg_bridge_stats",
                knowledgeGraph.getBridgeStats(null, projectPathsOf(scope)));
    }

    @McpTool(name = "kg_implementations",
            description = "获取接口的所有实现类。输入接口全限定名，返回所有 implements 该接口的类名列表。用于多态分析和架构理解。")
    public Object kgImplementations(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "接口名（全限定名）", required = true) String interfaceName,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, false);
        if (isBlank(interfaceName)) {
            return McpToolSupport.error("kg_implementations", "interfaceName 不能为空");
        }
        // GET /api/v2/knowledge-graph/implementations
        return McpToolSupport.envelope("kg_implementations",
                knowledgeGraph.getImplementations(interfaceName, null, projectPathsOf(scope)));
    }

    @McpTool(name = "kg_mybatis_sql",
            description = "获取MyBatis SQL映射信息，包括 SELECT/INSERT/UPDATE/DELETE 语句及对应的 Mapper 接口。用于理解数据访问层的 SQL 逻辑和表结构。可指定 mapperInterface 过滤。")
    public Object kgMybatisSql(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "Mapper接口名（全限定名），可选，不提供则返回所有", required = false) String mapperInterface,
            @McpToolParam(description = "SQL语句类型过滤，可选", required = false) StatementType statementType,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, false);
        // GET /api/v2/knowledge-graph/mybatis/sql —— mapperInterface 不传或传空串都是“不过滤”，
        // 控制器自己按“非空才生效”判断，所以原样往下传。
        return McpToolSupport.envelope("kg_mybatis_sql",
                knowledgeGraph.getMyBatisSqlList(null, projectPathsOf(scope), mapperInterface,
                        statementType == null ? null : statementType.name()));
    }

    @McpTool(name = "kg_feign_chain",
            description = "获取Feign服务的调用链，追踪微服务间的调用关系。输入 Feign 服务名，返回服务间的 RPC 调用链。适合分析分布式系统的服务依赖。")
    public Object kgFeignChain(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "Feign服务名", required = true) String serviceName,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, false);
        if (isBlank(serviceName)) {
            return McpToolSupport.error("kg_feign_chain", "serviceName 不能为空");
        }
        // GET /api/v2/knowledge-graph/feign/{serviceName}/call-chain —— Node 版不传 maxDepth，走接口默认值 10
        return McpToolSupport.envelope("kg_feign_chain",
                knowledgeGraph.getFeignCallChain(serviceName, null, projectPathsOf(scope), FEIGN_MQ_DEFAULT_MAX_DEPTH));
    }

    @McpTool(name = "kg_mq_chain",
            description = "获取MQ消息的调用链，追踪消息队列的生产者和消费者。输入 MQ topic 名，返回该消息的完整生产和消费链路。适合异步通信架构分析。")
    public Object kgMqChain(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "MQ主题名", required = true) String topic,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, false);
        if (isBlank(topic)) {
            return McpToolSupport.error("kg_mq_chain", "topic 不能为空");
        }
        // GET /api/v2/knowledge-graph/mq/{topic}/call-chain —— Node 版不传 maxDepth，走接口默认值 10
        return McpToolSupport.envelope("kg_mq_chain",
                knowledgeGraph.getMQCallChain(topic, null, projectPathsOf(scope), FEIGN_MQ_DEFAULT_MAX_DEPTH));
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
