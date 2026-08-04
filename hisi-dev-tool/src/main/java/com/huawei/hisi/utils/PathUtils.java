package com.huawei.hisi.utils;

/**
 * 路径处理工具类
 * 统一将所有路径格式标准化为正斜杠格式，确保与 Neo4j 和数据库存储一致
 */
public final class PathUtils {

    private PathUtils() {
        // 工具类不允许实例化
    }

    /**
     * 标准化路径格式
     * - 将所有反斜杠转换为正斜杠
     * - 去除首尾空白
     * - 去除末尾的斜杠
     *
     * @param path 原始路径
     * @return 标准化后的路径
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        // 将反斜杠转换为正斜杠
        String normalized = path.trim().replace('\\', '/');
        // 去除末尾斜杠（但保留根路径如 "C:/"）
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 检查路径是否为空
     *
     * @param path 路径
     * @return 是否为空
     */
    public static boolean isEmpty(String path) {
        return path == null || path.isBlank() || normalize(path).isEmpty();
    }

    /**
     * 连接多个路径部分
     *
     * @param parts 路径部分
     * @return 连接后的标准化路径
     */
    public static String join(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = normalize(parts[i]);
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0 && !sb.toString().endsWith("/") && !part.startsWith("/")) {
                sb.append("/");
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
