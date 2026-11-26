package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.service.LogAnalysisExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志分析 v2 的异步执行器：负责建图之外的状态流转与结果落库。
 *
 * <p>与 v1 的 {@code LogAnalysisExecutor} 写同一张 {@code log_analysis_report} 表、同一组列，
 * 所以列表、详情、导出、追问等 v1 读接口对 v2 产出的报告同样有效。
 * v2 只在 {@code rootCause} 列里多存 {@code evidenceAnchors} 与 {@code verification} 两段附加信息。
 *
 * <p>v2 流程没有产出报告时回退到 v1 流程，保证分析不会因为 v2 的问题而失败。
 */
@Slf4j
@Service
public class LogAnalysisExecutorV2 {

    private final LogAnalysisRepository repository;
    private final LogAnalysisV2Orchestrator orchestrator;
    private final LogAnalysisExecutor legacyExecutor;
    /**
     * 正在分析中的报告。同一报告的重复触发直接忽略：否则两次分析并发写同一行、节点事件交错，
     * 模型调用也要花两遍。
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public LogAnalysisExecutorV2(LogAnalysisRepository repository,
                                 LogAnalysisV2Orchestrator orchestrator,
                                 LogAnalysisExecutor legacyExecutor) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.legacyExecutor = legacyExecutor;
    }

    /** 异步执行日志分析（v2 流程）。 */
    @Async("analysisTaskExecutor")
    public void executeAnalysis(Long reportId) {
        if (!reserve(reportId)) {
            return;
        }
        try {
            runAnalysis(reportId);
        } finally {
            inFlight.remove(reportId);
        }
    }

    /** 重新分析：重置状态后重新执行 v2 流程。状态流转与 v1 一致。 */
    @Async("analysisTaskExecutor")
    public void reanalyze(Long reportId) {
        if (!reserve(reportId)) {
            return;
        }
        try {
            log.info("[LogAnalysisV2] 重新分析 (reportId={})", reportId);
            LogAnalysisReportEntity report = repository.findById(reportId);
            if (report == null) {
                log.error("[LogAnalysisV2] 报告不存在，无法重新分析 (reportId={})", reportId);
                return;
            }
            repository.updateAnalysisStatus(reportId, "pending");
            repository.updateAnalysisResult(reportId, null, null, null, null);
            runAnalysis(reportId);
        } finally {
            inFlight.remove(reportId);
        }
    }

    /** 占住报告；已被占用说明上一轮分析还没结束，这次触发直接忽略。 */
    private boolean reserve(Long reportId) {
        if (inFlight.add(reportId)) {
            return true;
        }
        log.info("[LogAnalysisV2] 报告已在分析中，忽略重复触发 (reportId={})", reportId);
        return false;
    }

    private void runAnalysis(Long reportId) {
        log.info("[LogAnalysisV2] 开始分析 (reportId={})", reportId);
        try {
            // analysis_status 也要写：任务状态接口的阶段与进度是从它算出来的，只写 status 的话进度一直是 0
            repository.updateAnalysisStatus(reportId, "analyzing");
            LogAnalysisReportEntity report = repository.findById(reportId);
            if (report == null) {
                throw new IllegalStateException("报告不存在 (reportId=" + reportId + ")");
            }

            Map<String, Object> result = orchestrator.analyzeLog(
                    report.getLogMessage(),
                    report.getLogStackTrace(),
                    projectPathOf(report),
                    report.getServiceName(),
                    report.getTraceId(),
                    projectPackagePrefixesOf(report),
                    // 带上报告 ID，页面才能通过 WebSocket 看到节点进度
                    report.getReportId());

            Map<String, Object> finalReport = asMap(result.get("finalReport"));
            if (finalReport == null) {
                log.warn("[LogAnalysisV2] v2 流程未产出报告（errorNode={}），回退到 v1 流程 (reportId={})",
                        result.get("errorNode"), reportId);
                repository.updateAnalysisStatus(reportId, "pending");
                legacyExecutor.reanalyze(reportId);
                return;
            }

            repository.updateAnalysisResult(
                    reportId,
                    asMap(finalReport.get("errorSummary")),
                    buildRootCause(result, finalReport),
                    asList(finalReport.get("fixSuggestions")),
                    asList(finalReport.get("keyStackFrames")));
            repository.updateAnalysisStatus(reportId, "completed");
            log.info("[LogAnalysisV2] 分析完成 (reportId={})", reportId);
        } catch (Exception e) {
            log.error("[LogAnalysisV2] 分析失败 (reportId={})", reportId, e);
            repository.updateError(reportId, e.getClass().getName() + ": " + e.getMessage());
            repository.updateAnalysisStatus(reportId, "failed");
        }
    }

    /** rootCause 列：v1 的三个键原样保留，另加 v2 的证据与验证结果。 */
    @SuppressWarnings("unchecked")
    Map<String, Object> buildRootCause(Map<String, Object> result, Map<String, Object> finalReport) {
        Map<String, Object> errorSummary = asMap(finalReport.get("errorSummary"));
        if (errorSummary == null) {
            errorSummary = new LinkedHashMap<>();
        }
        Map<String, Object> section = asMap(finalReport.get("rootCauseAnalysis"));
        Map<String, Object> rootCause = new LinkedHashMap<>();
        if (section != null) {
            rootCause.put("rootCauseType", errorSummary.get("rootCauseException"));
            rootCause.put("markdown", section.get("markdown"));
            rootCause.put("confidence", section.get("confidence"));
        }
        rootCause.put("evidenceAnchors", result.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_ANCHORS));
        rootCause.put("verification", compactVerification(asMap(result.get(LogVerifyNodeV2.KEY_VERIFICATION))));
        return rootCause;
    }

    /** 去掉每条验证结果里内嵌的完整假设，只留判定信息，避免报告详情过长。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> compactVerification(Map<String, Object> verification) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (verification == null) {
            return out;
        }
        out.put("overall", verification.get("overall"));
        List<Map<String, Object>> hypotheses = new ArrayList<>();
        if (verification.get("hypotheses") instanceof List<?> verdicts) {
            for (Object o : verdicts) {
                Map<String, Object> v = asMap(o);
                if (v == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", v.get("id"));
                item.put("title", v.get("title"));
                item.put("confidence", v.get("confidence"));
                item.put("chainStatus", v.get("chainStatus"));
                item.put("rootCauseInKg", v.get("rootCauseInKg"));
                item.put("kept", v.get("kept"));
                item.put("citedIds", v.get("citedIds"));
                item.put("unknownIds", v.get("unknownIds"));
                item.put("reasons", v.get("reasons"));
                hypotheses.add(item);
            }
        }
        out.put("hypotheses", hypotheses);
        return out;
    }

    // queryParams 里的 projectPath / projectPackagePrefixes 读取方式与 v1 LogAnalysisExecutor 保持一致

    @SuppressWarnings("unchecked")
    private static String projectPathOf(LogAnalysisReportEntity report) {
        if (report.getQueryParams() == null) {
            return null;
        }
        return report.getQueryParams().get("projectPath") instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> projectPackagePrefixesOf(LogAnalysisReportEntity report) {
        if (report.getQueryParams() == null) {
            return null;
        }
        Object prefixes = report.getQueryParams().get("projectPackagePrefixes");
        if (prefixes instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(s -> !s.isBlank()).toList();
        }
        if (prefixes instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(trimmed -> !trimmed.isEmpty()).toList();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>();
    }
}
