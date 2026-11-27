package com.huawei.hisi.mcp;

import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.project.remote.controller.RemoteProjectController;
import com.huawei.hisi.project.remote.controller.RemoteProjectController.ProjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 进程内 MCP 的范围选择工具：Node 版 index.ts 里的 handleScopeTool 原样搬来。
 *
 * <p>协议：agent 先 {@code action="list"} 列出已建图的项目并请用户挑选，再 {@code action="set"} 锁定范围；
 * 之后的 kg_* / hybrid_search 工具自动继承这个范围，不必重复传 projectPaths（显式传参可临时覆盖）。
 * 范围按 MCP 会话隔离，存取都在 {@link McpToolSupport} 里——Node 版是进程级的全局范围，因为它每个 pi 会话
 * 独占一个进程。四个动作的返回结构与 Node 版逐字段一致。</p>
 */
@Component
public class KgScopeMcpTools {

    /** 合同里 action 的取值；形参用枚举类型声明，入参 schema 才带 enum。 */
    public enum Action {
        list, set, get, clear
    }

    private static final Logger log = LoggerFactory.getLogger(KgScopeMcpTools.class);

    /** list 动作最多为前多少个项目取图谱统计，避免一次把后端压垮。 */
    private static final int MAX_STATS = 20;

    private final McpToolSupport support;
    private final KnowledgeGraphController knowledgeGraphController;
    private final RemoteProjectController remoteProjectController;

    public KgScopeMcpTools(McpToolSupport support,
                           KnowledgeGraphController knowledgeGraphController,
                           RemoteProjectController remoteProjectController) {
        this.support = support;
        this.knowledgeGraphController = knowledgeGraphController;
        this.remoteProjectController = remoteProjectController;
    }

