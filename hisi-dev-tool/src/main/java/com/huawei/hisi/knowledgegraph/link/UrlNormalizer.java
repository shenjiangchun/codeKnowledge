package com.huawei.hisi.knowledgegraph.link;

/**
 * 前端 URL 与后端 EntryPoint.entryKey 的归一化工具。
 *
 * <p>前端 axios URL 的路径参数是模板形式 {@code ${var}}（如
 * {@code /callchain/analysis/project/${projectName}}），后端 {@code entryKey}
 * 用 {@code @PathVariable} 占位 {@code {var}}（如
 * {@code DELETE /callchain/analysis/project/{projectName}}）。
 * 归一化把两侧的路径参数统一为 {@code :param} 占位符后再比对。</p>
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /**
     * 归一化前端 API 调用 URL。
     * <ul>
     *   <li>移除首尾空白</li>
     *   <li>去掉前导 {@code /api} 前缀（与后端 entryKey 的 fullPath 对齐）</li>
     *   <li>{@code ${var}} → {@code :param}</li>
     *   <li>追加前导 {@code /}（若无）</li>
     * </ul>
     */
    public static String normalizeFrontendUrl(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        // 去前导 /api 前缀（request.ts baseURL 已含 /api，后端 fullPath 也含 /api）
        if (u.startsWith("/api/")) {
            u = u.substring(4); // 去掉 "/api"，保留 "/xxx"
        } else if (u.equals("/api")) {
            u = "";
        }
        // ${var} → :param
        u = u.replaceAll("\\$\\{[^}]+\\}", ":param");
        if (!u.startsWith("/")) {
            u = "/" + u;
        }
        return u;
    }

    /**
     * 归一化后端 entryKey（形如 {@code GET /api/projects/{id}}）。
     * <ul>
     *   <li>提取 HTTP 方法（大写）与路径部分</li>
     *   <li>去掉路径前导 {@code /api} 前缀</li>
     *   <li>{@code {var}} → {@code :param}</li>
     * </ul>
     */
    public static String normalizeEntryKey(String entryKey) {
        if (entryKey == null) {
            return "";
        }
        String key = entryKey.trim();
        int space = key.indexOf(' ');
        String method;
        String path;
        if (space > 0) {
            method = key.substring(0, space).toUpperCase();
            path = key.substring(space + 1).trim();
        } else {
            method = "";
            path = key;
        }
        if (path.startsWith("/api/")) {
            path = path.substring(4);
        } else if (path.equals("/api")) {
            path = "";
        }
        path = path.replaceAll("\\{[^}]+\\}", ":param");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return method.isEmpty() ? path : method + " " + path;
    }
}
