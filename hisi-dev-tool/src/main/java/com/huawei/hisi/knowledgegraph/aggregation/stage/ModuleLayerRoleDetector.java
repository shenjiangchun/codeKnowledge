package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * module 职责识别器：按 artifactId 命名后缀约定映射到职责层级。
 *
 * <p>层级偏序 L1 &lt; L2 &lt; L3 &lt; L4 &lt; L5（数字越大层级越高，只能依赖下层/同层）。
 * 无法匹配任何后缀的 module 记为 {@link #UNKNOWN}。
 */
@Component
public class ModuleLayerRoleDetector {

    /** 职责未知 */
    public static final int UNKNOWN = 0;

    private static final Map<String, Integer> SUFFIX_LAYER = Map.ofEntries(
        Map.entry("model", 1), Map.entry("dto", 1), Map.entry("po", 1),
        Map.entry("entity", 1), Map.entry("common", 1), Map.entry("util", 1),
        Map.entry("client", 2), Map.entry("rpc", 2), Map.entry("sdk", 2), Map.entry("feign", 2),
        Map.entry("service", 3), Map.entry("core", 3), Map.entry("biz", 3), Map.entry("domain", 3),
        Map.entry("api", 4), Map.entry("controller", 4), Map.entry("web", 4),
        Map.entry("facade", 4), Map.entry("app", 4),
        Map.entry("gw", 5), Map.entry("gateway", 5), Map.entry("edge", 5), Map.entry("portal", 5)
    );

    /** 按 artifactId 命名后缀识别职责层级，无法识别返回 {@link #UNKNOWN}。 */
    public int detect(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) return UNKNOWN;
        String normalized = artifactId.toLowerCase();
        int dash = normalized.lastIndexOf('-');
        int underscore = normalized.lastIndexOf('_');
        int split = Math.max(dash, underscore);
        String suffix = split > 0 ? normalized.substring(split + 1) : normalized;
        return SUFFIX_LAYER.getOrDefault(suffix, UNKNOWN);
    }
}
