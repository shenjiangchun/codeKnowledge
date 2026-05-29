package com.huawei.hisi.service;

import com.huawei.hisi.model.*;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.utils.StackTraceFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 根因分析服务实现
 * 集成堆栈过滤、代码提取和 LLM 分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RootCauseAnalysisServiceImpl implements RootCauseAnalysisService {

    private final ObjectMapper objectMapper;
    private final LogAnalysisRepository repository;

    @Value("${app.project_dir:}")
    private String projectDir;

    // LLM 上下文限制
    private static final int MAX_TOKENS = 4000;
    private static final int CODE_EXTRACT_LINES = 10;

    /**
     * 堆栈过滤器
     */
    private StackTraceFilter createStackTraceFilter() {
        return new StackTraceFilter(projectDir);
    }

    @Override
    public LogAnalyzeResponse analyzeSingleLog(LogAnalyzeRequest request) {
        if (request == null || (request.getMessage() == null && request.getStackTrace() == null)) {
            throw new IllegalArgumentException("请求参数不能为空，需要提供日志消息或堆栈信息");
        }

        try {
            log.info("开始分析日志 (errorType={})", request.getErrorType());

            // 1. 过滤堆栈
            StackTraceFilter filter = createStackTraceFilter();
            List<StackTraceFilter.StackFrame> filteredFrames = filter.filter(request.getStackTrace());
            log.info("堆栈过滤：{} frames", filteredFrames.size());

            // 2. 提取代码片段
            List<Map<String, Object>> codeSnippets = extractCodeSnippets(filteredFrames);

            // 3. 构建分析上下文（带降级策略）
            String codeContext = buildCodeContext(filteredFrames, codeSnippets);

            // 4. 构建 LLM 提示词
            String prompt = buildAnalysisPrompt(request, filteredFrames, codeContext);

            // 5. LLM 分析已移除，返回占位结果
            log.info("LLM service removed - use Claude MCP for root cause analysis");
            String analysisResult = "Use Claude MCP for root cause analysis";

            // 6. 解析 LLM 响应
            AnalysisResult result = parseAnalysisResult(analysisResult);

            // 7. 构建响应
            LogAnalyzeResponse response = buildResponse(request, result, codeSnippets);

            return response;

        } catch (Exception e) {
            log.error("单条日志分析失败", e);
            throw new RuntimeException("分析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 提取代码片段
     */
    private List<Map<String, Object>> extractCodeSnippets(List<StackTraceFilter.StackFrame> frames) {
        List<Map<String, Object>> snippets = new ArrayList<>();

        for (StackTraceFilter.StackFrame frame : frames) {
            if (projectDir == null || projectDir.isEmpty()) {
                continue;
            }

            // 构建文件路径
            String filePath = buildFilePath(frame.getClassName());
            if (!new File(filePath).exists()) {
                continue;
            }

            try {
                List<String> lines = Files.readAllLines(Paths.get(filePath));
                int lineNum = frame.getLineNumber() != null ? frame.getLineNumber() : 0;

                // 提取上下文代码（前后各 5 行）
                int start = Math.max(0, lineNum - CODE_EXTRACT_LINES / 2);
                int end = Math.min(lines.size(), lineNum + CODE_EXTRACT_LINES / 2);

                List<String> codeLines = lines.subList(start, end);
                String code = String.join("\n", codeLines);

                Map<String, Object> snippet = new HashMap<>();
                snippet.put("className", frame.getClassName());
                snippet.put("methodName", frame.getMethodName());
                snippet.put("lineNumber", lineNum);
                snippet.put("code", code);

                snippets.add(snippet);

            } catch (IOException e) {
                log.warn("读取代码文件失败 ({}): {}", filePath, e.getMessage());
            }
        }

        return snippets;
    }

    /**
     * 构建代码上下文（带降级策略）
     */
    private String buildCodeContext(List<StackTraceFilter.StackFrame> frames,
                                    List<Map<String, Object>> snippets) {
        StringBuilder context = new StringBuilder();
        context.append("## 相关代码片段\n\n");

        for (Map<String, Object> snippet : snippets) {
            String className = (String) snippet.get("className");
            String methodName = (String) snippet.get("methodName");
            Integer lineNum = (Integer) snippet.get("lineNumber");
            String code = (String) snippet.get("code");

            context.append(String.format("### %s.%s (line %d)\n", className, methodName, lineNum));
            context.append("```\n").append(code).append("\n```\n\n");

            // 检查 token 数量，如果超过限制则停止
            if (estimateTokens(context.toString()) > MAX_TOKENS * 0.7) {
                context.append("... (代码片段被截断)\n");
                break;
            }
        }

        return context.toString();
    }

    /**
     * 估算 token 数量
     */
    private int estimateTokens(String text) {
        // 简单估算：每个单词约 1.3 个 token
        return (int) (text.split("\\s+").length * 1.3);
    }

    /**
     * 构建分析提示词
     */
    private String buildAnalysisPrompt(LogAnalyzeRequest request,
                                       List<StackTraceFilter.StackFrame> frames,
                                       String codeContext) {
        // 构建过滤后的堆栈字符串
        StringBuilder stackTrace = new StringBuilder();
        for (StackTraceFilter.StackFrame frame : frames) {
            stackTrace.append("  at ").append(frame.getLocation())
                      .append("(").append(frame.getFileName()).append(":")
                      .append(frame.getLineNumber()).append(")\n");
        }

        return String.format("""
            你是一个专业的 Java 代码分析和故障诊断专家。请分析以下错误日志，提供详细的根因分析和修复建议。

            ## 错误消息
            %s

            ## 错误类型
            %s

            ## 堆栈信息（已过滤，仅业务代码）
            %s

            %s

            请按以下 JSON 格式输出分析结果（不要包含 markdown 代码块标记）：
            {
              "errorSummary": {
                "errorType": "错误类型",
                "errorMessage": "简短错误描述",
                "errorLocation": "类名。方法名",
                "description": "错误详细说明"
              },
              "rootCause": {
                "rootCauseType": "根因类型",
                "description": "根因详细描述",
                "impact": "影响范围",
                "probability": "High/Medium/Low"
              },
              "fixSuggestions": [
                {
                  "priority": "HIGH/MEDIUM/LOW",
                  "suggestionType": "CodeFix/ConfigChange/Architecture",
                  "description": "建议描述",
                  "steps": ["步骤 1", "步骤 2"],
                  "codeLocation": "相关代码位置"
                }
              ],
              "codeSnippets": [
                {
                  "className": "类名",
                  "methodName": "方法名",
                  "lineNumber": 行号，
                  "issue": "问题描述"
                }
              ]
            }
            """,
            request.getMessage(),
            request.getErrorType() != null ? request.getErrorType() : "Unknown",
            stackTrace.toString(),
            codeContext
        );
    }

    /**
     * 解析 LLM 响应
     */
    private AnalysisResult parseAnalysisResult(String response) {
        try {
            // 清理响应（移除可能的 markdown 标记）
            String cleaned = response.replaceAll("^```json\\s*|\\s*```$", "")
                                     .trim();

            Map<String, Object> result = objectMapper.readValue(cleaned, Map.class);

            return new AnalysisResult(
                (Map<String, Object>) result.get("errorSummary"),
                (Map<String, Object>) result.get("rootCause"),
                (List<Map<String, Object>>) result.get("fixSuggestions"),
                (List<Map<String, Object>>) result.get("codeSnippets")
            );

        } catch (IOException e) {
            log.warn("解析 LLM 响应失败，使用默认结果", e);
            return createDefaultAnalysisResult();
        }
    }

    /**
     * 创建默认分析结果
     */
    private AnalysisResult createDefaultAnalysisResult() {
        Map<String, Object> errorSummary = new HashMap<>();
        errorSummary.put("errorType", "Unknown");
        errorSummary.put("errorMessage", "分析失败");
        errorSummary.put("errorLocation", "Unknown");
        errorSummary.put("description", "LLM 响应解析失败，使用默认结果");

        Map<String, Object> rootCause = new HashMap<>();
        rootCause.put("rootCauseType", "Unknown");
        rootCause.put("description", "无法确定根因");
        rootCause.put("impact", "Unknown");
        rootCause.put("probability", "Medium");

        return new AnalysisResult(errorSummary, rootCause, new ArrayList<>(), new ArrayList<>());
    }

    /**
     * 构建响应
     */
    private LogAnalyzeResponse buildResponse(LogAnalyzeRequest request,
                                             AnalysisResult result,
                                             List<Map<String, Object>> codeSnippets) {
        LogAnalyzeResponse response = new LogAnalyzeResponse();
        response.setAnalyzeId(UUID.randomUUID().toString());
        response.setStatus("completed");

        // 错误摘要
        LogAnalyzeResponse.ErrorSummary errorSummary = new LogAnalyzeResponse.ErrorSummary();
        Map<String, Object> es = result.getErrorSummary();
        errorSummary.setErrorType(es != null ? (String) es.get("errorType") : "Unknown");
        errorSummary.setErrorMessage(es != null ? (String) es.get("errorMessage") : request.getMessage());
        errorSummary.setErrorLocation(es != null ? (String) es.get("errorLocation") : "Unknown");
        errorSummary.setDescription(es != null ? (String) es.get("description") : "分析完成");
        response.setErrorSummary(errorSummary);

        // 根因分析
        Map<String, Object> rc = result.getRootCause();
        if (rc != null) {
            LogAnalyzeResponse.RootCauseAnalysis rootCause = new LogAnalyzeResponse.RootCauseAnalysis();
            rootCause.setRootCauseType((String) rc.get("rootCauseType"));
            rootCause.setDescription((String) rc.get("description"));
            rootCause.setImpact((String) rc.get("impact"));
            rootCause.setProbability((String) rc.get("probability"));
            response.setRootCause(rootCause);
        }

        // 修复建议
        List<LogAnalyzeResponse.FixSuggestion> suggestions = new ArrayList<>();
        if (result.getFixSuggestions() != null) {
            for (Map<String, Object> fs : result.getFixSuggestions()) {
                LogAnalyzeResponse.FixSuggestion suggestion = new LogAnalyzeResponse.FixSuggestion();
                suggestion.setPriority((String) fs.get("priority"));
                suggestion.setSuggestionType((String) fs.get("suggestionType"));
                suggestion.setDescription((String) fs.get("description"));
                suggestion.setSteps((List<String>) fs.get("steps"));
                suggestion.setCodeLocation((String) fs.get("codeLocation"));
                suggestions.add(suggestion);
            }
        }
        response.setFixSuggestions(suggestions);

        // 代码片段
        List<LogAnalyzeResponse.CodeSnippet> snippets = new ArrayList<>();
        for (Map<String, Object> cs : codeSnippets) {
            LogAnalyzeResponse.CodeSnippet snippet = new LogAnalyzeResponse.CodeSnippet();
            snippet.setClassName((String) cs.get("className"));
            snippet.setMethodName((String) cs.get("methodName"));
            snippet.setLineNumber((Integer) cs.get("lineNumber"));
            snippet.setCode((String) cs.get("code"));
            snippet.setIssue("待分析");
            snippets.add(snippet);
        }
        response.setCodeSnippets(snippets);

        return response;
    }

    /**
     * 构建文件路径
     */
    private String buildFilePath(String className) {
        String path = className.replace('.', '/') + ".java";
        return new File(projectDir, path).getAbsolutePath();
    }

    @Override
    public LogAnalysisReport analyze(List<LogEntry> logs) {
        if (logs == null || logs.isEmpty()) {
            throw new IllegalArgumentException("日志列表不能为空");
        }

        try {
            // 1. 提取错误信息
            String errorSummary = extractErrorSummary(logs);
            String errorStack = extractErrorStack(logs);

            // 2. 构建 LLM 提示
            String prompt = buildLegacyAnalysisPrompt(errorSummary, errorStack);

            // 3. LLM 分析已移除，返回占位结果
            String analysisResult = "Use Claude MCP for root cause analysis";

            // 4. 构建报告
            LogAnalysisReport report = new LogAnalysisReport();
            report.setReportNo(generateReportNo());
            report.setQueryTime(LocalDateTime.now());
            report.setLogSummary(errorSummary);
            report.setErrorStack(errorStack);
            report.setRootCause(analysisResult);
            report.setStatus("completed");
            report.setCreatedAt(LocalDateTime.now());

            return report;
        } catch (Exception e) {
            log.error("根因分析失败", e);
            throw new RuntimeException("根因分析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public LogAnalysisReport getReport(Long reportId) {
        LogAnalysisReportEntity entity = repository.findById(reportId);
        if (entity == null) {
            return null;
        }

        // 转换为 LogAnalysisReport
        LogAnalysisReport report = new LogAnalysisReport();
        report.setId(entity.getId());
        report.setStatus(entity.getStatus());
        report.setCreatedAt(entity.getCreatedAt());
        report.setQueryTime(entity.getCreatedAt());

        // logSummary may contain root cause analysis result
        if (entity.getLogSummary() != null) {
            report.setLogSummary(entity.getLogSummary());
        }

        return report;
    }

    // Legacy methods for backward compatibility

    private String extractErrorSummary(List<LogEntry> logs) {
        StringBuilder sb = new StringBuilder();
        sb.append("共查询到 ").append(logs.size()).append(" 条日志：\n");
        for (int i = 0; i < Math.min(logs.size(), 10); i++) {
            LogEntry log = logs.get(i);
            sb.append(i + 1).append(". [").append(log.getLevel())
              .append("] ").append(log.getTimestamp())
              .append(" - ").append(log.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private String extractErrorStack(List<LogEntry> logs) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry log : logs) {
            if (log.getStackTrace() != null && !log.getStackTrace().isEmpty()) {
                sb.append(log.getStackTrace()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String buildLegacyAnalysisPrompt(String errorSummary, String errorStack) {
        return String.format("""
            请分析以下日志，定位问题根因并提供修复建议：

            ## 日志摘要
            %s

            ## 错误堆栈
            %s

            请输出：
            1. 问题根因分析
            2. 可能的原因
            3. 建议的修复方案
            """, errorSummary, errorStack);
    }

    private String generateReportNo() {
        String timestamp = LocalDateTime.now().toString()
            .replace(":", "-")
            .replace(":", "-")
            .split("\\.")[0];
        return "RPT-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    /**
     * 分析结果内部类
     */
    private static class AnalysisResult {
        private final Map<String, Object> errorSummary;
        private final Map<String, Object> rootCause;
        private final List<Map<String, Object>> fixSuggestions;
        private final List<Map<String, Object>> codeSnippets;

        public AnalysisResult(Map<String, Object> errorSummary, Map<String, Object> rootCause,
                             List<Map<String, Object>> fixSuggestions,
                             List<Map<String, Object>> codeSnippets) {
            this.errorSummary = errorSummary;
            this.rootCause = rootCause;
            this.fixSuggestions = fixSuggestions;
            this.codeSnippets = codeSnippets;
        }

        public Map<String, Object> getErrorSummary() { return errorSummary; }
        public Map<String, Object> getRootCause() { return rootCause; }
        public List<Map<String, Object>> getFixSuggestions() { return fixSuggestions; }
        public List<Map<String, Object>> getCodeSnippets() { return codeSnippets; }
    }
}