    /**
     * 四个动作的成功结果与 Node 版逐字段一致。几处刻意不同：
     * <ul>
     *   <li>业务失败（set 没带 projectPaths）与异常都走 {@link McpToolSupport#error}——多一个 tool 字段且标记 isError，
     *       Node 版是把 {success:false, error} 当普通结果返回；</li>
     *   <li>action 取值不在枚举里时，框架在调到这里之前就已回错误（Node 版是回 Unknown action）；</li>
     *   <li>set 的回执里没有 scopeToken——Node 版 stdio 模式下它本来就是 undefined，不进 JSON。</li>
     * </ul>
     */
    @McpTool(name = "kg_select_scope", description = """
            [MANDATORY FIRST CALL] Select KG search scope for this session.

            Protocol:
            1. Call action="list" -> get all indexed KG projects + remote project configs
            2. Use AskUserQuestion to ask user "Which projects to search?" (multiSelect=true)
            3. Call action="set" with selected projectPaths -> scope is locked
            4. All subsequent KG tools auto-use this scope. No need to pass projectPaths again.

            projectPaths override: pass explicit projectPaths in any tool to temporarily override scope.
            Clear scope: action="clear" resets to full-project search.""")
    public Object kgSelectScope(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "list=show all projects, set=lock scope, get=view current, clear=reset", required = true) Action action,
            @McpToolParam(description = "Project paths to set as scope (required for action=\"set\")", required = false) List<String> projectPaths) {
        // Node 版：action 缺省按 list 处理
        Action act = action == null ? Action.list : action;
        try {
            return switch (act) {
                case list -> McpToolSupport.ok(list(ctx));
                case set -> set(ctx, projectPaths);
                case get -> McpToolSupport.ok(get(ctx));
                case clear -> McpToolSupport.ok(clear(ctx));
            };
        } catch (Exception e) {
            log.warn("[MCP] kg_select_scope({}) 失败", act, e);
            return McpToolSupport.error("kg_select_scope", e.getMessage());
        }
    }

    // ==================== 四个动作 ====================

    /** 已建图的项目 + 远程项目配置，分成本地 / 已建图的远程 / 已克隆未建图 / 未克隆四类，并带上前 20 个项目的统计。 */
    private Map<String, Object> list(McpSyncRequestContext ctx) {
        // 两份列表各自取：任何一份取不到都按空列表处理，不影响另一份，也不让 list 整体失败
        List<String> kgPaths = indexedProjects();
        List<ProjectResponse> remoteProjects = remoteProjects();

        // 归一（反斜杠→正斜杠、转小写）后做包含判断
        Set<String> kgNormSet = new LinkedHashSet<>();
        for (String p : kgPaths) {
            kgNormSet.add(norm(p));
        }
        Set<String> remoteNormPaths = new LinkedHashSet<>();
        for (ProjectResponse r : remoteProjects) {
            remoteNormPaths.add(norm(remoteFullPath(r)));
        }

        List<ProjectResponse> indexedRemote = new ArrayList<>();
        List<ProjectResponse> clonedNotIndexed = new ArrayList<>();
        List<ProjectResponse> notCloned = new ArrayList<>();
        for (ProjectResponse r : remoteProjects) {
            String fp = norm(remoteFullPath(r));
            boolean inKg = false;
            for (String kp : kgNormSet) {
                if (kp.contains(fp) || fp.contains(kp)) {
                    inKg = true;
                    break;
                }
            }
            if (!"CLONED".equals(r.cloneStatus())) {
                notCloned.add(r);
            } else if (inKg) {
                indexedRemote.add(r);
            } else {
                clonedNotIndexed.add(r);
            }
        }
        // 已建图的项目里，路径不落在任何远程项目目录下的，才算纯本地项目
        List<String> pureLocal = new ArrayList<>();
        for (String p : kgPaths) {
            String np = norm(p);
            boolean underRemote = false;
            for (String rf : remoteNormPaths) {
                if (!rf.isEmpty() && np.contains(rf)) {
                    underRemote = true;
                    break;
                }
            }
            if (!underRemote) {
                pureLocal.add(p);
            }
        }

        // 只为前 MAX_STATS 个项目取统计，避免后端过载
        boolean truncated = kgPaths.size() > MAX_STATS;
        Map<String, Object> stats = new HashMap<>();
        for (String p : kgPaths.subList(0, Math.min(MAX_STATS, kgPaths.size()))) {
            stats.put(p, statusOf(p));
        }

        List<String> currentScope = support.scopeOf(ctx);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalIndexed", kgPaths.size());
        summary.put("totalRemoteConfigs", remoteProjects.size());
        summary.put("pureLocal", pureLocal.size());
        summary.put("indexedRemote", indexedRemote.size());
        summary.put("clonedNotIndexed", clonedNotIndexed.size());
        summary.put("notCloned", notCloned.size());

        List<Map<String, Object>> searchableProjects = new ArrayList<>();
        for (String p : kgPaths) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", p);
            item.put("stats", stats.getOrDefault(p, Map.of()));
            item.put("source", pureLocal.contains(p) ? "local" : "remote-indexed");
            searchableProjects.add(item);
        }

        // Node 版这三组里都还带一个 fullPath（取 r.fullPath），但接口响应里没有这个字段，它恒为 undefined，
        // JSON.stringify 时被丢掉——所以 Node 版实际输出的就是下面这些键。这里保持一致，不输出 fullPath。
        List<Map<String, Object>> indexedItems = new ArrayList<>();
        for (ProjectResponse r : indexedRemote) {
            Map<String, Object> item = summaryOf(r);
            item.put("branch", r.branch());
            indexedItems.add(item);
        }
        List<Map<String, Object>> clonedItems = new ArrayList<>();
        for (ProjectResponse r : clonedNotIndexed) {
            Map<String, Object> item = summaryOf(r);
            item.put("action", "Cloned but not KG-indexed — run KG build first");
            clonedItems.add(item);
        }
        List<Map<String, Object>> notClonedItems = new ArrayList<>();
        for (ProjectResponse r : notCloned) {
            Map<String, Object> item = summaryOf(r);
            item.put("cloneStatus", r.cloneStatus());
            item.put("action", "Not cloned — clone first then KG build");
            notClonedItems.add(item);
        }
        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("indexed", indexedItems);
        remote.put("clonedNotIndexed", clonedItems);
        remote.put("notCloned", notClonedItems);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", summary);
        out.put("truncated", truncated);
        out.put("sampleCount", MAX_STATS);
        out.put("searchableProjects", searchableProjects);
        out.put("remoteProjects", remote);
        out.put("currentScope", currentScope);
        out.put("instruction", currentScope != null
                ? "Scope locked: " + currentScope.size() + " projects. Start searching."
                : "No scope set. Ask user to pick projects from searchableProjects, then call action=\"set\".");
        return out;
    }

    private Object set(McpSyncRequestContext ctx, List<String> projectPaths) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            return McpToolSupport.error("kg_select_scope", "projectPaths is required for action=\"set\"");
        }
        support.setScope(ctx, projectPaths);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        // 回显调用方传入的路径（与 Node 版一致）；存进范围的那份由 McpToolSupport 归一成正斜杠
        out.put("projectPaths", projectPaths);
        out.put("projectCount", projectPaths.size());
        out.put("message",
                "Scope locked: " + projectPaths.size() + " projects. All subsequent tools auto-use this scope.");
        return McpToolSupport.ok(out);
    }

    private Map<String, Object> get(McpSyncRequestContext ctx) {
        List<String> current = support.scopeOf(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("scoped", current != null);
        out.put("projectPaths", current != null ? current : List.of());
        out.put("projectCount", current != null ? current.size() : 0);
        return out;
    }

    private Map<String, Object> clear(McpSyncRequestContext ctx) {
        support.clearScope(ctx);
        return mapOf("success", true, "message", "Scope cleared. Full project search restored.");
    }

    // ==================== 数据来源 ====================

    /** 已建图的项目路径，对应 GET /api/v2/knowledge-graph/projects（v2 对它只是转调）。 */
    private List<String> indexedProjects() {
        try {
            ApiResponse<List<String>> projects = knowledgeGraphController.getProjects();
            return projects.getData() == null ? List.of() : projects.getData();
        } catch (Exception e) {
            log.warn("[MCP] kg_select_scope 取已建图项目列表失败", e);
            return List.of();
        }
    }

    /** 远程项目配置，对应 GET /api/remote-projects。 */
    private List<ProjectResponse> remoteProjects() {
        try {
            ApiResponse<List<ProjectResponse>> remote = remoteProjectController.list();
            return remote.getData() == null ? List.of() : remote.getData();
        } catch (Exception e) {
            log.warn("[MCP] kg_select_scope 取远程项目列表失败", e);
            return List.of();
        }
    }

    /**
     * 单个项目的图谱统计，对应 GET /api/v2/knowledge-graph/status?projectPaths=p。
     * 取不到时与 Node 版一样给出占位（业务失败 → no_data，接口异常 → error + 原因），不让 list 整体失败。
     */
    private Map<String, Object> statusOf(String projectPath) {
        try {
            ApiResponse<Map<String, Object>> status = knowledgeGraphController.getStatus(null, List.of(projectPath));
            if (status.isSuccess() && status.getData() != null) {
                return status.getData();
            }
            return mapOf("status", "no_data");
        } catch (Exception e) {
            return mapOf("status", "error", "detail", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 远程项目的全路径。Node 版读的是 {@code r.fullPath || r.localPath}，而 /api/remote-projects 的响应体里
     * 并没有 fullPath 这个字段（全路径落在 localPath 里），所以它实际拿到的是 localPath——这里取同一个值。
     */
    private static String remoteFullPath(ProjectResponse r) {
        return r.localPath();
    }

    /** Node 版输出里远程项目的公共部分：名称与仓库地址。 */
    private static Map<String, Object> summaryOf(ProjectResponse r) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", r.name());
        item.put("gitUrl", r.gitUrl());
        return item;
    }

    /** 归一后再做包含判断：反斜杠→正斜杠、转小写；没有值按空串处理。 */
    private static String norm(String value) {
        return (value == null ? "" : value).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> mapOf(Object... keyAndValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyAndValue.length; i += 2) {
            out.put((String) keyAndValue[i], keyAndValue[i + 1]);
        }
        return out;
    }
}
