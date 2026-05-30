package com.huawei.hisi.service;

import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志分析异步执行器
 * 负责异步执行日志分析任务
 *
 * Note: Claude SDK backend analysis has been removed.
 * The frontend now uses pty4j to connect directly to Claude CLI.
 * This executor retains the async task lifecycle (status tracking)
 * but no longer performs LLM-based analysis on the backend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogAnalysisExecutor {

    private final RootCauseAnalysisService rootCauseAnalysisService;
    private final LogAnalysisRepository repository;

    /**
     * 异步执行日志分析任务
     *
     * @param reportId 报告 ID
     */
    @Async("analysisTaskExecutor")
    public void executeAnalysis(Long reportId) {
        log.info("开始执行日志分析任务 (reportId={})", reportId);

        try {
            // 1. 更新状态为 processing
            repository.updateStatus(reportId, "processing");
            log.debug("任务状态已更新为 processing (reportId={})", reportId);

            // 2. 获取报告信息
            LogAnalysisReportEntity report = repository.findById(reportId);
            if (report == null) {
                throw new RuntimeException("报告不存在 (reportId=" + reportId + ")");
            }

            // 3. 构建分析请求
            Map<String, Object> analysisRequest = buildAnalysisRequest(report);

            // 4. 执行分析（基础规则分析，LLM 分析已迁移至前端 pty4j）
            log.info("执行基础规则分析 (reportId={})", reportId);
            AnalysisResult result = performAnalysis(analysisRequest);

            // 5. 保存分析结果
            repository.updateAnalysisResult(
                reportId,
                result.getErrorSummary(),
                result.getRootCause(),
                result.getFixSuggestions(),
                result.getCodeSnippets()
            );
            log.info("日志分析完成 (reportId={})", reportId);

        } catch (Exception e) {
            log.error("日志分析失败 (reportId={})", reportId, e);

            // 6. 记录错误信息
            repository.updateError(reportId, e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * 构建分析请求
     */
    private Map<String, Object> buildAnalysisRequest(LogAnalysisReportEntity report) {
        Map<String, Object> request = new HashMap<>();
        request.put("message", report.getLogMessage());
        request.put("stackTrace", report.getLogStackTrace());
        request.put("filteredStackTrace", report.getFilteredStackTrace());
        request.put("errorType", report.getErrorType());
        request.put("traceId", report.getTraceId());
        request.put("serviceName", report.getServiceName());
        return request;
    }

    /**
     * 执行基础规则分析
     * Claude SDK 分析已移除，前端通过 pty4j 直接连接 Claude CLI。
     * 此方法保留基础的错误信息提取，供任务状态追踪使用。
     */
    private AnalysisResult performAnalysis(Map<String, Object> request) {
        String errorType = (String) request.get("errorType");
        String errorMessage = (String) request.get("message");
        String stackTrace = (String) request.get("stackTrace");

        Map<String, Object> errorSummary = new HashMap<>();
        errorSummary.put("errorType", errorType != null ? errorType : "Unknown");
        errorSummary.put("errorMessage", errorMessage);
        errorSummary.put("description", "基础规则分析结果，详细 LLM 分析请使用前端 Claude CLI 终端");

        Map<String, Object> rootCause = new HashMap<>();
        rootCause.put("rootCauseType", errorType != null ? errorType : "Unknown");
        rootCause.put("description", "请通过前端 Claude CLI 终端进行深度根因分析");

        List<Map<String, Object>> fixSuggestions = new ArrayList<>();
        Map<String, Object> suggestion = new HashMap<>();
        suggestion.put("id", 1);
        suggestion.put("suggestion", "请使用前端 Claude CLI 终端进行详细分析和修复建议");
        suggestion.put("priority", "High");
        fixSuggestions.add(suggestion);

        return new AnalysisResult(errorSummary, rootCause, fixSuggestions, List.of());
    }

    /**
     * 分析结果
     */
    public static class AnalysisResult {
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
