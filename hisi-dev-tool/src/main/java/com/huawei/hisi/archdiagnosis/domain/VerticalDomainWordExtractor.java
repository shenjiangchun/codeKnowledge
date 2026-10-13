package com.huawei.hisi.archdiagnosis.domain;

import java.util.List;

/**
 * 垂直领域词提取：类名剥离技术后缀得核心领域词（诊断侧纯计算，不碰生成侧）。
 *
 * <p>只做「去技术后缀」，不做复合词拆分与缩写还原——那是
 * {@link VerticalDomainNamer}（LLM 消歧）的职责。后缀按长度降序匹配，
 * 保证 {@code ServiceImpl} 先于 {@code Impl} 剥离；短全大写后缀（DO/PO/VO/QO/BO）
 * 大小写敏感匹配，避免 {@code Pseudo} 这类普通词被 {@code DO} 误伤。</p>
 *
 * <p>无匹配后缀 / 剥离后为空 / 空输入，一律回退原文（简单名），不抛异常。</p>
 */
public final class VerticalDomainWordExtractor {

    /** 技术后缀（按长度降序），用于 {@code endsWith} 精确剥离。 */
    private static final List<String> TECH_SUFFIXES = List.of(
        "Configuration",
        "ServiceImpl",
        "Interceptor",
        "Controller",
        "Repository",
        "Constants",
        "Exception",
        "Listener",
        "Constant",
        "Response",
        "Request",
        "Service",
        "Factory",
        "Builder",
        "Handler",
        "Filter",
        "Aspect",
        "Helper",
        "Mapper",
        "Entity",
        "Config",
        "Utils",
        "Model",
        "Enum",
        "Util",
        "Impl",
        "DTO",
        "Dao",
        "PO",
        "DO",
        "VO",
        "QO",
        "BO"
    );

    /**
     * 提取核心领域词。
     *
     * @param className 全限定类名（如 {@code com.example.order.OrderController}）
     * @return 核心领域词（如 {@code Order}）；无法识别时回退简单类名原文
     */
    public String extract(String className) {
        String simple = simpleName(className);
        if (simple == null || simple.isBlank()) {
            return "";
        }
        String core = stripSuffix(simple);
        return core.isBlank() ? simple : core;
    }

    private String stripSuffix(String simple) {
        for (String suffix : TECH_SUFFIXES) {
            if (simple.endsWith(suffix)) {
                return simple.substring(0, simple.length() - suffix.length());
            }
        }
        return simple;
    }

    private String simpleName(String className) {
        if (className == null) {
            return null;
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }
}
