package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 类级职责识别器（Spring 技术分层三级回退）。
 *
 * <p>识别顺序：注解优先 → 类名后缀 → 包名后缀。三级均无法识别返回 UNKNOWN（由 LLM 补全）。
 * 分层偏序：CONTROLLER(4) → SERVICE(3) → REPOSITORY(2) → MODEL(1)，UTILITY 为叶子（可被任意层依赖）。
 */
@Component
public class ClassLayerRoleDetector {

    public static final String CONTROLLER = "CONTROLLER";
    public static final String SERVICE = "SERVICE";
    public static final String REPOSITORY = "REPOSITORY";
    public static final String MODEL = "MODEL";
    public static final String UTILITY = "UTILITY";
    public static final String UNKNOWN = "UNKNOWN";

    /** 分层偏序（数字越大层级越高，只能依赖下层/同层） */
    private static final java.util.Map<String, Integer> LAYER_ORDER = java.util.Map.of(
        MODEL, 1,
        UTILITY, 1,
        REPOSITORY, 2,
        SERVICE, 3,
        CONTROLLER, 4
    );

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
        "RestController", "Controller");
    private static final Set<String> SERVICE_ANNOTATIONS = Set.of(
        "Service");
    private static final Set<String> REPOSITORY_ANNOTATIONS = Set.of(
        "Repository");

    /**
     * 三级回退识别类职责。
     *
     * @param annotations 类上的注解名列表（Spring 注解，如 "RestController"/"Service"）
     * @param className   全限定类名
     * @param packageName 包名
     * @return 类职责（CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN）
     */
    public String detect(List<String> annotations, String className, String packageName) {
        // 一级：注解优先
        if (annotations != null) {
            for (String a : annotations) {
                String simple = a.contains(".") ? a.substring(a.lastIndexOf('.') + 1) : a;
                if (CONTROLLER_ANNOTATIONS.contains(simple)) return CONTROLLER;
                if (SERVICE_ANNOTATIONS.contains(simple)) return SERVICE;
                if (REPOSITORY_ANNOTATIONS.contains(simple)) return REPOSITORY;
            }
        }
        // 二级：类名后缀
        String simpleName = simpleClassName(className);
        String byName = detectBySuffix(simpleName);
        if (byName != null) return byName;
        // 三级：包名后缀
        if (packageName != null) {
            String pkgSuffix = packageName.substring(packageName.lastIndexOf('.') + 1);
            String byPkg = detectBySuffix(pkgSuffix);
            if (byPkg != null) return byPkg;
        }
        return UNKNOWN;
    }

    /** 带来源标记的职责识别结果。 */
    public record RoleDetection(String role, String source) {}

    /**
     * 三级回退识别类职责，并返回来源标记（ANNOTATION / NAME / PACKAGE / UNKNOWN）。
     */
    public RoleDetection detectWithSource(List<String> annotations, String className, String packageName) {
        if (annotations != null) {
            for (String a : annotations) {
                String simple = a.contains(".") ? a.substring(a.lastIndexOf('.') + 1) : a;
                if (CONTROLLER_ANNOTATIONS.contains(simple)) return new RoleDetection(CONTROLLER, "ANNOTATION");
                if (SERVICE_ANNOTATIONS.contains(simple)) return new RoleDetection(SERVICE, "ANNOTATION");
                if (REPOSITORY_ANNOTATIONS.contains(simple)) return new RoleDetection(REPOSITORY, "ANNOTATION");
            }
        }
        String simpleName = simpleClassName(className);
        String byName = detectBySuffix(simpleName);
        if (byName != null) return new RoleDetection(byName, "NAME");
        if (packageName != null) {
            String pkgSuffix = packageName.substring(packageName.lastIndexOf('.') + 1);
            String byPkg = detectBySuffix(pkgSuffix);
            if (byPkg != null) return new RoleDetection(byPkg, "PACKAGE");
        }
        return new RoleDetection(UNKNOWN, "UNKNOWN");
    }

    /** 按后缀名识别职责，无法识别返回 null。 */
    private String detectBySuffix(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase();
        if (lower.endsWith("controller")) return CONTROLLER;
        if (lower.endsWith("service") || lower.endsWith("serviceimpl")) return SERVICE;
        if (lower.endsWith("repository") || lower.endsWith("dao") || lower.endsWith("mapper")) return REPOSITORY;
        if (lower.endsWith("dto") || lower.endsWith("entity") || lower.endsWith("model")
            || lower.endsWith("po") || lower.endsWith("vo") || lower.endsWith("domain")) return MODEL;
        if (lower.endsWith("util") || lower.endsWith("config") || lower.endsWith("common")
            || lower.endsWith("constant") || lower.endsWith("helper")) return UTILITY;
        return null;
    }

    private String simpleClassName(String className) {
        if (className == null) return null;
        int idx = className.lastIndexOf('.');
        return idx > 0 ? className.substring(idx + 1) : className;
    }

    /** 分层偏序值，用于反向依赖判定；UNKNOWN 返回 -1。 */
    public int layerOrder(String role) {
        return LAYER_ORDER.getOrDefault(role, -1);
    }
}
