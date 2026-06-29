package com.huawei.hisi.loganalysis.nodes;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ClaudeAnalyzeNode - Fourth node in log analysis DAG.
 *
 * Uses Claude SDK via RamClaudeJsonClient to analyze the error log
 * with the code context loaded from previous nodes.
 *
 * v2: Enhanced prompts for causal chain / multi-factor / timeline analysis.
 * Input: { parsedError, codeBodies, callChains, entryPoints, entryPointsWithLayers }
 * Output: { rootCauseAnalysis, fixSuggestions, causalChain, multiFactorAnalysis, timeline }
 */
@Slf4j
@Component
public class ClaudeAnalyzeNode implements LogAnalysisDagNode {

    private final RamClaudeJsonClient claudeClient;

    public ClaudeAnalyzeNode(RamClaudeJsonClient claudeClient) {
        this.claudeClient = claudeClient;
    }

    @Override
    public String name() {
        return "ClaudeAnalyzeNode";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[ClaudeAnalyzeNode] 开始 Claude 分析");

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

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(parsedError, codeBodies, callChains, entryPoints, entryPointsWithLayers);

        log.info("[ClaudeAnalyzeNode] 发送分析请求 (prompt length: {})", userPrompt.length());

        try {
            SendOptions opts = new SendOptions(
                    claudeClient.defaultModel(),
                    8000,
                    0.3,
                    null);

            Map<String, Object> analysis = claudeClient.callJson(systemPrompt, userPrompt, opts);

            log.info("[ClaudeAnalyzeNode] Claude 分析完成: keys={}", analysis.keySet());

            output.put("rootCauseAnalysis", extractRootCause(analysis));
            output.put("fixSuggestions", extractFixSuggestions(analysis));
            output.put("analysisConfidence", extractConfidence(analysis));
            output.put("causalChain", extractCausalChain(analysis));
            output.put("multiFactorAnalysis", extractMultiFactorAnalysis(analysis));
            output.put("timeline", extractTimeline(analysis));
            output.put("rawAnalysis", analysis);

        } catch (Exception e) {
            log.error("[ClaudeAnalyzeNode] Claude 分析失败: {}", e.getMessage());
            output.put("rootCauseAnalysis", "分析失败: " + e.getMessage());
            output.put("fixSuggestions", Collections.emptyList());
            output.put("causalChain", Collections.emptyList());
            output.put("multiFactorAnalysis", Map.of());
            output.put("timeline", Collections.emptyList());
            output.put("analysisError", e.getMessage());
        }

        return output;
    }

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

