package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 报告导出服务
 * 支持将日志分析报告导出为 Markdown 格式
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private final LogAnalysisRepository logAnalysisRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final AgentEventRepository agentEventRepository;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 将日志分析报告导出为 Markdown 格式
     *
     * @param reportId 报告 ID
     * @return Markdown 格式的报告内容
     * @throws IllegalArgumentException 如果报告不存在
     */
    public String exportLogReportAsMd(Long reportId) {
        LogAnalysisReportEntity report = logAnalysisRepository.findById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("报告不存在: " + reportId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 日志分析报告 #").append(reportId).append("\n\n");

        // 基本信息
        sb.append("## 基本信息\n\n");
        sb.append("| 字段 | 值 |\n");
        sb.append("|------|----|\n");
        sb.append("| 报告编号 | ").append(report.getReportNo() != null ? report.getReportNo() : reportId).append(" |\n");
        sb.append("| 状态 | ").append(report.getStatus() != null ? report.getStatus() : "-").append(" |\n");
        sb.append("| 创建时间 | ").append(report.getCreatedAt() != null ? report.getCreatedAt().format(TIME_FMT) : "-").append(" |\n");
        sb.append("| 更新时间 | ").append(report.getUpdatedAt() != null ? report.getUpdatedAt().format(TIME_FMT) : "-").append(" |\n\n");

        // 错误摘要
        sb.append("## 错误摘要\n\n");
        if (report.getLogSummary() != null && !report.getLogSummary().isBlank()) {
            sb.append(report.getLogSummary()).append("\n\n");
        } else {
            sb.append("暂无\n\n");
        }

        // 根因分析
        sb.append("## 根本原因\n\n");
        String rootCause = extractRootCause(report);
        if (rootCause != null && !rootCause.isBlank()) {
            sb.append(rootCause).append("\n\n");
        } else {
            sb.append("暂无\n\n");
        }

        // 修复建议
        sb.append("## 修复建议\n\n");
        String fixSuggestions = extractFixSuggestions(report);
        if (fixSuggestions != null && !fixSuggestions.isBlank()) {
            sb.append(fixSuggestions).append("\n\n");
        } else {
            sb.append("暂无\n\n");
        }

        // 错误堆栈
        String stackTrace = report.getFilteredStackTrace() != null && !report.getFilteredStackTrace().isBlank()
            ? report.getFilteredStackTrace()
            : report.getLogStackTrace();
        if (stackTrace != null && !stackTrace.isBlank()) {
            sb.append("## 错误堆栈\n\n");
            sb.append("```\n").append(stackTrace).append("\n```\n\n");
        }

        // 相关代码
        List<Map<String, Object>> codeSnippets = report.getCodeSnippets();
        if (codeSnippets != null && !codeSnippets.isEmpty()) {
            sb.append("## 相关代码\n\n");
            for (Map<String, Object> code : codeSnippets) {
                String className = (String) code.get("className");
                String methodName = (String) code.get("methodName");
                String codeSnippet = (String) code.get("codeSnippet");

                sb.append("### ").append(className != null ? className : "Unknown");
                sb.append(".").append(methodName != null ? methodName : "unknown").append("\n\n");
                if (codeSnippet != null && !codeSnippet.isBlank()) {
                    sb.append("```java\n").append(codeSnippet).append("\n```\n\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 将 RAM 需求分析会话导出为 Markdown 格式
     * 优化：提取节点关键结果，不输出原始 JSON
     *
     * @param sessionId 前端 UUID 会话标识
     * @return Markdown 格式的会话内容
     * @throws IllegalArgumentException 如果会话不存在
     */
    public String exportRamSessionAsMd(String sessionId) {
        Optional<AgentSession> sessionOpt = agentSessionRepository.findByUuid(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        AgentSession session = sessionOpt.get();
        long backendId = session.getId();
        List<AgentEvent> events = agentEventRepository.findBySessionId(backendId);
        ObjectMapper objectMapper = new ObjectMapper();

        StringBuilder sb = new StringBuilder();
        sb.append("# RAM 需求分析会话 #").append(sessionId).append("\n\n");

        // 基本信息
        sb.append("## 基本信息\n\n");
        sb.append("| 字段 | 值 |\n");
        sb.append("|------|----|\n");
        sb.append("| 会话ID | ").append(sessionId).append(" |\n");
        sb.append("| 用户 | ").append(session.getUserId() != null ? session.getUserId() : "-").append(" |\n");
        sb.append("| 状态 | ").append(session.getStatus() != null ? session.getStatus().name() : "-").append(" |\n");
        sb.append("| 类型 | ").append(session.getSessionType() != null ? session.getSessionType().name() : "DEMAND").append(" |\n");
        sb.append("| 当前节点 | ").append(session.getCurrentNode() != null ? session.getCurrentNode() : "-").append(" |\n");
        sb.append("| 创建时间 | ").append(formatEpoch(session.getCreatedAt())).append(" |\n");
        sb.append("| 更新时间 | ").append(formatEpoch(session.getUpdatedAt())).append(" |\n\n");

        // 需求描述
        sb.append("## 需求描述\n\n");
        if (session.getIntent() != null && !session.getIntent().isBlank()) {
            sb.append(session.getIntent()).append("\n\n");
        } else {
            sb.append("暂无\n\n");
        }

        // 项目路径
        sb.append("## 项目路径\n\n");
        if (session.getProjectPaths() != null && !session.getProjectPaths().isBlank()) {
            sb.append(session.getProjectPaths()).append("\n\n");
        } else {
            sb.append("暂无\n\n");
        }

        // 节点结果汇总（提取 CHECKPOINT 事件）
        sb.append("## 分析结果\n\n");
        boolean hasCheckpoint = false;
        for (AgentEvent event : events) {
            if (event.getType() == EventType.CHECKPOINT && event.getPayload() != null) {
                hasCheckpoint = true;
                try {
                    Map<String, Object> checkpoint = objectMapper.readValue(event.getPayload(), Map.class);
                    String nodeName = (String) checkpoint.get("nodeName");
                    if (nodeName == null) nodeName = "unknown";

                    sb.append("### ").append(nodeName).append("\n\n");

                    // 提取关键输出字段
                    extractAndFormatNodeOutput(sb, checkpoint, nodeName);
                } catch (Exception e) {
                    log.warn("解析 CHECKPOINT payload 失败: {}", e.getMessage());
                    sb.append("（解析失败）\n\n");
                }
            }
        }
        if (!hasCheckpoint) {
            sb.append("暂无分析结果\n\n");
        }

        // 澄清问答汇总
        sb.append("## 澄清问答\n\n");
        boolean hasClarify = false;
        for (AgentEvent event : events) {
            if (event.getType() == EventType.CLARIFY_REQ && event.getPayload() != null) {
                hasClarify = true;
                try {
                    Map<String, Object> clarify = objectMapper.readValue(event.getPayload(), Map.class);
                    Integer roundNo = event.getClarifyRoundNo();
                    sb.append("**澄清轮次 ").append(roundNo != null ? roundNo : 1).append("**\n\n");

                    String question = (String) clarify.get("question");
                    if (question != null && !question.isBlank()) {
                        sb.append("问: ").append(question).append("\n\n");
                    }
                } catch (Exception e) {
                    log.warn("解析 CLARIFY_REQ payload 失败: {}", e.getMessage());
                }
            }
            if (event.getType() == EventType.CLARIFY_RES && event.getPayload() != null) {
                try {
                    Map<String, Object> res = objectMapper.readValue(event.getPayload(), Map.class);
                    String answer = (String) res.get("answer");
                    if (answer != null && !answer.isBlank()) {
                        sb.append("答: ").append(answer).append("\n\n");
                    }
                } catch (Exception e) {
                    log.warn("解析 CLARIFY_RES payload 失败: {}", e.getMessage());
                }
            }
        }
        if (!hasClarify) {
            sb.append("暂无澄清记录\n\n");
        }

        return sb.toString();
    }

    /**
     * 提取并格式化节点输出
     */
    @SuppressWarnings("unchecked")
    private void extractAndFormatNodeOutput(StringBuilder sb, Map<String, Object> checkpoint, String nodeName) {
        // 根据节点类型提取不同的关键字段
        Map<String, Object> output = (Map<String, Object>) checkpoint.get("output");
        if (output == null || output.isEmpty()) {
            sb.append("暂无输出\n\n");
            return;
        }

        switch (nodeName) {
            case "clarify":
                // 澄清节点：提取澄清问题和答案
                formatClarifyOutput(sb, output);
                break;
            case "search":
                // 搜索节点：提取匹配方法列表
                formatSearchOutput(sb, output);
                break;
            case "impact":
                // 影响分析节点：提取影响范围
                formatImpactOutput(sb, output);
                break;
            case "techPlan":
                // 技术方案节点：提取方案内容
                formatTechPlanOutput(sb, output);
                break;
            case "implement":
                // 实现节点：提取代码改动
                formatImplementOutput(sb, output);
                break;
            case "verify":
                // 验证节点：提取验证结果
                formatVerifyOutput(sb, output);
                break;
            case "report":
                // 报告节点：提取最终报告
                formatReportOutput(sb, output);
                break;
            default:
                // 默认：提取主要字段
                formatGenericOutput(sb, output);
        }
    }

    private void formatClarifyOutput(StringBuilder sb, Map<String, Object> output) {
        List<Map<String, Object>> questions = (List<Map<String, Object>>) output.get("questions");
        if (questions != null && !questions.isEmpty()) {
            sb.append("**澄清问题列表:**\n\n");
            for (int i = 0; i < questions.size(); i++) {
                Map<String, Object> q = questions.get(i);
                sb.append((i + 1)).append(". ").append(q.getOrDefault("question", "-")).append("\n");
            }
            sb.append("\n");
        }
        String summary = (String) output.get("summary");
        if (summary != null && !summary.isBlank()) {
            sb.append("**澄清总结:**\n").append(summary).append("\n\n");
        }
    }

    private void formatSearchOutput(StringBuilder sb, Map<String, Object> output) {
        List<Map<String, Object>> methods = (List<Map<String, Object>>) output.get("matchedMethods");
        if (methods != null && !methods.isEmpty()) {
            sb.append("**匹配方法 (共 ").append(methods.size()).append(" 个):**\n\n");
            for (int i = 0; i < Math.min(10, methods.size()); i++) {
                Map<String, Object> m = methods.get(i);
                String className = (String) m.getOrDefault("className", "-");
                String methodName = (String) m.getOrDefault("methodName", "-");
                sb.append("- ").append(className).append(".").append(methodName).append("\n");
            }
            if (methods.size() > 10) {
                sb.append("- ... (共 ").append(methods.size()).append(" 个)\n");
            }
            sb.append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void formatImpactOutput(StringBuilder sb, Map<String, Object> output) {
        Map<String, Object> impact = (Map<String, Object>) output.get("impact");
        if (impact != null) {
            String riskLevel = (String) impact.getOrDefault("riskLevel", "-");
            sb.append("**风险评估:** ").append(riskLevel).append("\n\n");

            List<String> affectedClasses = (List<String>) impact.get("affectedClasses");
            if (affectedClasses != null && !affectedClasses.isEmpty()) {
                sb.append("**受影响类:**\n");
                for (String cls : affectedClasses) {
                    sb.append("- ").append(cls).append("\n");
                }
                sb.append("\n");
            }
        }
        String summary = (String) output.get("summary");
        if (summary != null && !summary.isBlank()) {
            sb.append("**影响分析:**\n").append(summary).append("\n\n");
        }
    }

    private void formatTechPlanOutput(StringBuilder sb, Map<String, Object> output) {
        String plan = (String) output.get("techPlan");
        if (plan != null && !plan.isBlank()) {
            sb.append("**技术方案:**\n\n").append(plan).append("\n\n");
        }
        List<String> steps = (List<String>) output.get("steps");
        if (steps != null && !steps.isEmpty()) {
            sb.append("**实现步骤:**\n\n");
            for (int i = 0; i < steps.size(); i++) {
                sb.append((i + 1)).append(". ").append(steps.get(i)).append("\n");
            }
            sb.append("\n");
        }
    }

    private void formatImplementOutput(StringBuilder sb, Map<String, Object> output) {
        String codeChanges = (String) output.get("codeChanges");
        if (codeChanges != null && !codeChanges.isBlank()) {
            sb.append("**代码改动:**\n\n```java\n").append(codeChanges).append("\n```\n\n");
        }
        List<String> files = (List<String>) output.get("modifiedFiles");
        if (files != null && !files.isEmpty()) {
            sb.append("**修改文件:**\n");
            for (String f : files) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("\n");
        }
    }

    private void formatVerifyOutput(StringBuilder sb, Map<String, Object> output) {
        String result = (String) output.getOrDefault("result", "-");
        sb.append("**验证结果:** ").append(result).append("\n\n");
        List<String> tests = (List<String>) output.get("testCases");
        if (tests != null && !tests.isEmpty()) {
            sb.append("**测试用例:**\n");
            for (String t : tests) {
                sb.append("- ").append(t).append("\n");
            }
            sb.append("\n");
        }
    }

    private void formatReportOutput(StringBuilder sb, Map<String, Object> output) {
        String summary = (String) output.get("summary");
        if (summary != null && !summary.isBlank()) {
            sb.append("**分析总结:**\n\n").append(summary).append("\n\n");
        }
        String recommendation = (String) output.get("recommendation");
        if (recommendation != null && !recommendation.isBlank()) {
            sb.append("**建议:**\n\n").append(recommendation).append("\n\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void formatGenericOutput(StringBuilder sb, Map<String, Object> output) {
        // 提取常见字段
        String summary = (String) output.get("summary");
        if (summary != null && !summary.isBlank()) {
            sb.append("**摘要:**\n").append(summary).append("\n\n");
        }
        String result = (String) output.get("result");
        if (result != null && !result.isBlank()) {
            sb.append("**结果:**\n").append(result).append("\n\n");
        }
        List<String> items = (List<String>) output.get("items");
        if (items != null && !items.isEmpty()) {
            sb.append("**列表:**\n");
            for (String item : items) {
                sb.append("- ").append(item).append("\n");
            }
            sb.append("\n");
        }
    }

    /**
     * 格式化 epoch 秒为可读时间字符串
     */
    private String formatEpoch(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "-";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault())
                .format(TIME_FMT);
    }

    /**
     * 提取根因分析内容
     */
    private String extractRootCause(LogAnalysisReportEntity report) {
        // 优先使用 text 字段
        if (report.getRootCauseText() != null && !report.getRootCauseText().isBlank()) {
            return report.getRootCauseText();
        }
        // 否则从 Map 中提取
        Map<String, Object> rootCauseMap = report.getRootCause();
        if (rootCauseMap != null && !rootCauseMap.isEmpty()) {
            return formatMapAsMarkdown(rootCauseMap);
        }
        return null;
    }

    /**
     * 提取修复建议内容
     */
    private String extractFixSuggestions(LogAnalysisReportEntity report) {
        // 优先使用 text 字段
        if (report.getFixSuggestionText() != null && !report.getFixSuggestionText().isBlank()) {
            return report.getFixSuggestionText();
        }
        // 否则从 List 中提取
        List<Map<String, Object>> fixSuggestions = report.getFixSuggestions();
        if (fixSuggestions != null && !fixSuggestions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fixSuggestions.size(); i++) {
                Map<String, Object> suggestion = fixSuggestions.get(i);
                sb.append((i + 1)).append(". ");
                if (suggestion.containsKey("suggestion")) {
                    sb.append(suggestion.get("suggestion"));
                } else {
                    sb.append(formatMapAsMarkdown(suggestion));
                }
                sb.append("\n");
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * 将 Map 格式化为 Markdown 文本
     */
    @SuppressWarnings("unchecked")
    private String formatMapAsMarkdown(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sb.append("- **").append(entry.getKey()).append("**: ");
            Object value = entry.getValue();
            if (value instanceof Map) {
                sb.append("\n");
                for (Map.Entry<String, Object> subEntry : ((Map<String, Object>) value).entrySet()) {
                    sb.append("  - ").append(subEntry.getKey()).append(": ").append(subEntry.getValue()).append("\n");
                }
            } else if (value instanceof List) {
                sb.append("\n");
                for (Object item : (List<?>) value) {
                    sb.append("  - ").append(item).append("\n");
                }
            } else {
                sb.append(value != null ? value : "-").append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 批量导出日志分析报告为 ZIP 文件
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return ZIP 文件字节数组
     * @throws IOException 如果 ZIP 创建失败
     */
    public byte[] exportLogReportsAsZip(LocalDateTime startTime, LocalDateTime endTime) throws IOException {
        List<LogAnalysisReportEntity> reports = logAnalysisRepository.findByCreatedAtBetween(startTime, endTime);

        log.info("批量导出报告: 共 {} 条记录 ({} - {})", reports.size(), startTime, endTime);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (LogAnalysisReportEntity report : reports) {
                Long reportId = report.getReportId();
                if (reportId == null) {
                    log.warn("[Export] Skipping report with null reportId");
                    continue;
                }
                String mdContent = exportLogReportAsMd(reportId);
                String entryName = "report-" + reportId + ".md";
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                zos.write(mdContent.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        return baos.toByteArray();
    }

    /**
     * 将合入分析报告导出为 Markdown 格式
     * 优化：提取节点关键结果，不输出原始 JSON
     *
     * @param sessionId 前端 UUID 会话标识
     * @return Markdown 格式的报告内容
     * @throws IllegalArgumentException 如果会话不存在
     */
    public String exportMergeReportAsMd(String sessionId) {
        Optional<AgentSession> sessionOpt = agentSessionRepository.findByUuid(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        AgentSession session = sessionOpt.get();
        long backendId = session.getId();
        List<AgentEvent> events = agentEventRepository.findBySessionId(backendId);
        ObjectMapper objectMapper = new ObjectMapper();

        StringBuilder sb = new StringBuilder();
        sb.append("# 合入分析报告 #").append(sessionId).append("\n\n");

        // 基本信息
        sb.append("## 基本信息\n\n");
        sb.append("| 字段 | 值 |\n");
        sb.append("|------|----|\n");
        sb.append("| 会话ID | ").append(sessionId).append(" |\n");
        sb.append("| 项目路径 | ").append(session.getProjectPaths() != null ? session.getProjectPaths() : "-").append(" |\n");
        sb.append("| 源分支 | ").append(session.getSourceBranch() != null ? session.getSourceBranch() : "-").append(" |\n");
        sb.append("| 目标分支 | ").append(session.getTargetBranch() != null ? session.getTargetBranch() : "-").append(" |\n");
        sb.append("| 状态 | ").append(session.getStatus() != null ? session.getStatus().name() : "-").append(" |\n");
        sb.append("| 当前节点 | ").append(session.getCurrentNode() != null ? session.getCurrentNode() : "-").append(" |\n");
        sb.append("| 创建时间 | ").append(formatEpoch(session.getCreatedAt())).append(" |\n");
        sb.append("| 更新时间 | ").append(formatEpoch(session.getUpdatedAt())).append(" |\n\n");

        // 分析意图
        sb.append("## 分析意图\n\n");
        if (session.getIntent() != null && !session.getIntent().isBlank()) {
            sb.append(session.getIntent()).append("\n\n");
        } else {
            sb.append("暂无\n\n");
        }

        // 节点结果汇总（提取 CHECKPOINT 事件）
        sb.append("## 分析结果\n\n");
        boolean hasCheckpoint = false;
        for (AgentEvent event : events) {
            if (event.getType() == EventType.CHECKPOINT && event.getPayload() != null) {
                hasCheckpoint = true;
                try {
                    Map<String, Object> checkpoint = objectMapper.readValue(event.getPayload(), Map.class);
                    String nodeName = (String) checkpoint.get("nodeName");
                    if (nodeName == null) nodeName = "unknown";

                    sb.append("### ").append(nodeName).append("\n\n");
                    extractAndFormatNodeOutput(sb, checkpoint, nodeName);
                } catch (Exception e) {
                    log.warn("解析 CHECKPOINT payload 失败: {}", e.getMessage());
                    sb.append("（解析失败）\n\n");
                }
            }
        }
        if (!hasCheckpoint) {
            sb.append("暂无分析结果\n\n");
        }

        // 澄清问答汇总
        sb.append("## 澄清问答\n\n");
        boolean hasClarify = false;
        for (AgentEvent event : events) {
            if (event.getType() == EventType.CLARIFY_REQ && event.getPayload() != null) {
                hasClarify = true;
                try {
                    Map<String, Object> clarify = objectMapper.readValue(event.getPayload(), Map.class);
                    Integer roundNo = event.getClarifyRoundNo();
                    sb.append("**澄清轮次 ").append(roundNo != null ? roundNo : 1).append("**\n\n");
                    String question = (String) clarify.get("question");
                    if (question != null && !question.isBlank()) {
                        sb.append("问: ").append(question).append("\n\n");
                    }
                } catch (Exception e) {
                    log.warn("解析 CLARIFY_REQ payload 失败: {}", e.getMessage());
                }
            }
            if (event.getType() == EventType.CLARIFY_RES && event.getPayload() != null) {
                try {
                    Map<String, Object> res = objectMapper.readValue(event.getPayload(), Map.class);
                    String answer = (String) res.get("answer");
                    if (answer != null && !answer.isBlank()) {
                        sb.append("答: ").append(answer).append("\n\n");
                    }
                } catch (Exception e) {
                    log.warn("解析 CLARIFY_RES payload 失败: {}", e.getMessage());
                }
            }
        }
        if (!hasClarify) {
            sb.append("暂无澄清记录\n\n");
        }

        return sb.toString();
    }
}
