package com.huawei.hisi.loganalysis.nodes;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * ReportNode - Fifth node in log analysis DAG.
 *
 * Formats the analysis results into a structured report and prepares
 * for persistence to database.
 *
 * Input: { parsedError, rootCauseAnalysis (markdown), fixSuggestions, analysisConfidence }
 * Output: { finalReport }
 */
@Slf4j
@Component
public class ReportNode implements LogAnalysisDagNode {

    @Override
    public String name() {
        return "ReportNode";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[ReportNode] 开始生成报告");

        Map<String, Object> parsedError = (Map<String, Object>) input.get("parsedError");
        String rootCauseAnalysis = (String) input.get("rootCauseAnalysis");
        List<Map<String, Object>> fixSuggestions = (List<Map<String, Object>>) input.get("fixSuggestions");
        String confidence = (String) input.get("analysisConfidence");
        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) input.get("keyFrames");
        List<?> entryPoints = (List<?>) input.get("entryPoints");
        String errorFingerprint = (String) input.get("errorFingerprint");
        Object rawAnalysis = input.get("rawAnalysis");

        Map<String, Object> output = new LinkedHashMap<>(input);

        // Build final report
        Map<String, Object> report = new LinkedHashMap<>();

        // Metadata
        report.put("generatedAt", Instant.now().toString());
        report.put("analysisVersion", input.getOrDefault("analysisVersion", "2.0"));
        report.put("errorFingerprint", errorFingerprint);

        // v3: 模式识别结果
        String patternType = (String) input.get("patternType");
        String patternConfidence = (String) input.get("patternConfidence");
        if (patternType != null) {
            report.put("patternType", patternType);
        }
        if (patternConfidence != null) {
            report.put("patternConfidence", patternConfidence);
        }

        // Error summary
        Map<String, Object> errorSummary = new LinkedHashMap<>();
        if (parsedError != null) {
            errorSummary.put("errorType", parsedError.get("errorType"));
            errorSummary.put("rootCauseException", parsedError.get("rootCauseException"));
            errorSummary.put("message", truncate((String) parsedError.get("fullMessage"), 500));
            errorSummary.put("stackTracePreview", truncate((String) parsedError.get("stackTrace"), 1000));
        }
        report.put("errorSummary", errorSummary);

        // Key stack frames
        if (keyFrames != null && !keyFrames.isEmpty()) {
            List<Map<String, Object>> framesForReport = new ArrayList<>();
            for (Map<String, Object> frame : keyFrames.stream().limit(8).toList()) {
                Map<String, Object> frameInfo = new LinkedHashMap<>();
                frameInfo.put("className", frame.get("simpleClassName"));
                frameInfo.put("methodName", frame.get("methodName"));
                frameInfo.put("lineNumber", frame.get("lineNumber"));
                frameInfo.put("fileName", frame.get("fileName"));
                framesForReport.add(frameInfo);
            }
            report.put("keyStackFrames", framesForReport);
        }

        // Root cause analysis (markdown)
        Map<String, Object> rootCauseSection = new LinkedHashMap<>();
        rootCauseSection.put("markdown", rootCauseAnalysis != null ? rootCauseAnalysis : "");
        rootCauseSection.put("confidence", confidence != null ? confidence : "unknown");
        rootCauseSection.put("entryPointsCount", entryPoints != null ? entryPoints.size() : 0);
        report.put("rootCauseAnalysis", rootCauseSection);

        // Fix suggestions
        List<Map<String, Object>> suggestions = new ArrayList<>();
        if (fixSuggestions != null) {
            for (Map<String, Object> fix : fixSuggestions) {
                Map<String, Object> suggestion = new LinkedHashMap<>();
                suggestion.put("description", fix.get("suggestion"));
                suggestion.put("priority", fix.get("priority"));
                suggestion.put("affectedCode", fix.get("affectedCode"));
                // v2: 预期效果
                if (fix.get("expectedEffect") != null) {
                    suggestion.put("expectedEffect", fix.get("expectedEffect"));
                }
                suggestions.add(suggestion);
            }
        }
        report.put("fixSuggestions", suggestions);

        // Full analysis (for debugging)
        if (rawAnalysis != null) {
            report.put("detailedAnalysis", rawAnalysis);
        }

        log.info("[ReportNode] 报告生成完成: suggestions={}, frames={}, markdownLen={}, pattern={}, confidence={}",
                suggestions.size(),
                keyFrames != null ? keyFrames.size() : 0,
                rootCauseAnalysis != null ? rootCauseAnalysis.length() : 0,
                patternType,
                confidence);

        output.put("finalReport", report);

        return output;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