        return output;
    }

    // ========== Prompt 构建 ==========

    private String buildSystemPrompt() {
        return """
你是资深运维与代码根因分析专家。分析日志时必须采用以下推理方法：

## 推理方法（必须遵循）

1. 因果链推理: 从异常表象逐步追溯至根因，形成 A→B→C→D 的因果链路。每一步必须说明机制（为什么 A 导致了 B）。
2. 多因素叠加分析: 识别是否有多个因素共同作用（如锁超时+连接池耗尽+重试放大）。分析各因素之间的交互和叠加效应。
3. 证据交叉引用: 每个推断步骤必须引用具体的堆栈帧（类名#方法名:行号）或代码片段作为依据，不做无证据的推测。
4. 时序重建: 对并发/时序问题，重建事件演进时间线（T1→T2→T3），标注每个阶段的关键事件和持续时间。

## 输出格式（严格遵循）

返回一个 JSON 对象，包含以下字段:

{
  "causalChain": [
    {
      "step": 1,
      "event": "描述这一步发生了什么",
      "mechanism": "为什么这一步导致了下一步",
      "evidence": "引用具体堆栈帧或代码行号作为依据"
    }
  ],
  "multiFactorAnalysis": {
    "primaryFactor": "主要因素描述",
    "contributingFactors": [
      {
        "factor": "辅助因素描述",
        "interaction": "与主因素如何叠加/交互"
      }
    ],
    "cascadeEffect": "因素叠加后的级联效应描述"
  },
  "timeline": [
    {
      "phase": "T1",
      "event": "阶段关键事件",
      "duration": "持续时间估计",
      "evidence": "佐证依据"
    }
  ],
  "rootCause": "一句话描述根本原因",
  "rootCauseDetail": "详细分析，包含推理过程和证据引用",
  "confidence": "high/medium/low",
  "confidenceReason": "为什么给出该置信度",
  "fixSuggestions": [
    {
      "suggestion": "修复建议描述",
      "priority": "P0/P1/P2",
      "affectedCode": "涉及的代码位置（文件:行号）",
      "expectedEffect": "修复后预期效果"
    }
  ],
  "relatedCode": ["相关的代码文件和方法"]
}

注意:
- causalChain 至少 3 步，复杂问题可达 5-7 步
- 如果不是并发/时序问题，timeline 可以只含 1-2 个阶段
- 如果不是多因素叠加，contributingFactors 可为空数组
- fixSuggestions 至少 2 条，必须包含 P0 级别建议
- priority 使用 P0(立即修复)/P1(短期修复)/P2(长期优化)，不要用 high/medium/low
""";
    }

    private String buildUserPrompt(Map<String, Object> parsedError,
                                   List<MethodBodyInfo> codeBodies,
                                   List<Map<String, Object>> callChains,
                                   List<?> entryPoints,
                                   List<Map<String, Object>> entryPointsWithLayers) {
        StringBuilder sb = new StringBuilder();

        // === 错误日志信息 ===
        sb.append("## 错误日志信息\n\n");

        if (parsedError != null) {
            sb.append("错误类型: ").append(parsedError.get("errorType")).append("\n");
            sb.append("根因异常: ").append(parsedError.get("rootCauseException")).append("\n");
            sb.append("\n错误消息:\n").append(parsedError.get("fullMessage")).append("\n");
            sb.append("\n堆栈跟踪:\n").append(parsedError.get("stackTrace")).append("\n");
        }

        // === 代码上下文 ===
        sb.append("\n## 代码上下文\n\n");

        if (codeBodies != null && !codeBodies.isEmpty()) {
            sb.append("找到 ").append(codeBodies.size()).append(" 个相关方法:\n\n");
            for (MethodBodyInfo info : codeBodies.stream().limit(30).collect(Collectors.toList())) {
                sb.append("### ").append(info.className()).append("#").append(info.methodName()).append("\n");
                sb.append("文件: ").append(info.filePath()).append("\n");
                if (info.description() != null && !info.description().isBlank()) {
                    sb.append("描述: ").append(info.description()).append("\n");
                }
                if (info.methodBody() != null && !info.methodBody().isBlank()) {
                    sb.append("\n代码:\n```java\n")
                            .append(truncateCode(info.methodBody(), 2000))
                            .append("\n```\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("未找到相关代码上下文。\n");
        }

        // === 调用链信息（增强：展示完整下游方法名称列表） ===
        sb.append("\n## 调用链信息\n\n");

        if (callChains != null && !callChains.isEmpty()) {
            sb.append("分析了 ").append(callChains.size()).append(" 个调用链:\n\n");
            for (Map<String, Object> chain : callChains.stream().limit(8).collect(Collectors.toList())) {
                sb.append("### ").append(chain.get("className")).append("#").append(chain.get("methodName")).append("\n");

                List<Map<String, Object>> callees = (List<Map<String, Object>>) chain.get("calleesTree");
                if (callees != null && !callees.isEmpty()) {
                    sb.append("下游调用链 (共 ").append(callees.size()).append(" 个方法):\n");
                    for (Map<String, Object> callee : callees.stream().limit(15).collect(Collectors.toList())) {
                        int depth = callee.get("depth") instanceof Integer d ? d : 1;
                        String indent = "  ".repeat(Math.max(0, depth - 1));
                        sb.append(indent).append("- ")
                                .append(callee.get("className")).append("#").append(callee.get("methodName"))
                                .append(" [depth=").append(depth).append("]\n");
                    }
                    if (callees.size() > 15) {
                        sb.append("  ... (还有 ").append(callees.size() - 15).append(" 个下游方法)\n");
                    }
                }
                sb.append("\n");
            }
        } else {
            sb.append("未找到调用链信息。\n");
        }

        // === 入口点信息（增强：展示具体入口点详情） ===
        sb.append("\n## 入口点信息\n\n");

        if (entryPointsWithLayers != null && !entryPointsWithLayers.isEmpty()) {
            sb.append("找到 ").append(entryPointsWithLayers.size()).append(" 个入口点:\n\n");
            for (Map<String, Object> ep : entryPointsWithLayers.stream().limit(10).collect(Collectors.toList())) {
                sb.append("- ").append(ep.get("className")).append("#").append(ep.get("methodName"));
                sb.append(" [类型=").append(ep.get("entryType") != null ? ep.get("entryType") : ep.get("type"));
                sb.append(", 层级=").append(ep.get("layer"));
                sb.append(", 来源=").append(ep.get("source")).append("]\n");
            }
        } else if (entryPoints != null && !entryPoints.isEmpty()) {
            sb.append("找到 ").append(entryPoints.size()).append(" 个入口点。\n");
        } else {
            sb.append("未找到明确的入口点。\n");
        }

        sb.append("\n请严格按照系统提示中的推理方法和输出格式，对以上信息进行深度根因分析。\n");

        return sb.toString();
    }

    // ========== 截断工具 ==========

    private String truncateCode(String code, int maxLen) {
        if (code == null) return "";
        if (code.length() <= maxLen) return code;

        String[] lines = code.split("\n");
        if (lines.length <= 40) {
            return code;
        }

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
