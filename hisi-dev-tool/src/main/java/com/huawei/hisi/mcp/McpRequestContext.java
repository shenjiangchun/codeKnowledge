package com.huawei.hisi.mcp;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 会话 → 用户令牌。写工具（act_*）以用户身份调后端接口时要用令牌，而工具方法本身拿不到 HTTP 请求头
 * （传输上下文不透明），所以由端点过滤器在请求进入时按 MCP 会话 ID 记下令牌，工具方法用
 * {@link org.springaicommunity.mcp.context.McpSyncRequestContext#sessionId()} 来取。
 *
 * <p>令牌只存在内存里，不落盘；带过期清理。pi 侧每个请求都会带上会话 ID 与令牌两个请求头，
 * 所以任一请求都会刷新对应关系；只读流的请求不带令牌，这里查不到，写工具据此拒绝匿名调用。</p>
 */
@Component
public class McpRequestContext {

    private static final long TOKEN_TTL_MS = 2 * 60 * 60 * 1000L;

    private record TokenEntry(String token, long at) {
    }

    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();

    public void bind(String mcpSessionId, String token) {
        if (mcpSessionId == null || mcpSessionId.isBlank() || token == null || token.isBlank()) {
            return;
        }
        tokens.put(mcpSessionId, new TokenEntry(token, System.currentTimeMillis()));
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> now - e.getValue().at() > TOKEN_TTL_MS);
    }

    /** 该 MCP 会话对应的用户令牌；没有返回 null。 */
    public String token(String mcpSessionId) {
        if (mcpSessionId == null) {
            return null;
        }
        TokenEntry entry = tokens.get(mcpSessionId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.at() > TOKEN_TTL_MS) {
            tokens.remove(mcpSessionId, entry);
            return null;
        }
        return entry.token();
    }
}
