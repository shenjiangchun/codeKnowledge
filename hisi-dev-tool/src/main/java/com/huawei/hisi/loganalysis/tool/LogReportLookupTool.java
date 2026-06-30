package com.huawei.hisi.loganalysis.tool;

import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Exposes log analysis report data as a Claude tool for follow-up Q&A.
 * The LLM can look up the original error details, stack traces, and analysis results.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogReportLookupTool {

    private final LogAnalysisRepository repository;

    private static final String TOOL_NAME = "lookup_log_report";
    private static final String TOOL_DESC = """
            查看日志分析报告的详细信息，包括原始错误消息、堆栈跟踪、分析结果。
            传入 reportId 获取完整报告数据。
            """;
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "reportId": {
                  "type": "number",
                  "description": "日志分析报告 ID"
                }
              },
              "required": ["reportId"]
            }
            """;

    public ToolDefinition buildDefinition() {
        return new ToolDefinition(TOOL_NAME, TOOL_DESC, INPUT_SCHEMA);
    }

    public Function<Map<String, Object>, Object> buildHandler() {
        return input -> {
            Object reportIdObj = input.get("reportId");
            long reportId;
            if (reportIdObj instanceof Number n) {
                reportId = n.longValue();
            } else {
                reportId = Long.parseLong(String.valueOf(reportIdObj));
            }

            log.info("[LogReportLookupTool] Looking up reportId={}", reportId);
            var report = repository.findById(reportId);
            if (report == null) {
                return Map.of("error", "Report not found: " + reportId);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reportId", report.getReportId());
            result.put("status", report.getStatus());
            result.put("errorType", report.getErrorType());
            result.put("logMessage", report.getLogMessage());
            result.put("logStackTrace", truncate(report.getLogStackTrace(), 5000));
            result.put("errorSummary", report.getErrorSummary());
            result.put("rootCause", report.getRootCause());
            result.put("fixSuggestions", report.getFixSuggestions());
            result.put("codeSnippets", report.getCodeSnippets());
            result.put("occurrenceCount", report.getOccurrenceCount());
            return result;
        };
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return null;
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "... (truncated)";
    }
}
