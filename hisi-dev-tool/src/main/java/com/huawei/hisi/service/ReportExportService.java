package com.huawei.hisi.service;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
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

        StringBuilder sb = new StringBuilder();
        sb.append("# RAM 需求分析会话 #").append(sessionId).append("\n\n");

        // 基本信息
        sb.append("## 基本信息\n\n");
        sb.append("| 字段 | 值 |\n");
        sb.append("|------|----|\n");
        sb.append("| 会话ID | ").append(sessionId).append(" |\n");
        sb.append("| 后端ID | ").append(backendId).append(" |\n");
        sb.append("| 用户 | ").append(session.getUserId() != null ? session.getUserId() : "-").append(" |\n");
        sb.append("| 状态 | ").append(session.getStatus() != null ? session.getStatus().name() : "-").append(" |\n");
        sb.append("| 类型 | ").append(session.getSessionType() != null ? session.getSessionType().name() : "DEMAND").append(" |\n");
        sb.append("| 当前节点 | ").append(session.getCurrentNode() != null ? session.getCurrentNode() : "-").append(" |\n");
        sb.append("| 步骤数 | ").append(session.getStepCount()).append(" |\n");
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

        // 事件历史
        sb.append("## 事件历史\n\n");
        if (!events.isEmpty()) {
            for (AgentEvent event : events) {
                sb.append("### ").append(event.getType() != null ? event.getType().name() : "UNKNOWN");
                sb.append(" (seq=").append(event.getSeq()).append(")\n\n");
                sb.append("- 时间: ").append(formatEpoch(event.getCreatedAt())).append("\n");
                if (event.getClarifyRoundNo() != null) {
                    sb.append("- 澄清轮次: ").append(event.getClarifyRoundNo()).append("\n");
                }
                if (event.getPayload() != null && !event.getPayload().isBlank()) {
                    sb.append("- Payload:\n```\n").append(event.getPayload()).append("\n```\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("暂无事件记录\n\n");
        }

        return sb.toString();
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
                String mdContent = exportLogReportAsMd(report.getReportId());
                String entryName = "report-" + report.getReportId() + ".md";
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
        sb.append("| 步骤数 | ").append(session.getStepCount()).append(" |\n");
        sb.append("| 创建时间 | ").append(formatEpoch(session.getCreatedAt())).append(" |\n");
        sb.append("| 更新时间 | ").append(formatEpoch(session.getUpdatedAt())).append(" |\n\n");

        // 分析意图
        sb.append("## 分析意图\n\n");
        if (session.getIntent() != null && !session.getIntent().isBlank()) {
            sb.append(session.getIntent()).append("\n\n");
        } else {
            sb.append("暂无\n\n");
        }

        // 分析过程事件
        sb.append("## 分析过程\n\n");
        if (!events.isEmpty()) {
            for (AgentEvent event : events) {
                if (event.getType() == null) continue;
                
                sb.append("### ").append(event.getType().name());
                sb.append(" (seq=").append(event.getSeq()).append(")\n\n");
                sb.append("- 时间: ").append(formatEpoch(event.getCreatedAt())).append("\n");
                
                if (event.getPayload() != null && !event.getPayload().isBlank()) {
                    sb.append("- Payload:\n```\n").append(event.getPayload()).append("\n```\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("暂无分析记录\n\n");
        }

        return sb.toString();
    }
}
