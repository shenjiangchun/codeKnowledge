package com.huawei.hisi.service;

import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final FingerprintService fingerprintService;

    /**
     * 提交日志分析请求（带指纹去重）
     *
     * @param message 日志消息
     * @param stackTrace 堆栈跟踪
     * @param userId 用户ID
     * @param queryParams 查询参数
     * @return 报告ID（新日志返回新ID，重复日志返回已有ID）
     */
    public Long submitForAnalysis(String message, String stackTrace, String userId, Map<String, Object> queryParams) {
        // Generate fingerprint
        String fingerprint = fingerprintService.generateFingerprint(message + "\n" + stackTrace);
        log.debug("生成指纹: {}", fingerprint);

        // Check for duplicate
        LogAnalysisReportEntity existing = repository.findByFingerprint(fingerprint);
        if (existing != null) {
            // Duplicate found - increment count and return existing reportId
            repository.incrementOccurrenceCount(existing.getReportId());
            log.info("重复日志检测到 (fingerprint={}), 出现次数增加", fingerprint);
            return existing.getReportId();
        }

        // New log - create report
        return createNewReport(message, stackTrace, userId, queryParams, fingerprint);
    }

    private Long createNewReport(String message, String stackTrace, String userId, Map<String, Object> queryParams, String fingerprint) {
        Long reportId = snowflakeIdGenerator.nextId();
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(reportId);
        report.setReportNo("RPT-" + reportId);
        report.setUserId(userId);
        report.setQueryParams(queryParams);
        report.setLogMessage(message);
        report.setLogStackTrace(stackTrace);
        report.setErrorFingerprint(fingerprint);
        report.setAnalysisStatus("pending");
        report.setOccurrenceCount(1);
        report.setSimilarityThreshold(0.85);
        report.setStatus("pending");

        repository.save(report);
        log.info("新报告已创建 (reportId={}, fingerprint={})", reportId, fingerprint);
        return reportId;
    }

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
     * 重新分析指定报告（重置状态并重新执行分析）
     */
    @Async("analysisTaskExecutor")
    public void reanalyze(Long reportId) {
        log.info("重新执行日志分析任务 (reportId={})", reportId);
        LogAnalysisReportEntity report = repository.findById(reportId);
        if (report == null) {
            log.error("报告不存在，无法重新分析 (reportId={})", reportId);
            return;
        }
        // 重置分析状态
        repository.updateStatus(reportId, "pending");
        repository.updateAnalysisResult(reportId, null, null, null, null);
        executeAnalysis(reportId);
    }

    private static final Pattern EXCEPTION_PATTERN =
        Pattern.compile("([\\w.]+(?:Exception|Error|Throwable))[:\\s]");
    private static final Pattern CAUSED_BY_PATTERN =
        Pattern.compile("Caused by:\\s*([\\w.]+(?:Exception|Error|Throwable))[:\\s]");
    private static final Pattern AT_PATTERN =
        Pattern.compile("^\\s+at\\s+([\\w.$]+)\\.([\\w<>]+)\\([\\w.]+:(\\d+)\\)", Pattern.MULTILINE);
    private static final Pattern API_PATH_PATTERN =
        Pattern.compile("handle\\s+(\\S+)\\s+error");

    /**
     * 执行基础规则分析：从日志中提取错误类型、根因、业务接口、关键栈帧
     */
    private AnalysisResult performAnalysis(Map<String, Object> request) {
        String errorMessage = (String) request.get("message");
        String stackTrace = (String) request.get("stackTrace");
        String fullContent = errorMessage != null ? errorMessage : "";
        if (stackTrace != null && !stackTrace.isEmpty()) {
            fullContent += "\n" + stackTrace;
        }

        // 提取异常类型
        String detectedErrorType = extractErrorType(fullContent);
        // 提取 Caused by（更接近根因）
        String rootException = extractRootCause(fullContent);
        // 提取业务接口路径
        String apiPath = extractApiPath(fullContent);
        // 提取关键非框架栈帧
        List<Map<String, Object>> keyFrames = extractKeyStackFrames(fullContent);

        Map<String, Object> errorSummary = new HashMap<>();
        errorSummary.put("errorType", detectedErrorType);
        errorSummary.put("rootException", rootException);
        errorSummary.put("apiPath", apiPath != null ? apiPath : "");
        errorSummary.put("errorMessage", truncate(errorMessage, 500));

        Map<String, Object> rootCause = new HashMap<>();
        rootCause.put("rootCauseType", rootException != null ? rootException : detectedErrorType);
        if (rootException != null && rootException.contains("Broken pipe")) {
            rootCause.put("description", "客户端在服务端响应完成前断开连接（Broken pipe），通常因前端超时或用户主动取消请求导致，非服务端 Bug");
        } else if (rootException != null) {
            rootCause.put("description", rootException + "，详细分析请使用 Claude CLI 终端");
        } else {
            rootCause.put("description", "未识别到明确异常类型，请使用 Claude CLI 终端深度分析");
        }

        List<Map<String, Object>> fixSuggestions = new ArrayList<>();
        if (rootException != null && rootException.contains("Broken pipe")) {
            fixSuggestions.add(suggestion(1, "检查前端请求超时设置，适当增大 timeout 或改为异步轮询", "Medium"));
            fixSuggestions.add(suggestion(2, "确认 GlobalExceptionHandler 中是否需要对此异常做静默处理，避免刷日志", "Low"));
        } else {
            fixSuggestions.add(suggestion(1, "请使用 Claude CLI 终端进行详细根因分析和修复建议", "High"));
        }

        return new AnalysisResult(errorSummary, rootCause, fixSuggestions, keyFrames);
    }

    private String extractErrorType(String content) {
        Matcher m = EXCEPTION_PATTERN.matcher(content);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last != null ? last.substring(last.lastIndexOf('.') + 1) : "Unknown";
    }

    private String extractRootCause(String content) {
        Matcher m = CAUSED_BY_PATTERN.matcher(content);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if (last != null) {
            return last.substring(last.lastIndexOf('.') + 1);
        }
        // 无 Caused by 时取第一个异常
        m = EXCEPTION_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(1).substring(m.group(1).lastIndexOf('.') + 1);
        }
        return null;
    }

    private String extractApiPath(String content) {
        Matcher m = API_PATH_PATTERN.matcher(content);
        if (m.find()) return m.group(1);
        return null;
    }

    private List<Map<String, Object>> extractKeyStackFrames(String content) {
        List<Map<String, Object>> frames = new ArrayList<>();
        Matcher m = AT_PATTERN.matcher(content);
        int count = 0;
        while (m.find() && count < 5) {
            String className = m.group(1);
            if (isFrameworkClass(className)) continue;
            Map<String, Object> frame = new HashMap<>();
            frame.put("class", className.substring(className.lastIndexOf('.') + 1));
            frame.put("method", m.group(2));
            frame.put("line", Integer.parseInt(m.group(3)));
            frames.add(frame);
            count++;
        }
        return frames;
    }

    private boolean isFrameworkClass(String className) {
        return className.startsWith("java.") || className.startsWith("javax.") ||
               className.startsWith("sun.") || className.startsWith("org.springframework.") ||
               className.startsWith("org.apache.") || className.startsWith("com.fasterxml.jackson.") ||
               className.startsWith("io.netty.") || className.startsWith("reactor.");
    }

    private Map<String, Object> suggestion(int id, String text, String priority) {
        Map<String, Object> s = new HashMap<>();
        s.put("id", id);
        s.put("suggestion", text);
        s.put("priority", priority);
        return s;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
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
