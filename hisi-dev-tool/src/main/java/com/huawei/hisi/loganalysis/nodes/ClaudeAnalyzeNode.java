package com.huawei.hisi.loganalysis.nodes;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ClaudeAnalyzeNode - Fourth node in log analysis DAG.
 *
 * v3: Three-round progressive analysis.
 *   Round 1 — 模式识别 (2000 tokens): 快速判断事故类型
 *   Round 2 — 因果推理 (8000 tokens): 基于模式 + 代码上下文深度分析
 *   Round 3 — 修复方案 (4000 tokens): 基于因果链设计分优先级修复方案
 *
 * 降级策略:
 *   Round1 失败 → 单轮回退（Phase A 方案），version="2.0-fallback"
 *   Round2 失败 → 使用 Round1 假设作为根因 + 默认 P2 建议，version="2.0-partial"
 *   Round3 失败 → 保留 Round1+Round2 结果 + 默认 P2 建议
 *
 * Input: { parsedError, codeBodies, callChains, entryPoints, entryPointsWithLayers }
 * Output: { rootCauseAnalysis, fixSuggestions, causalChain, multiFactorAnalysis, timeline, analysisVersion, patternType, ... }
 */
@Slf4j
@Component
public class ClaudeAnalyzeNode implements LogAnalysisDagNode {

    private final RamClaudeJsonClient claudeClient;
    private final RoundPromptBuilder promptBuilder;

