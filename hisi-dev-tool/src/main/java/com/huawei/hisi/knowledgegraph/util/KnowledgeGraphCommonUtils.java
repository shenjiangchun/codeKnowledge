package com.huawei.hisi.knowledgegraph.util;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Common path / exclude helpers shared by knowledge-graph builders
 * across languages (Java, Python, ...).
 *
 * <p>Methods here duplicate the private helpers in
 * {@code KnowledgeGraphBuilder} so that new (non-Java) code paths can
 * call them without depending on the Java builder. The builder retains
 * its own internal versions; this class is purely additive.
 */
@Slf4j
public final class KnowledgeGraphCommonUtils {

    private KnowledgeGraphCommonUtils() {
        // utility class
    }

    /**
     * 路径规范化 —— 统一委托 {@link com.huawei.hisi.utils.PathUtils#normalize(String)}。
     * 反斜杠→正斜杠、trim、去末尾斜杠。
     *
     * @deprecated 新代码请直接使用 {@code PathUtils.normalize(path)}。
     */
    @Deprecated
    public static String normalizePath(String path) {
        return com.huawei.hisi.utils.PathUtils.normalize(path);
    }

    /**
     * Returns {@code true} if {@code filePath} matches any pattern in
     * {@code excludePaths}. Patterns may be either:
     * <ul>
     *   <li>a directory name or file name (exact match on path segments), or</li>
     *   <li>a glob containing {@code *} (single segment) or {@code **}
     *       (any number of segments) — converted to a regex and full-matched</li>
     * </ul>
     * A {@code null} file path or {@code null}/empty exclude list yields {@code false}.
     */
    public static boolean shouldExclude(String filePath, List<String> excludePaths) {
        if (filePath == null || excludePaths == null || excludePaths.isEmpty()) {
            return false;
        }
        String normalized = normalizePath(filePath);
        for (String pattern : excludePaths) {
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            if (pattern.indexOf('*') >= 0) {
                String regex = globToRegex(pattern);
                if (Pattern.matches(regex, normalized)) {
                    log.debug("[shouldExclude] File '{}' excluded by glob pattern '{}' (regex: '{}')", normalized, pattern, regex);
                    return true;
                }
            } else {
                // For non-glob patterns, match as path segments rather than arbitrary substrings
                // Match: "/pattern/", "/pattern" at end, or "^pattern/" at start
                String segmentPattern = "/" + pattern + "/";
                String endPattern = "/" + pattern;
                String startPattern = pattern + "/";
                if (normalized.contains(segmentPattern) || normalized.endsWith(endPattern) || normalized.startsWith(startPattern)) {
                    log.debug("[shouldExclude] File '{}' excluded by path segment pattern '{}'", normalized, pattern);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code absolutePath} relative to {@code projectPath}, using
     * forward slashes and no leading {@code /}. If {@code absolutePath} is
     * not under {@code projectPath}, the normalized absolute path is returned.
     * Identical paths return an empty string.
     *
     * <p>Null handling: if {@code absolutePath} is null, returns null. If
     * {@code projectPath} is null, returns {@code absolutePath} unchanged.
     */
    public static String relativeFilePath(String projectPath, String absolutePath) {
        if (absolutePath == null) {
            return null;
        }
        if (projectPath == null) {
            return absolutePath;
        }
        String normProject = normalizePath(projectPath);
        String normAbs = normalizePath(absolutePath);
        if (normProject.equals(normAbs)) {
            return "";
        }
        String prefix = normProject.endsWith("/") ? normProject : normProject + "/";
        if (normAbs.startsWith(prefix)) {
            return normAbs.substring(prefix.length());
        }
        return normAbs;
    }

    /**
     * Convert a glob (with {@code *} and {@code **}) into a regex.
     * {@code **} maps to {@code .*} (any chars including {@code /}); a single
     * {@code *} maps to {@code [^/]*} (within one path segment). All other
     * regex metacharacters are escaped.
     */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if ("\\.[]{}()+?^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
