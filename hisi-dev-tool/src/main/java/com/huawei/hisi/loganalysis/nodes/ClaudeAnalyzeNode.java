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
 * Input: { parsedError, codeBodies, callChains, entryPoints }
 * Output: { rootCauseAnalysis, fixSuggestions }
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

        // Check Claude availability
        if (!claudeClient.isAvailable()) {
            log.warn("[ClaudeAnalyzeNode] Claude API 未配置，返回基础分析");
            return fallbackAnalysis(input);
        }

        Map<String, Object> parsedError = (Map<String, Object>) input.get("parsedError");
        List<MethodBodyInfo> codeBodies = (List<MethodBodyInfo>) input.get("codeBodies");
        List<Map<String, Object>> callChains = (List<Map<String, Object>>) input.get("callChains");
        List<?> entryPoints = (List<?>) input.get("entryPoints");

        Map<String, Object> output = new LinkedHashMap<>(input);

        // Build the analysis prompt
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(parsedError, codeBodies, callChains, entryPoints);

        log.info("[ClaudeAnalyzeNode] 发送分析请求 (prompt length: {})", userPrompt.length());

        try {
            SendOptions opts = new SendOptions(
                    claudeClient.defaultModel(),
                    4096,
                    0.3,
                    null);

            Map<String, Object> analysis = claudeClient.callJson(systemPrompt, userPrompt, opts);

            log.info("[ClaudeAnalyzeNode] Claude 分析完成: keys={}", analysis.keySet());

            output.put("rootCauseAnalysis", extractRootCause(analysis));
            output.put("fixSuggestions", extractFixSuggestions(analysis));
            output.put("analysisConfidence", extractConfidence(analysis));
            output.put("rawAnalysis", analysis);

        } catch (Exception e) {
            log.error("[ClaudeAnalyzeNode] Claude 分析失败: {}", e.getMessage());
            output.put("rootCauseAnalysis", "分析失败: " + e.getMessage());
            output.put("fixSuggestions", Collections.emptyList());
            output.put("analysisError", e.getMessage());
        }

        return output;
    }

    /**
     * Fallback analysis when Claude API is not available.
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
        output.put("fixSuggestions", Collections.singletonList(Map.of("suggestion", "请手动分析代码并检查错误位置", "priority", "medium")));
        output.put("analysisConfidence", "low");

        return output;
    }

    private String buildSystemPrompt() {
        return """
你是日志根因分析专家。你需要根据错误日志和相关的代码上下文，分析错误的根本原因并提供修复建议。

分析时请关注:
1. 错误类型和根因异常的含义
2. 关键堆栈帧中的代码逻辑
3. 调用链和入口点信息
4. 可能的业务逻辑问题

输出格式要求:
返回一个 JSON 对象，包含以下字段:
{
  "rootCause": "一句话描述根本原因",
  "rootCauseDetail": "详细分析，包括原因推断过程",
  "confidence": "high/medium/low",
  "fixSuggestions": [
    {
      "suggestion": "修复建议描述",
      "priority": "high/medium/low",
      "affectedCode": "涉及的代码位置"
    }
  ],
  "relatedCode": ["相关的代码文件和方法"]
}
""";
    }

    private String buildUserPrompt(Map<String, Object> parsedError,
                                   List<MethodBodyInfo> codeBodies,
                                   List<Map<String, Object>> callChains,
                                   List<?> entryPoints) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 错误日志信息\n\n");

        if (parsedError != null) {
            sb.append("错误类型: ").append(parsedError.get("errorType")).append("\n");
            sb.append("根因异常: ").append(parsedError.get("rootCauseException")).append("\n");
            sb.append("\n错误消息:\n").append(parsedError.get("fullMessage")).append("\n");
            sb.append("\n堆栈跟踪:\n").append(parsedError.get("stackTrace")).append("\n");
        }

        sb.append("\n## 代码上下文\n\n");

        if (codeBodies != null && !codeBodies.isEmpty()) {
            sb.append("找到 ").append(codeBodies.size()).append(" 个相关方法:\n\n");
            for (MethodBodyInfo info : codeBodies.stream().limit(20).collect(Collectors.toList())) {
                sb.append("### ").append(info.className()).append("#").append(info.methodName()).append("\n");
                sb.append("文件: ").append(info.filePath()).append("\n");
                if (info.description() != null && !info.description().isBlank()) {
                    sb.append("描述: ").append(info.description()).append("\n");
                }
                if (info.methodBody() != null && !info.methodBody().isBlank()) {
                    sb.append("\n代码:\n```java\n")
                            .append(truncateCode(info.methodBody(), 500))
                            .append("\n```\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("未找到相关代码上下文。\n");
        }

        sb.append("\n## 调用链信息\n\n");

        if (callChains != null && !callChains.isEmpty()) {
            sb.append("分析了 ").append(callChains.size()).append(" 个调用链:\n\n");
            for (Map<String, Object> chain : callChains.stream().limit(5).collect(Collectors.toList())) {
                sb.append("- ").append(chain.get("className")).append("#").append(chain.get("methodName")).append("\n");
                List<Map<String, Object>> callees = (List<Map<String, Object>>) chain.get("calleesTree");
                if (callees != null && !callees.isEmpty()) {
                    sb.append("  调用了 ").append(callees.size()).append(" 个下游方法\n");
                }
            }
        }

        sb.append("\n## 入口点信息\n\n");

        if (entryPoints != null && !entryPoints.isEmpty()) {
            sb.append("找到 ").append(entryPoints.size()).append(" 个可能的入口点。\n");
        } else {
            sb.append("未找到明确的入口点。\n");
        }

        sb.append("\n请分析以上信息，给出根因分析和修复建议。\n");

        return sb.toString();
    }

    private String truncateCode(String code, int maxLen) {
        if (code == null) return "";
        if (code.length() <= maxLen) return code;
        return code.substring(0, maxLen) + "\n... (truncated)";
    }

    private String extractRootCause(Map<String, Object> analysis) {
        Object rc = analysis.get("rootCause");
        if (rc == null) rc = analysis.get("rootCauseDetail");
        return rc != null ? String.valueOf(rc) : "未分析出根因";
    }

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
}