package com.huawei.hisi.workbench.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.archdiagnosis.model.ArchDiagnosisSnapshot;
import com.huawei.hisi.archdiagnosis.repository.ArchDiagnosisSnapshotRepository;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.repository.AppLogConfigRepository;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.utils.PathUtils;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把日志分析报告与架构诊断产出聚合成工作台待办。
 *
 * <p>按键 upsert（同一问题多次出现只会更新内容与出现次数），用户的状态决定
 * （IGNORED / DONE / RUNNING / WAITING）不会被同步覆盖。聚合只做读表，
 * 图谱影响面在详情接口按需查询，避免批量同步拖慢。
 */
@Slf4j
@Service
public class DebtItemSyncService {

    /** 深度分析结果的新鲜窗口：7 天内优先用它，否则退回规则快照。 */
    static final long ARCH_AI_FRESH_SECONDS = 7L * 24 * 3600;
    private static final String ARCH_REACT_NODE = "arch_react_agent";

    private final LogAnalysisRepository logRepository;
    private final AppLogConfigRepository appLogConfigRepository;
    private final ArchDiagnosisSnapshotRepository snapshotRepository;
    private final AgentEventRepository agentEventRepository;
    private final WorkbenchRepository workbenchRepository;
    private final ObjectMapper objectMapper;

    private final boolean syncEnabled;

    public DebtItemSyncService(LogAnalysisRepository logRepository,
                               AppLogConfigRepository appLogConfigRepository,
                               ArchDiagnosisSnapshotRepository snapshotRepository,
                               AgentEventRepository agentEventRepository,
                               WorkbenchRepository workbenchRepository,
                               ObjectMapper objectMapper,
                               @Value("${workbench.sync.enabled:true}") boolean syncEnabled) {
        this.logRepository = logRepository;
        this.appLogConfigRepository = appLogConfigRepository;
        this.snapshotRepository = snapshotRepository;
        this.agentEventRepository = agentEventRepository;
        this.workbenchRepository = workbenchRepository;
        this.objectMapper = objectMapper;
        this.syncEnabled = syncEnabled;
    }

    @Scheduled(fixedDelayString = "${workbench.sync.interval-ms:600000}", initialDelayString = "15000")
    public void scheduledSync() {
        if (!syncEnabled) {
            return;
        }
        try {
            SyncStats stats = sync();
            if (stats.changed() > 0) {
                log.info("[WorkbenchSync] 同步完成: 日志 {} 条，架构 {} 条，待办表更新 {} 项",
                        stats.logItems(), stats.archItems(), stats.changed());
            }
        } catch (Exception e) {
            // 定时任务失败只记录，不打断服务；手动同步接口会返回错误
            log.error("[WorkbenchSync] 定时同步失败", e);
        }
    }

    public record SyncStats(int logItems, int archItems, int changed) {
        static SyncStats of(int logItems, int archItems, int changed) {
            return new SyncStats(logItems, archItems, changed);
        }
    }

    /** 手动/定时共用：聚合日志与架构产出，返回统计。 */
    public SyncStats sync() {
        List<WorkbenchItem> candidates = new ArrayList<>();
        candidates.addAll(syncLogReports());
        candidates.addAll(syncArchitecture());
        if (candidates.isEmpty()) {
            return SyncStats.of(0, 0, 0);
        }

        Set<String> existing = new LinkedHashSet<>();
        for (WorkbenchItem item : workbenchRepository.listItems()) {
            existing.add(item.getItemKey());
        }
        int created = 0;
        for (WorkbenchItem item : candidates) {
            if (!existing.contains(item.getItemKey())) {
                created++;
            }
            workbenchRepository.upsertItem(item);
            workbenchRepository.upsertDay(item.getItemKey(), today(), item.getOccurrenceCount() == null ? 1 : item.getOccurrenceCount());
        }
        long logItems = candidates.stream().filter(i -> WorkbenchItem.SOURCE_LOG.equals(i.getSource())).count();
        long archItems = candidates.size() - logItems;
        log.info("[WorkbenchSync] 候选 {} 条（新增 {}），日志 {} 架构 {}",
                candidates.size(), created, logItems, archItems);
        return SyncStats.of((int) logItems, (int) archItems, created);
    }

    // ──────────────────────────── 日志报告 ────────────────────────────

    private List<WorkbenchItem> syncLogReports() {
        List<WorkbenchItem> items = new ArrayList<>();
        for (LogAnalysisReportEntity report : logRepository.findByStatus("completed")) {
            String project = projectOfReport(report);
            if (project == null) {
                log.debug("[WorkbenchSync] 报告 {} 没有项目路径，跳过", report.getReportId());
                continue;
            }
            items.add(toLogItem(report, project));
        }
        return items;
    }