    public ClaudeAnalyzeNode(RamClaudeJsonClient claudeClient, RoundPromptBuilder promptBuilder) {
        this.claudeClient = claudeClient;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public String name() {
        return "ClaudeAnalyzeNode";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[ClaudeAnalyzeNode] 开始三轮递进分析 (v3)");

        if (!claudeClient.isAvailable()) {
            log.warn("[ClaudeAnalyzeNode] Claude API 未配置，返回基础分析");
            return fallbackAnalysis(input);
        }

        Map<String, Object> parsedError = (Map<String, Object>) input.get("parsedError");
        List<MethodBodyInfo> codeBodies = (List<MethodBodyInfo>) input.get("codeBodies");
        List<Map<String, Object>> callChains = (List<Map<String, Object>>) input.get("callChains");
        List<?> entryPoints = (List<?>) input.get("entryPoints");
        List<Map<String, Object>> entryPointsWithLayers = (List<Map<String, Object>>) input.get("entryPointsWithLayers");

        Map<String, Object> output = new LinkedHashMap<>(input);

        // ── Round 1: 模式识别 ──
        Map<String, Object> round1Result = null;
        try {
            log.info("[ClaudeAnalyzeNode] Round 1: 模式识别");
            String r1Sys = promptBuilder.buildRound1SystemPrompt();
            String r1User = promptBuilder.buildRound1UserPrompt(parsedError);
            SendOptions r1Opts = new SendOptions(claudeClient.defaultModel(), 2000, 0.3, null);
            round1Result = claudeClient.callJson(r1Sys, r1User, r1Opts);
            log.info("[ClaudeAnalyzeNode] Round 1 完成: patternType={}, confidence={}",
                    round1Result.get("patternType"), round1Result.get("patternConfidence"));
        } catch (Exception e) {
            log.warn("[ClaudeAnalyzeNode] Round 1 失败，进入单轮回退: {}", e.getMessage());
        }

        // ── Round 1 失败 → 单轮回退（Phase A 方案） ──
        if (round1Result == null || round1Result.isEmpty()) {
            return singleRoundFallback(input, parsedError, codeBodies, callChains, entryPoints, entryPointsWithLayers);
        }

        // ── Round 2: 因果推理 ──
        Map<String, Object> round2Result = null;
        try {
            String patternType = String.valueOf(round1Result.getOrDefault("patternType", "UNKNOWN"));
            log.info("[ClaudeAnalyzeNode] Round 2: 因果推理 (pattern={})", patternType);
            String r2Sys = promptBuilder.buildRound2SystemPrompt(patternType);
            String r2User = promptBuilder.buildRound2UserPrompt(round1Result, codeBodies, callChains, entryPoints, entryPointsWithLayers);
            SendOptions r2Opts = new SendOptions(claudeClient.defaultModel(), 8000, 0.3, null);
            round2Result = claudeClient.callJson(r2Sys, r2User, r2Opts);
            log.info("[ClaudeAnalyzeNode] Round 2 完成: rootCause={}, confidence={}",
                    round2Result.get("rootCause"), round2Result.get("confidence"));
        } catch (Exception e) {
            log.warn("[ClaudeAnalyzeNode] Round 2 失败，使用 Round1 假设: {}", e.getMessage());
        }

        // ── Round 2 失败 → 使用 Round1 假设 ──
        if (round2Result == null || round2Result.isEmpty()) {
            return round2FailedFallback(output, round1Result);
        }

        // ── Round 3: 修复方案 ──
        Map<String, Object> round3Result = null;
        try {
            log.info("[ClaudeAnalyzeNode] Round 3: 修复方案");
            String r3Sys = promptBuilder.buildRound3SystemPrompt();
            String r3User = promptBuilder.buildRound3UserPrompt(round2Result);
            SendOptions r3Opts = new SendOptions(claudeClient.defaultModel(), 4000, 0.3, null);
            round3Result = claudeClient.callJson(r3Sys, r3User, r3Opts);
            log.info("[ClaudeAnalyzeNode] Round 3 完成: suggestions={}",
                    round3Result.get("fixSuggestions") instanceof List<?> l ? l.size() : 0);
        } catch (Exception e) {
            log.warn("[ClaudeAnalyzeNode] Round 3 失败，使用默认 P2 建议: {}", e.getMessage());
        }

        // ── 合并三轮结果 ──
        return mergeResults(output, round1Result, round2Result, round3Result);
    }

    // ========== 降级策略 ==========

    /**
     * Round 1 失败时的单轮回退：合并 Round1+Round2 的 system prompt，走 Phase A 方案。
     */
    private Map<String, Object> singleRoundFallback(Map<String, Object> input,
                                                     Map<String, Object> parsedError,
                                                     List<MethodBodyInfo> codeBodies,
                                                     List<Map<String, Object>> callChains,
                                                     List<?> entryPoints,
                                                     List<Map<String, Object>> entryPointsWithLayers) {
        log.info("[ClaudeAnalyzeNode] 执行单轮回退分析");
        Map<String, Object> output = new LinkedHashMap<>(input);

        try {
            // 使用专用 fallback prompt（统一输出格式，避免 Round1/Round2 格式矛盾）
            String systemPrompt = promptBuilder.buildFallbackSystemPrompt();
            // 复用 buildRound2UserPrompt 构建完整用户上下文
            Map<String, Object> syntheticRound1 = Map.of(
                    "patternType", "UNKNOWN",
                    "patternConfidence", "low",
                    "initialHypothesis", "模式识别失败，进行通用分析",
                    "suggestedDepth", "medium");
            String userPrompt = promptBuilder.buildRound2UserPrompt(
                    syntheticRound1, codeBodies, callChains, entryPoints, entryPointsWithLayers)
                    + "\n请严格按照系统提示中的输出格式进行综合分析。\n";

            SendOptions opts = new SendOptions(claudeClient.defaultModel(), 8000, 0.3, null);
            Map<String, Object> analysis = claudeClient.callJson(systemPrompt, userPrompt, opts);

            output.put("rootCauseAnalysis", extractRootCause(analysis));
            output.put("fixSuggestions", extractFixSuggestions(analysis));
            output.put("analysisConfidence", extractConfidence(analysis));
            output.put("causalChain", extractCausalChain(analysis));
            output.put("multiFactorAnalysis", extractMultiFactorAnalysis(analysis));
            output.put("timeline", extractTimeline(analysis));
            output.put("analysisVersion", "2.0-fallback");
            output.put("rawAnalysis", analysis);
            log.info("[ClaudeAnalyzeNode] 单轮回退完成");
        } catch (Exception e) {
            log.error("[ClaudeAnalyzeNode] 单轮回退也失败: {}", e.getMessage());
            output.put("rootCauseAnalysis", "分析失败: " + e.getMessage());
            output.put("fixSuggestions", Collections.emptyList());
            output.put("causalChain", Collections.emptyList());
            output.put("multiFactorAnalysis", Map.of());
            output.put("timeline", Collections.emptyList());
            output.put("analysisVersion", "error");
            output.put("analysisError", e.getMessage());
        }

        return output;
    }

    /**
     * Round 2 失败时：使用 Round1 假设作为根因，生成默认 P2 建议。
     */
    private Map<String, Object> round2FailedFallback(Map<String, Object> output, Map<String, Object> round1Result) {
        String hypothesis = String.valueOf(round1Result.getOrDefault("initialHypothesis", "无法确定根因"));
        String patternType = String.valueOf(round1Result.getOrDefault("patternType", "UNKNOWN"));

        output.put("rootCauseAnalysis", hypothesis);
        output.put("analysisConfidence", round1Result.getOrDefault("patternConfidence", "low"));
        output.put("patternType", patternType);
        output.put("causalChain", Collections.emptyList());
        output.put("multiFactorAnalysis", Map.of());
        output.put("timeline", Collections.emptyList());
        output.put("fixSuggestions", Collections.singletonList(Map.of(
                "suggestion", "基于初步模式识别 [" + patternType + "]，建议: " + hypothesis,
                "priority", "P2",
                "affectedCode", "待进一步分析",
                "expectedEffect", "需要更深入的代码分析才能确定具体修复方案")));
        output.put("analysisVersion", "2.0-partial");
        output.put("rawAnalysis", round1Result);

        log.info("[ClaudeAnalyzeNode] Round2 降级完成: pattern={}", patternType);
        return output;
    }

    /**
     * Claude API 不可用时的规则分析。
     */
    private Map<String, Object> fallbackAnalysis(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<>(input);

        Map<String, Object> parsedError = (Map<String, Object>) input.get("parsedError");
        String errorType = (String) parsedError.get("errorType");
        String rootCause = (String) parsedError.get("rootCauseException");
        String message = (String) parsedError.get("fullMessage");

        StringBuilder analysis = new StringBuilder();
        analysis.append("基于规则的分析结果（Claude API 未配置）:\n\n");
        analysis.append("错误类型: ").append(errorType != null ? errorType : "未知").append("\n");
        analysis.append("根因异常: ").append(rootCause != null ? rootCause : "无").append("\n\n");
        analysis.append("建议: 请检查堆栈中的关键方法，分析业务逻辑是否有错误。\n");

        if (message != null && message.length() > 100) {
            analysis.append("\n错误消息片段: ").append(message.substring(0, 200));
        }

        output.put("rootCauseAnalysis", analysis.toString());
        output.put("fixSuggestions", Collections.singletonList(Map.of("suggestion", "请手动分析代码并检查错误位置", "priority", "P2")));
        output.put("analysisConfidence", "low");
        output.put("analysisVersion", "rule-based");

        return output;
    }

    // ========== 结果合并 ==========

    /**
     * 合并三轮分析结果到输出 map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeResults(Map<String, Object> output,
                                              Map<String, Object> round1Result,
                                              Map<String, Object> round2Result,
                                              Map<String, Object> round3Result) {
        // Round 1: 模式识别
        output.put("patternType", round1Result.getOrDefault("patternType", "UNKNOWN"));
        output.put("patternConfidence", round1Result.getOrDefault("patternConfidence", "low"));
        output.put("initialHypothesis", round1Result.getOrDefault("initialHypothesis", ""));
        output.put("suggestedDepth", round1Result.getOrDefault("suggestedDepth", "medium"));

        // Round 2: 因果推理
        output.put("rootCauseAnalysis", extractRootCause(round2Result));
        output.put("analysisConfidence", extractConfidence(round2Result));
        output.put("causalChain", extractCausalChain(round2Result));
        output.put("multiFactorAnalysis", extractMultiFactorAnalysis(round2Result));
        output.put("timeline", extractTimeline(round2Result));
        output.put("confidenceReason", round2Result.getOrDefault("confidenceReason", ""));

        // Round 3: 修复方案
        if (round3Result != null && !round3Result.isEmpty()) {
            List<Map<String, Object>> suggestions = extractFixSuggestions(round3Result);
            output.put("fixSuggestions", suggestions.isEmpty() ? defaultPSuggestions() : suggestions);
            output.put("verificationChecklist", extractStringList(round3Result, "verificationChecklist"));
            output.put("riskAssessment", round3Result.getOrDefault("riskAssessment", ""));
        } else {
            output.put("fixSuggestions", defaultPSuggestions());
            output.put("verificationChecklist", Collections.emptyList());
            output.put("riskAssessment", "");
        }

        output.put("analysisVersion", "3.0");
        output.put("rawAnalysis", Map.of(
                "round1", round1Result,
                "round2", round2Result,
                "round3", round3Result != null ? round3Result : Map.of()));

        log.info("[ClaudeAnalyzeNode] 三轮分析合并完成: pattern={}, version=3.0",
                output.get("patternType"));
        return output;
    }

    // ========== 辅助方法 ==========

    private List<Map<String, Object>> defaultPSuggestions() {
        return Collections.singletonList(Map.of(
                "suggestion", "请基于以上根因分析手动制定修复方案",
                "priority", "P2",
                "affectedCode", "待确定",
                "expectedEffect", "消除已识别的根因"));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStringList(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return Collections.emptyList();
    }

    // ========== 截断工具 ==========

    private String truncateCode(String code, int maxLen) {
        if (code == null) return "";
        if (code.length() <= maxLen) return code;

        String[] lines = code.split("\n");
        if (lines.length <= 40) return code;

        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < Math.min(20, lines.length); i++) {
            truncated.append(lines[i]).append("\n");
        }
        truncated.append("\n... (中间 ").append(lines.length - 25).append(" 行省略)\n\n");
        for (int i = Math.max(lines.length - 5, 20); i < lines.length; i++) {
            truncated.append(lines[i]).append("\n");
        }
        return truncated.toString();
    }

    // ========== 结果提取 ==========

    private String extractRootCause(Map<String, Object> analysis) {
        Object rc = analysis.get("rootCause");
        if (rc == null) rc = analysis.get("rootCauseDetail");
        return rc != null ? String.valueOf(rc) : "未分析出根因";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractFixSuggestions(Map<String, Object> analysis) {
        Object fixes = analysis.get("fixSuggestions");
        if (fixes instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> suggestion = new LinkedHashMap<>();
                    map.forEach((k, v) -> suggestion.put(String.valueOf(k), v));
                    result.add(suggestion);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private String extractConfidence(Map<String, Object> analysis) {
        Object conf = analysis.get("confidence");
        return conf != null ? String.valueOf(conf) : "unknown";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractCausalChain(Map<String, Object> analysis) {
        Object chain = analysis.get("causalChain");
        if (chain instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> step = new LinkedHashMap<>();
                    map.forEach((k, v) -> step.put(String.valueOf(k), v));
                    result.add(step);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMultiFactorAnalysis(Map<String, Object> analysis) {
        Object mfa = analysis.get("multiFactorAnalysis");
        if (mfa instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTimeline(Map<String, Object> analysis) {
        Object timeline = analysis.get("timeline");
        if (timeline instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> phase = new LinkedHashMap<>();
                    map.forEach((k, v) -> phase.put(String.valueOf(k), v));
                    result.add(phase);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
