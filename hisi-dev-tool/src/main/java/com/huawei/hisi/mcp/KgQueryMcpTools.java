package com.huawei.hisi.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.controller.SemanticSearchController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 进程内 MCP 的知识图谱查询工具：Node 版 hisi-mcp-server 的 9 个 kg_* 查询工具 + hybrid_search。
 * 工具名、描述、入参名与必填标记逐字沿用 Node 版导出的合同——agent 的提示词、skill 和工具白名单都按它写。
 *
 * <p>每个工具直接调 Node 版所请求的那个 REST 接口对应的 controller 方法，行为与现在一致：</p>
 * <ul>
 *   <li>查询类接口走的是 v2 路径，而 {@code KnowledgeGraphV2Controller} 对它们都是纯转调（projectPath 传 null），
 *       所以这里直接调 v1 的 {@link KnowledgeGraphController}；</li>
 *   <li>项目范围的解析与 Node 版的 injectScope 逐条一致，交给 {@link McpToolSupport}：显式传了 projectPaths 或
 *       projectPath 就按显式的来，否则继承会话范围；两者都没有时本类工具全是严格型，回 SCOPE_NOT_SET；</li>
 *   <li>后端只收得到 projectPaths——Node 版从不把 projectPath 发给接口，只用它拼出单元素列表
 *       （见 {@link #effectivePaths}）；</li>
 *   <li>{@code language} 留在入参里是为了与合同一致，但 v2 的这几个查询接口都没有声明它：Node 版把它拼进 query
 *       string 之后被 Spring 直接忽略，所以这里同样不往后传（只有 hybrid_search 是真用到了它）。</li>
 * </ul>
 *
 * <p>入参 schema 由框架按 Java 形参类型生成，再盖上 {@code @McpToolParam} 的 description / required，所以形参类型
 * 要按合同挑：带 enum 的（language / entryType）用枚举类型声明、常量名就是合同里的取值，schema 里才有同样的 enum
 * （框架按常量名转换入参，取值不在枚举里时整个调用直接回错误；Node 版不校验，会把非法值原样透传）；
 * type=number 的（maxDepth / limit / graphDepth）统一用 {@code Integer}，传小数时同样由框架按错误处理。</p>
 */
@Component
public class KgQueryMcpTools {

    /** 合同里 language 的取值；形参用枚举类型声明，入参 schema 才带 enum。常量名就是合同里的取值。 */
    public enum Language {
        java, python, typescript
    }

    /** 合同里 entryType 的取值；形参用枚举类型声明，入参 schema 才带 enum。常量名就是发给接口的值。 */
    public enum EntryType {
        CONTROLLER, SCHEDULED, MQ_LISTENER, FEIGN_CLIENT, ALL
    }

    /** 合同里 hybrid_search 的 language 取值：只有 java / python（比 kg_* 的 {@link Language} 少一个 typescript）。 */
    public enum SearchLanguage {
        java, python
    }

    private static final Logger log = LoggerFactory.getLogger(KgQueryMcpTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** v2 的 callees-tree 与 call-chain/downstream 接口上 maxDepth 的 defaultValue。 */
    private static final int DEFAULT_MAX_DEPTH = 10;

    /** Node 版 hybrid_search 在入参缺省时发给接口的值（limit ?? 10、graphDepth ?? 0）。 */
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_GRAPH_DEPTH = 0;

    private final McpToolSupport support;
    private final KnowledgeGraphController knowledgeGraphController;
    private final SemanticSearchController semanticSearchController;

    public KgQueryMcpTools(McpToolSupport support,
                           KnowledgeGraphController knowledgeGraphController,
                           SemanticSearchController semanticSearchController) {
        this.support = support;
        this.knowledgeGraphController = knowledgeGraphController;
        this.semanticSearchController = semanticSearchController;
    }

    // ==================== 项目发现 ====================

    @McpTool(name = "kg_list_projects",
            description = "【⚠️必须最先调用】列出知识图谱中所有已建图的项目路径。在调用任何其他 kg_* 或 hybrid_search 工具之前，必须先用此工具获取可用的项目路径列表，然后向用户确认应该在哪些项目里搜索/查询，再用用户选定的路径作为后续工具的 projectPaths 入参。不要自己猜测项目路径（例如把仓库根目录或工作目录直接当 projectPath），那样会查到 0 条结果。")
    public Object kgListProjects() {
        return call("kg_list_projects", knowledgeGraphController::getProjects);
    }

    // ==================== 图谱状态 ====================

    @McpTool(name = "kg_status",
            description = "获取知识图谱服务状态，检查指定项目的服务是否正常运行。返回节点数、关系数、入口点数等统计信息。用于确认项目图谱数据是否就绪。")
    public Object kgStatus(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_status");
        }
        List<String> paths = effectivePaths(scope);
        return call("kg_status", () -> knowledgeGraphController.getStatus(null, paths));
    }

    // ==================== 方法查询 ====================

    @McpTool(name = "kg_method_detail",
            description = "获取方法详细信息，包括参数、返回值、注解、方法体、复杂度、异常声明等。适合在检索到方法后深入了解其实现细节。需要 nodeId（从其他 KG 工具的结果中获取）。")
    public Object kgMethodDetail(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "方法节点ID", required = true) String nodeId,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_method_detail");
        }
        if (nodeId == null) {
            return McpToolSupport.error("kg_method_detail", "缺少必填参数 nodeId");
        }
        List<String> paths = effectivePaths(scope);
        return call("kg_method_detail", () -> knowledgeGraphController.getMethodDetail(nodeId, null, paths));
    }

    @McpTool(name = "kg_method_by_class",
            description = "获取类中的所有方法列表（全限定类名）。返回类内所有方法的签名、参数、注解等信息。用于浏览类的 API 表面。")
    public Object kgMethodByClass(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "类名（全限定名）", required = true) String className,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_method_by_class");
        }
        if (className == null) {
            return McpToolSupport.error("kg_method_by_class", "缺少必填参数 className");
        }
        List<String> paths = effectivePaths(scope);
        return call("kg_method_by_class", () -> knowledgeGraphController.getMethodsByClass(className, null, paths));
    }

    // ==================== 根入口点与下游调用树 ====================

    @McpTool(name = "kg_root_entries",
            description = "查询指定方法的上游信息（合并接口），返回 rootEntries（根入口点：Controller/MQ/Feign/定时任务等）和 directCallers（直接调用方，含 callType/callLine）")
    public Object kgRootEntries(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "类名（全限定名）", required = true) String className,
            @McpToolParam(description = "方法名", required = true) String methodName,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_root_entries");
        }
        if (className == null || methodName == null) {
            return McpToolSupport.error("kg_root_entries", "缺少必填参数 className / methodName");
        }
        List<String> paths = effectivePaths(scope);
        return call("kg_root_entries",
                () -> knowledgeGraphController.getRootEntries(className, methodName, null, paths));
    }

    @McpTool(name = "kg_callees_tree",
            description = "获取方法的完整下游调用树（含深度、循环检测），返回带层级的节点和边。比 kg_downstream 更结构化，适合生成调用链路图。需要 className + methodName。")
    public Object kgCalleesTree(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "类名（全限定名）", required = true) String className,
            @McpToolParam(description = "方法名", required = true) String methodName,
            @McpToolParam(description = "最大追踪深度，默认10", required = false) Integer maxDepth,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_callees_tree");
        }
        if (className == null || methodName == null) {
            return McpToolSupport.error("kg_callees_tree", "缺少必填参数 className / methodName");
        }
        int depth = maxDepth == null ? DEFAULT_MAX_DEPTH : maxDepth;
        List<String> paths = effectivePaths(scope);
        return call("kg_callees_tree",
                () -> knowledgeGraphController.getCalleesTree(className, methodName, null, paths, depth));
    }

    // ==================== 入口点 ====================

    @McpTool(name = "kg_entry_points",
            description = "获取项目的入口点列表，如Controller、定时任务、消息监听器、Feign Client等。可按 entryType 过滤，用于了解项目的对外 API 面和入口架构。")
    public Object kgEntryPoints(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "入口点类型（可选，默认ALL）", required = false) EntryType entryType,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_entry_points");
        }
        // Node 版只在传了且不是 ALL 时才带 entryType；分页固定取第 1 页、每页 20 条（接口默认值，Node 版从不传分页）
        String type = entryType == null || entryType == EntryType.ALL ? null : entryType.name();
        List<String> paths = effectivePaths(scope);
        return call("kg_entry_points",
                () -> knowledgeGraphController.getEntryPoints(null, paths, type, 1, 20));
    }

    // ==================== 调用链 ====================

    @McpTool(name = "kg_downstream",
            description = "获取节点的下游调用链，从指定节点向下游追踪调用关系（BFS）。返回层级化的节点和边，含调用类型（DIRECT/LAMBDA）和循环检测。适合问\"这个方法调了什么\"。需要 nodeId。")
    public Object kgDownstream(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "节点ID", required = true) String nodeId,
            @McpToolParam(description = "最大追踪深度（可选，默认10）", required = false) Integer maxDepth,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_downstream");
        }
        if (nodeId == null) {
            return McpToolSupport.error("kg_downstream", "缺少必填参数 nodeId");
        }
        int depth = maxDepth == null ? DEFAULT_MAX_DEPTH : maxDepth;
        List<String> paths = effectivePaths(scope);
        return call("kg_downstream", () -> knowledgeGraphController.getDownstreamCallChain(nodeId, null, paths, depth));
    }

    @McpTool(name = "kg_affecting",
            description = "获取影响指定方法的上游调用链，找出哪些代码会影响到当前节点。反向追踪调用方，适合问\"改了这方法会影响谁\"。需要 className + methodName。")
    public Object kgAffecting(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "类名（全限定名）", required = true) String className,
            @McpToolParam(description = "方法名", required = true) String methodName,
            @McpToolParam(description = "项目路径列表（必填），支持多项目同时查询", required = true) List<String> projectPaths,
            @McpToolParam(description = "单项目路径（兼容旧调用，优先使用 projectPaths）", required = false) String projectPath,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) Language language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("kg_affecting");
        }
        if (className == null || methodName == null) {
            return McpToolSupport.error("kg_affecting", "缺少必填参数 className / methodName");
        }
        List<String> paths = effectivePaths(scope);
        return call("kg_affecting",
                () -> knowledgeGraphController.getCallChainsAffecting(className, methodName, null, paths));
    }

    // ==================== 混合检索 ====================

    @McpTool(name = "hybrid_search",
            description = "三层混合检索代码方法：关键词过滤 + 向量语义匹配 + 调用链图遍历扩展（RRF融合）。【重要】调用本工具前必须先用 kg_list_projects 列出可选项目并和用户确认 projectPath，不要把仓库根目录当 projectPath，会查到 0 条。使用自然语言描述要找的功能，返回最相关的方法列表及其文件位置。若所选目录存在前后端绑定，检索范围会自动展开到绑定的对侧目录，结果可同时包含后端方法（type=method）与前端节点（type=component/route/apiclient，带 nodeType 字段）。示例查询：「查找处理支付回调的方法」、「用户创建逻辑」、「订单状态流转」")
    public Object hybridSearch(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "自然语言查询，描述要查找的功能或行为", required = true) String query,
            @McpToolParam(description = "项目根目录的绝对路径（与 projectPaths 二选一）", required = false) String projectPath,
            @McpToolParam(description = "项目路径列表，支持多项目同时查询", required = false) List<String> projectPaths,
            @McpToolParam(description = "最多返回结果数量，默认10，建议5~20", required = false) Integer limit,
            @McpToolParam(description = "调用链图遍历深度，默认0。注意：多路召回（v2）路径下固定为0不做图扩展，仅查询退化为单路检索时此参数才生效", required = false) Integer graphDepth,
            @McpToolParam(description = "编程语言过滤（不传则查询全部语言）", required = false) SearchLanguage language) {
        McpToolSupport.BothScope scope = support.resolveBoth(ctx, projectPaths, projectPath, true);
        if (!scope.isSet()) {
            return McpToolSupport.scopeNotSet("hybrid_search");
        }

        // 请求体与 Node 版发给 POST /api/search/semantic/v2 的逐键一致；值为 undefined 的键不进 JSON，这里同样不放
        List<String> bodyPaths = scope.projectPaths() != null ? scope.projectPaths()
                : nonEmpty(scope.projectPath()) ? List.of(scope.projectPath()) : null;
        String requestedProjectPath = nonEmpty(scope.projectPath()) ? scope.projectPath() : firstOf(bodyPaths);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        putIfPresent(body, "projectPath", requestedProjectPath);
        putIfPresent(body, "projectPaths", bodyPaths);
        body.put("limit", limit == null ? DEFAULT_LIMIT : limit);
        body.put("graphDepth", graphDepth == null ? DEFAULT_GRAPH_DEPTH : graphDepth);
        if (language != null) {
            body.put("filters", Map.of("language", language.name()));
        }

        // 必须走 controller：它里面有绑定范围展开与前端结果的合并，绕过去直接调 service 结果会变少
        ResponseEntity<Map<String, Object>> response;
        try {
            response = semanticSearchController.semanticSearchV2(body);
        } catch (Exception e) {
            log.warn("[MCP] hybrid_search 调用后端异常", e);
            return McpToolSupport.error("hybrid_search", e.getMessage());
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            // Node 版遇到非 2xx 会抛 "HTTP <status>: <响应体>"，再由 dispatch 包成工具错误
            return McpToolSupport.error("hybrid_search",
                    "HTTP " + response.getStatusCode().value() + ": " + toJson(response.getBody()));
        }
        Map<String, Object> result = response.getBody();

        Object results = result == null ? null : result.get("results");
        long total = results instanceof List<?> items ? items.size()
                : result != null && result.get("total") instanceof Number number ? number.longValue() : 0;
        if (total != 0) {
            return McpToolSupport.ok(result);
        }
        // 0 条结果时附上可选项目列表，让 agent 请用户重选 projectPath 后重试；取不到列表就原样返回空结果
        try {
            Map<String, Object> merged = result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
            merged.put("_hint",
                    "本次搜索 0 条结果。可能是 projectPath 不在已建图的项目里。"
                            + "请用 availableProjects 中的某一个路径作为 projectPath 重新搜索，"
                            + "或先调用 kg_list_projects 让用户选择。");
            merged.put("availableProjects", knowledgeGraphController.getProjects());
            putIfPresent(merged, "requestedProjectPath", requestedProjectPath);
            return McpToolSupport.ok(merged);
        } catch (Exception e) {
            log.warn("[MCP] hybrid_search 取可选项目列表失败", e);
            return McpToolSupport.ok(result);
        }
    }

    // ==================== 内部工具 ====================

    /**
     * Node 版 resolveProjectParams 的落点：发给接口的 projectPaths 就是调用方（或会话范围）给的 projectPaths，
     * 没有才由 projectPath 包成单元素列表。走到这里时二者必有一个。
     */
    private static List<String> effectivePaths(McpToolSupport.BothScope scope) {
        List<String> paths = scope.projectPaths();
        return paths != null ? paths : List.of(scope.projectPath());
    }

    /**
     * 调后端并统一结果形态：通用信封交给 {@link McpToolSupport#envelope}，其余返回体交给 {@link McpToolSupport#ok}。
     * 接口抛异常时 Node 版（dispatchToolCall 的 catch）回的是 {@code {success:false, error, tool}} 并标记 isError；
     * 进程内直接调 controller 没有 HTTP 状态码，同样在这里接住异常转成 MCP 错误。
     */
    private <T> Object call(String tool, Supplier<T> backendCall) {
        try {
            T result = backendCall.get();
            return result instanceof ApiResponse<?> response ? McpToolSupport.envelope(tool, response)
                    : McpToolSupport.ok(result);
        } catch (Exception e) {
            log.warn("[MCP] {} 调用后端异常", tool, e);
            return McpToolSupport.error(tool, e.getMessage());
        }
    }

    /** JS 的真值判断：字符串非空才算有值。 */
    private static boolean nonEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static String firstOf(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** JS 对象里值为 undefined 的键在 JSON.stringify 时会被丢掉：没有值就不放这个键。 */
    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