    /** 报告归属的项目路径：query_params.projectPath 可能逗号分隔多路径；没有则经 app_id 查配置。 */
    String projectOfReport(LogAnalysisReportEntity report) {
        Map<String, Object> params = report.getQueryParams();
        if (params != null) {
            Object raw = params.get("projectPath");
            if (raw instanceof String s && !s.isBlank()) {
                for (String part : s.split(",")) {
                    String normalized = PathUtils.normalize(part.trim());
                    if (!normalized.isBlank()) {
                        return normalized;
                    }
                }
            }
        }
        String appId = report.getAppId();
        if (appId != null) {
            var config = appLogConfigRepository.findByAppId(appId);
            if (config != null && config.getProjectPath() != null && !config.getProjectPath().isBlank()) {
                return PathUtils.normalize(config.getProjectPath());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private WorkbenchItem toLogItem(LogAnalysisReportEntity report, String project) {
        Map<String, Object> errorSummary = report.getErrorSummary();
        Map<String, Object> rootCause = report.getRootCause();
        List<Map<String, Object>> fixes = report.getFixSuggestions();

        String errorType = report.getErrorType();
        if (errorType == null && errorSummary != null) {
            errorType = asString(errorSummary.get("rootCauseException"));
            if (errorType == null) {
                errorType = asString(errorSummary.get("errorType"));
            }
        }
        if (errorType == null || errorType.isBlank()) {
            errorType = "异常";
        }

        String message = errorSummary == null ? null : asString(errorSummary.get("message"));
        String title = errorType + (message == null || message.isBlank() ? "" : "：" + truncate(message, 80));

        Map<String, Object> topFix = topPriorityFix(fixes);
        String severity = topFix == null ? "P2" : toSeverity(topFix.get("priority"));
        String suggestion = topFix == null ? null : asString(topFix.get("description"));

        String location = locationOf(report);
        String why = rootCause == null ? null : truncate(asString(rootCause.get("markdown")), 500);

        String confidence = null;
        String chainStatus = null;
        Map<String, Object> verification = asMap(rootCause == null ? null : rootCause.get("verification"));
        List<Map<String, Object>> hypotheses = asMapList(verification == null ? null : verification.get("hypotheses"));
        if (!hypotheses.isEmpty()) {
            Map<String, Object> first = hypotheses.get(0);
            confidence = asString(first.get("confidence"));
            chainStatus = asString(first.get("chainStatus"));
            if (why == null && first.get("title") != null) {
                why = asString(first.get("title"));
            }
        }
        if (confidence == null && rootCause != null) {
            confidence = asString(rootCause.get("confidence"));
        }

        List<String> evidence = new ArrayList<>();
        if (rootCause != null && rootCause.get("evidenceAnchors") instanceof List<?> anchors) {
            for (Object anchor : anchors) {
                if (anchor instanceof Map<?, ?> a && a.get("summary") != null) {
                    evidence.add("[" + a.get("id") + "] " + a.get("summary"));
                }
            }
        }
        if (evidence.isEmpty() && location != null) {
            evidence.add("堆栈位置：" + location);
        }

        long firstSeen = report.getCreatedAt() == null ? 0 : report.getCreatedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        long lastSeen = report.getUpdatedAt() == null ? firstSeen : report.getUpdatedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        int occurrences = report.getOccurrenceCount() == null ? 1 : Math.max(1, report.getOccurrenceCount());
        String fingerprint = report.getErrorFingerprint();
        String key = "log:" + project + ":" + (fingerprint == null || fingerprint.isBlank()
                ? String.valueOf(report.getReportId()) : fingerprint);

        return WorkbenchItem.builder()
                .itemKey(key)
                .source(WorkbenchItem.SOURCE_LOG)
                .projectPath(project)
                .title(title)
                .location(location)
                .severity(severity)
                .status(WorkbenchItem.STATUS_NEW)
                .confidence(confidence)
                .chainStatus(chainStatus)
                .why(why)
                .suggestion(suggestion)
                .evidenceJson(toJson(evidence))
                .sourceRefJson(toJson(Map.of("reportId", String.valueOf(report.getReportId()))))
                .occurrenceCount(occurrences)
                .firstSeenAt(firstSeen)
                .lastSeenAt(lastSeen)
                .build();
    }

    private String locationOf(LogAnalysisReportEntity report) {
        List<Map<String, Object>> snippets = report.getCodeSnippets();
        if (snippets != null) {
            for (Map<String, Object> frame : snippets) {
                String cls = asString(frame.get("className"));
                String method = asString(frame.get("methodName"));
                if (cls != null && method != null) {
                    Object line = frame.get("lineNumber");
                    return cls + "#" + method + (line == null ? "" : ":" + line);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> topPriorityFix(List<Map<String, Object>> fixes) {
        if (fixes == null || fixes.isEmpty()) {
            return null;
        }
        Map<String, Object> best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Map<String, Object> fix : fixes) {
            int rank = severityRank(fix.get("priority"));
            if (rank < bestRank) {
                bestRank = rank;
                best = fix;
            }
        }
        return best;
    }

    // ──────────────────────────── 架构产出 ────────────────────────────

    @SuppressWarnings("unchecked")
    private List<WorkbenchItem> syncArchitecture() {
        List<WorkbenchItem> items = new ArrayList<>();
        long freshAfter = System.currentTimeMillis() / 1000L - ARCH_AI_FRESH_SECONDS;

        // 每个项目取 7 天内最新一次深度分析；项目名集合同时供规则快照回退
        Map<String, ArchAnalysis> aiIssuesByProject = new HashMap<>();
        for (AgentEvent event : agentEventRepository.findCheckpointsContaining(ARCH_REACT_NODE, freshAfter)) {
            Map<String, Object> payload = parseJson(event.getPayload());
            if (payload == null || !ARCH_REACT_NODE.equals(asString(payload.get("nodeName")))) {
                continue;
            }
            Map<String, Object> output = asMap(payload.get("output"));
            List<String> projects = projectPathsOf(output);
            if (projects.isEmpty()) {
                continue;
            }
            Map<String, Object> rootCauseResult = asMap(output == null ? null : output.get("rootCauseResult"));
            List<Map<String, Object>> issues = asMapList(rootCauseResult == null ? null : rootCauseResult.get("issues"));
            if (issues.isEmpty()) {
                continue;
            }
            // 每条待办记下来源会话：自动修复的实证阶段要回到这里取证据包
            ArchAnalysis analysis = new ArchAnalysis(event.getSessionId(), issues, rootCauseResult);
            for (String project : projects) {
                // 事件按时间倒序返回：每项目只留最新一次分析
                aiIssuesByProject.putIfAbsent(project, analysis);
            }
        }

        for (Map.Entry<String, ArchAnalysis> entry : aiIssuesByProject.entrySet()) {
            for (Map<String, Object> issue : entry.getValue().issues()) {
                items.add(toArchAiItem(entry.getKey(), issue, entry.getValue()));
            }
        }

        // 没有新鲜深度分析的项目：退回最新规则快照（scope 即项目路径）
        for (String scope : snapshotRepository.listScopes()) {
            if (aiIssuesByProject.containsKey(PathUtils.normalize(scope))) {
                continue;
            }
            ArchDiagnosisSnapshot snapshot = snapshotRepository.findLatestByScope(scope);
            if (snapshot == null || snapshot.getReportJson() == null) {
                continue;
            }
            Map<String, Object> report = parseJson(snapshot.getReportJson());
            List<Map<String, Object>> issues = asMapList(report == null ? null : report.get("issues"));
            for (Map<String, Object> issue : issues) {
                items.add(toArchRuleItem(PathUtils.normalize(scope), snapshot, issue));
            }
        }
        return items;
    }

    /** 一次架构深度分析的产出：会话 ID、问题列表和完整结果（含路线图）。 */
    record ArchAnalysis(long sessionId, List<Map<String, Object>> issues, Map<String, Object> result) {}

    private WorkbenchItem toArchAiItem(String project, Map<String, Object> issue, ArchAnalysis analysis) {
        String ref = asString(issue.get("ref"));
        String title = asString(issue.get("title"));
        String severity = toSeverity(issue.get("severity"));
        String verdict = asString(issue.get("verdict"));
        String rootCause = asString(issue.get("rootCause"));
        String suggestion = asString(issue.get("suggestion"));
        String reason = asString(issue.get("reason"));
        String location = asString(issue.get("location"));
        if (location == null) {
            location = asString(issue.get("className"));
        }
        List<String> evidence = new ArrayList<>();
        // 证据引用是回查证据包的钥匙，逐条原样保留
        for (String refLine : evidenceRefsOf(issue)) {
            evidence.add("架构证据：" + refLine);
        }
        if (reason != null && !reason.isBlank()) {
            evidence.add("判定依据：" + reason);
        }
        if (evidence.isEmpty()) {
            evidence.add("架构深度分析判定：" + verdict);
        }
        long now = System.currentTimeMillis() / 1000L;
        Map<String, Object> sourceRef = new java.util.LinkedHashMap<>();
        sourceRef.put("session", "arch-deep-analysis");
        sourceRef.put("sessionId", String.valueOf(analysis.sessionId()));
        sourceRef.put("ref", ref == null ? "" : ref);
        return WorkbenchItem.builder()
                .itemKey("arch:ai:" + project + ":" + (ref == null || ref.isBlank() ? sha16(title) : ref))
                .source(WorkbenchItem.SOURCE_ARCH_AI)
                .projectPath(project)
                .title(title == null ? "架构问题" : title)
                .location(location)
                .severity(severity)
                .status(WorkbenchItem.STATUS_NEW)
                .confidence("confirmed".equalsIgnoreCase(verdict) ? "high" : "medium")
                .chainStatus(verdict)
                .why(rootCause)
                .suggestion(suggestion)
                .evidenceJson(toJson(evidence))
                .sourceRefJson(toJson(sourceRef))
                .occurrenceCount(1)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    /** 问题的证据引用：正常是字符串列表；旧数据可能是形如 "['a', 'b']" 的字符串。 */
    static List<String> evidenceRefsOf(Map<String, Object> issue) {
        List<String> out = new ArrayList<>();
        Object refs = issue.get("evidenceRefs");
        if (refs instanceof List<?> list) {
            for (Object ref : list) {
                if (ref != null && !String.valueOf(ref).isBlank()) {
                    out.add(String.valueOf(ref).trim());
                }
            }
        } else if (refs instanceof String text && !text.isBlank()) {
            out.addAll(parseEvidenceRefs(text));
        }
        return out;
    }

    private WorkbenchItem toArchRuleItem(String project, ArchDiagnosisSnapshot snapshot, Map<String, Object> issue) {
        String message = asString(issue.get("message"));
        String severity = toSeverity(issue.get("severity"));
        String sourceLevel = asString(issue.get("sourceLevel"));
        String className = asString(issue.get("className"));
        String methodName = asString(issue.get("methodName"));
        String location = className == null ? sourceLevel
                : className + (methodName == null ? "" : "#" + methodName);
        List<String> evidence = new ArrayList<>();
        evidence.add("检测来源：" + (sourceLevel == null ? "规则" : sourceLevel));
        evidence.add("健康度快照：" + snapshot.getHealthScore() + " 分 / " + snapshot.getGrade() + " 级");
        long now = System.currentTimeMillis() / 1000L;
        return WorkbenchItem.builder()
                .itemKey("arch:rule:" + project + ":" + sha16(severity + "|" + sourceLevel + "|" + message))
                .source(WorkbenchItem.SOURCE_ARCH_RULE)
                .projectPath(project)
                .title(message == null ? "架构问题" : message)
                .location(location)
                .severity(severity)
                .status(WorkbenchItem.STATUS_NEW)
                .confidence(severity.equals("P0") ? "high" : "medium")
                .chainStatus("detected")
                .why(message)
                .suggestion(null)
                .evidenceJson(toJson(evidence))
                .sourceRefJson(toJson(Map.of("snapshotId", String.valueOf(snapshot.getId()))))
                .occurrenceCount(1)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    // ──────────────────────────── 工具方法 ────────────────────────────

    private static String today() {
        return LocalDate.now().toString();
    }

    private String toJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) map;
            return casted;
        }
        return null;
    }

    private static List<Map<String, Object>> asMapList(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = asMap(item);
                if (map != null) {
                    out.add(map);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> projectPathsOf(Map<String, Object> output) {
        List<String> out = new ArrayList<>();
        if (output == null) {
            return out;
        }
        Object paths = output.get("projectPaths");
        if (paths instanceof List<?> list) {
            for (Object path : list) {
                if (path instanceof String s && !s.isBlank()) {
                    out.add(PathUtils.normalize(s));
                }
            }
        }
        return out;
    }

    /** 证据引用在报告里是形如 "['a', 'b']" 的字符串，拆回条目。 */
    static List<String> parseEvidenceRefs(String refs) {
        List<String> out = new ArrayList<>();
        String trimmed = refs.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        for (String part : trimmed.split(",")) {
            String clean = part.trim().replaceAll("^['\"]|['\"]$", "").trim();
            if (!clean.isBlank()) {
                out.add(clean);
            }
        }
        return out;
    }

    /** 兼容两套严重度写法：P0/P1/P2 与 High/Medium/Low（规则回退路径）。 */
    static String toSeverity(Object raw) {
        if (raw == null) {
            return "P2";
        }
        String value = String.valueOf(raw).trim();
        return switch (value.toUpperCase()) {
            case "P0", "HIGH" -> "P0";
            case "P1", "MEDIUM", "MIDDLE" -> "P1";
            case "P2", "LOW" -> "P2";
            default -> "P2";
        };
    }

    private static int severityRank(Object raw) {
        return switch (toSeverity(raw)) {
            case "P0" -> 0;
            case "P1" -> 1;
            default -> 2;
        };
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String sha16(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((text == null ? "" : text).hashCode());
        }
    }
}
