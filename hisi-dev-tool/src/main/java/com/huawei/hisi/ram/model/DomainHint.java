package com.huawei.hisi.ram.model;

import java.util.List;

/**
 * Domain inference result for phase 2 analysis.
 * Determines the analysis type and recommended KG tools based on user question.
 *
 * @param analysisType   the type of analysis to perform
 * @param primaryTools   recommended KG tools to use
 * @param treeDirection  call tree direction (upstream/downstream)
 * @param focusOnBridges whether to focus on bridge points (Feign/MQ)
 */
public record DomainHint(
        AnalysisType analysisType,
        List<String> primaryTools,
        TreeDirection treeDirection,
        boolean focusOnBridges
) {

    /**
     * Analysis type enum for domain inference.
     */
    public enum AnalysisType {
        /** Impact analysis: trace upstream to find callers */
        IMPACT_ANALYSIS,
        /** Flow analysis: trace downstream to understand execution flow */
        FLOW_ANALYSIS,
        /** Dependency analysis: focus on external dependencies */
        DEPENDENCY_ANALYSIS,
        /** Entry analysis: find entry points and request handlers */
        ENTRY_ANALYSIS,
        /** General analysis: mixed approach */
        GENERAL_ANALYSIS
    }

    /**
     * Call tree direction for tracing.
     */
    public enum TreeDirection {
        /** Trace upstream (callers) */
        UPSTREAM,
        /** Trace downstream (callees) */
        DOWNSTREAM,
        /** Trace both directions */
        BOTH
    }

    /**
     * Infer domain hint from user question.
     *
     * @param question the user's question
     * @return inferred domain hint
     */
    public static DomainHint inferDomain(String question) {
        if (question == null || question.isBlank()) {
            return generalAnalysis();
        }

        String lowerQ = question.toLowerCase();

        // Impact analysis: find callers, upstream dependencies
        if (containsAny(lowerQ, "影响", "受", "上游", "调用者", "谁调", "impact", "caller", "upstream")) {
            return new DomainHint(
                    AnalysisType.IMPACT_ANALYSIS,
                    List.of("rootEntries", "affecting"),
                    TreeDirection.UPSTREAM,
                    false
            );
        }

        // Flow analysis: understand execution flow
        if (containsAny(lowerQ, "如何", "流程", "实现", "执行", "how", "flow", "process", "implementation")) {
            return new DomainHint(
                    AnalysisType.FLOW_ANALYSIS,
                    List.of("calleesTree", "downstream"),
                    TreeDirection.DOWNSTREAM,
                    false
            );
        }

        // Dependency analysis: external dependencies
        if (containsAny(lowerQ, "依赖", "调用", "外部", "dependency", "external", "feign", "mq")) {
            return new DomainHint(
                    AnalysisType.DEPENDENCY_ANALYSIS,
                    List.of("bridges", "bridgeStats"),
                    TreeDirection.BOTH,
                    true
            );
        }

        // Entry analysis: find entry points
        if (containsAny(lowerQ, "入口", "入口点", "controller", "定时", "消息", "entry", "scheduled", "listener")) {
            return new DomainHint(
                    AnalysisType.ENTRY_ANALYSIS,
                    List.of("entryPoints", "rootEntries"),
                    TreeDirection.UPSTREAM,
                    false
            );
        }

        return generalAnalysis();
    }

    private static DomainHint generalAnalysis() {
        return new DomainHint(
                AnalysisType.GENERAL_ANALYSIS,
                List.of("hybridSearch", "entryPoints"),
                TreeDirection.BOTH,
                false
        );
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}