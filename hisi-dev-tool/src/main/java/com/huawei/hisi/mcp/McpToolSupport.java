package com.huawei.hisi.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 MCP 工具的公共语义，从 Node 版 hisi-mcp-server 原样搬过来（agent 的提示词与 skill 都依赖这些行为）：
 *
 * <ul>
 *   <li><b>会话级项目范围</b>：{@code kg_select_scope(action="set")} 之后，受范围控制的工具不必再传 projectPaths；
 *       显式传参可临时覆盖。Node 版每个 pi 会话独占一个进程、范围是进程级的；进程内版所有会话共用一个服务，
 *       所以范围按 MCP 会话 ID 隔离（{@link McpSyncRequestContext#sessionId()}，实测同一会话不变、跨会话不同）。</li>
 *   <li><b>路径归一化</b>：路径型参数里的反斜杠统一成正斜杠（图谱按正斜杠路径建键）。</li>
 *   <li><b>失败形态</b>：{@code {"success":false,"error":...,"tool":...}} 且标记为 MCP 错误（isError）。
 *       后端的通用响应信封在业务失败时 HTTP 层仍是成功，必须在这里转成 isError——否则 agent 会把失败当成功。</li>
 *   <li><b>结果序列化用后端自己的 ObjectMapper</b>：MCP 框架自带的序列化器不认后端的 Jackson 配置——雪花 ID（Long）
 *       会被输出成数字（约 3.8e17，超出 JS 安全整数，agent 回传后查不到对应记录），时间类型也可能序列化失败。
 *       所以成功结果一律由这里用后端的 ObjectMapper 渲染成文本，字段形态与 REST 接口完全一致。</li>
 * </ul>
 */
@Component
public class McpToolSupport {

    /** 范围的最长有效期（与 Node 版一致：30 分钟），从设置时刻算起。 */
    private static final long SCOPE_TTL_MS = 30 * 60 * 1000L;
    private static final String SCOPE_HINT =
            "Scope not set. Call kg_select_scope(action=\"list\") -> pick projects -> kg_select_scope(action=\"set\").";

    /**
     * 后端自己的 ObjectMapper。工具方法里的静态便捷方法（{@link #ok} / {@link #envelope} / {@link #error}）用它渲染结果；
     * 由 Spring 构造本组件时注入（本组件一定先于任何工具调用被创建），之前的兜底值只在没有容器时使用。
     */
    private static volatile ObjectMapper appMapper = new ObjectMapper();

    private record ScopeEntry(List<String> projectPaths, long setAt) {
    }

    private final Map<String, ScopeEntry> scopes = new ConcurrentHashMap<>();

    public McpToolSupport(ObjectMapper objectMapper) {
        appMapper = objectMapper;
    }

    /** 一次调用的项目范围；{@code projectPaths} 为 null 表示“既没有显式传参，也没有会话范围”。 */
    public record Scope(List<String> projectPaths) {
        public boolean isSet() {
            return projectPaths != null && !projectPaths.isEmpty();
        }

        public String first() {
            return isSet() ? projectPaths.get(0) : null;
        }
    }

    /**
     * 同时带 {@code projectPaths} 与 {@code projectPath} 两个参数的工具的解析结果，两个值都可能是 null（没传）。
     */
    public record BothScope(List<String> projectPaths, String projectPath) {
        public boolean isSet() {
            return (projectPaths != null && !projectPaths.isEmpty()) || (projectPath != null && !projectPath.isBlank());
        }
    }

    // ── 路径归一化 ──

    public static String path(String value) {
        return value == null ? null : value.replace('\\', '/');
    }

    public static List<String> paths(List<String> values) {
        return values == null ? null : values.stream().map(McpToolSupport::path).toList();
    }

    // ── 会话级范围 ──

    /** 当前会话已锁定的范围；没有或已过期返回 null。 */
    public List<String> scopeOf(McpSyncRequestContext ctx) {
        String key = key(ctx);
        ScopeEntry entry = scopes.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.setAt() > SCOPE_TTL_MS) {
            scopes.remove(key, entry);
            return null;
        }
        return entry.projectPaths();
    }

    public void setScope(McpSyncRequestContext ctx, List<String> projectPaths) {
        scopes.put(key(ctx), new ScopeEntry(paths(projectPaths), System.currentTimeMillis()));
        evictExpired();
    }

    public void clearScope(McpSyncRequestContext ctx) {
        scopes.remove(key(ctx));
    }

    /**
     * 解析多值项目路径：显式 {@code projectPaths} 优先，否则用会话范围。
     * strict 工具两者都没有时返回未设置的 Scope，调用方必须回 {@link #scopeNotSet(String)}；
     * 非 strict 工具则拿到未设置的 Scope 并按“没传”处理（与 Node 版一致）。
     */
    public Scope resolvePaths(McpSyncRequestContext ctx, List<String> explicit_projectPaths, boolean strict) {
        List<String> explicit = paths(explicit_projectPaths);
        if (explicit != null && !explicit.isEmpty()) {
            return new Scope(explicit);
        }
        List<String> scoped = scopeOf(ctx);
        if (scoped != null) {
            return new Scope(scoped);
        }
        return new Scope(null);
    }

    /**
     * 解析单值项目路径（{@code projectPath}）：显式传参优先；否则取会话范围的第一个。
     * strict 工具两者都没有时返回 null，调用方必须回 {@link #scopeNotSet(String)}。
     */
    public String resolvePath(McpSyncRequestContext ctx, String explicit_projectPath, boolean strict) {
        String explicit = path(explicit_projectPath);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        List<String> scoped = scopeOf(ctx);
        if (scoped != null && !scoped.isEmpty()) {
            return scoped.get(0);
        }
        return null;
    }

    /**
     * 同时带 {@code projectPaths} 与 {@code projectPath} 的工具用这个，规则与 Node 版的 injectScope 逐条一致：
     * <ol>
     *   <li>调用方传了其中任意一个 → 两个值都按显式传参处理（没传的那个保持 null，不拿范围去补）；</li>
     *   <li>两个都没传且会话已设范围 → {@code projectPaths = 范围全部}，{@code projectPath = 范围的第一个}；</li>
     *   <li>两个都没传且没有范围 → 两个都是 null；strict 工具此时必须回 {@link #scopeNotSet(String)}，
     *       非 strict 工具按“没传”处理。</li>
     * </ol>
     */
    public BothScope resolveBoth(McpSyncRequestContext ctx, List<String> explicit_projectPaths,
                                 String explicit_projectPath, boolean strict) {
        List<String> explicitPaths = paths(explicit_projectPaths);
        String explicitPath = path(explicit_projectPath);
        if ((explicitPaths != null && !explicitPaths.isEmpty()) || (explicitPath != null && !explicitPath.isBlank())) {
            return new BothScope(explicitPaths, explicitPath);
        }
        List<String> scoped = scopeOf(ctx);
        if (scoped != null && !scoped.isEmpty()) {
            return new BothScope(scoped, scoped.get(0));
        }
        return new BothScope(null, null);
    }

    private static String key(McpSyncRequestContext ctx) {
        // 无会话 ID（无状态模式）时退化为全局一份；本项目用的是有会话的 STREAMABLE，不会走到这里
        return ctx == null || ctx.sessionId() == null ? "" : ctx.sessionId();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        scopes.entrySet().removeIf(e -> now - e.getValue().setAt() > SCOPE_TTL_MS);
    }

    // ── 结果与失败形态 ──

    /** 成功结果：用后端自己的 ObjectMapper 渲染（Long→字符串、时间类型等与 REST 一致）。 */
    public static McpSchema.CallToolResult ok(Object body) {
        return callResult(body instanceof ResponseEntity<?> entity ? entity.getBody() : body, false);
    }

    /** 业务失败：与 Node 版同形，并标记为 MCP 错误。 */
    public static McpSchema.CallToolResult error(String tool, String error) {
        return callResult(Map.of("success", false, "error", error == null ? "" : error, "tool", tool), true);
    }

    /** 严格型工具在没有范围时的固定回复（与 Node 版同形）。 */
    public static McpSchema.CallToolResult scopeNotSet(String tool) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", "SCOPE_NOT_SET");
        body.put("message", SCOPE_HINT);
        body.put("tool", tool);
        return callResult(body, true);
    }

    /** 后端通用响应信封：业务码 200 原样返回（字段与 REST 一致）；其余一律转成带后端消息的 MCP 错误。 */
    public static McpSchema.CallToolResult envelope(String tool, ApiResponse<?> response) {
        if (response == null) {
            return error(tool, "后端返回空结果");
        }
        if (response.getCode() != 200) {
            return error(tool, response.getMessage());
        }
        return ok(response);
    }

    public static McpSchema.CallToolResult callResult(Object body, boolean isError) {
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(appMapper.writeValueAsString(body))
                    .isError(isError)
                    .build();
        } catch (Exception e) {
            // 渲染失败本身就是工具失败，不能让框架再用它自带的序列化器去试一遍（结果会不一致）
            return callResult(Map.of("success", false,
                    "error", "工具结果序列化失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "tool", String.valueOf(body.getClass().getSimpleName())), true);
        }
    }

    /** 供类型守卫使用：集合类型。 */
    static boolean isCollection(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || type.isArray();
    }

    /** 供类型守卫使用：数值类型（含基本类型）。 */
    static boolean isNumber(Class<?> type) {
        return Number.class.isAssignableFrom(type) || type == int.class || type == long.class || type == double.class
                || type == float.class || type == short.class || type == byte.class;
    }
}
