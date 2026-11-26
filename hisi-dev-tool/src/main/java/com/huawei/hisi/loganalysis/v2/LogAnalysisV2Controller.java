package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.model.AnalyzeTaskResponse;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.DetailedAnalysisReport;
import com.huawei.hisi.model.LogAnalyzeRequest;
import com.huawei.hisi.controller.LogAnalysisController;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 日志分析 v2 接口：{@code /api/v2/log}。
 *
 * <p>请求体与响应体和 v1 的 {@code /api/log} 完全一致，v1 路由保持不变。
 * 分析与重新分析走 v2 流程；状态与详情是同一张表的读取，直接委托给 v1 控制器，避免两份实现出现差异。
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/log")
public class LogAnalysisV2Controller {

    private static final String DEFAULT_USER_ID = "sys_admin";

    private final LogAnalysisRepository repository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final LogAnalysisExecutorV2 executorV2;
    private final LogAnalysisController legacyController;

    public LogAnalysisV2Controller(LogAnalysisRepository repository,
                                   SnowflakeIdGenerator snowflakeIdGenerator,
                                   LogAnalysisExecutorV2 executorV2,
                                   LogAnalysisController legacyController) {
        this.repository = repository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.executorV2 = executorV2;
        this.legacyController = legacyController;
    }

    /**
     * 提交日志分析任务（v2 流程）
     * POST /api/v2/log/analyze
     */
    @PostMapping("/analyze")
    public ApiResponse<AnalyzeTaskResponse> analyze(@Valid @RequestBody LogAnalyzeRequest request) {
        try {
            if (request == null) {
                return ApiResponse.error(400, "请求参数不能为空");
            }
            if (request.getMessage() == null && request.getStackTrace() == null) {
                return ApiResponse.error(400, "请求参数不能为空，需要提供日志消息或堆栈信息");
            }

            Long reportId = snowflakeIdGenerator.nextId();
            log.info("[LogAnalysisV2] 生成新的分析任务 (reportId={})", reportId);

            String userId = request.getUserId() != null ? request.getUserId() : DEFAULT_USER_ID;

            LogAnalysisReportEntity report = new LogAnalysisReportEntity();
            report.setReportId(reportId);
            report.setUserId(userId);
            report.setStatus("pending");
            report.setLogMessage(request.getMessage());
            report.setLogStackTrace(request.getStackTrace());
            report.setFilteredStackTrace(null);
            report.setErrorType(request.getErrorType());
            report.setTraceId(request.getTraceId());
            report.setServiceName(request.getServiceName());
            report.setCreatedAt(LocalDateTime.now());
            report.setUpdatedAt(LocalDateTime.now());
            if (request.getProjectPath() != null && !request.getProjectPath().isEmpty()) {
                Map<String, Object> queryParams = new HashMap<>();
                queryParams.put("projectPath", request.getProjectPath());
                report.setQueryParams(queryParams);
            }

            repository.save(report);
            executorV2.executeAnalysis(reportId);
            log.info("[LogAnalysisV2] 异步分析任务已提交 (reportId={})", reportId);

            AnalyzeTaskResponse response = new AnalyzeTaskResponse();
            response.setReportId(reportId);
            response.setStatus("pending");
            response.setCreatedAt(LocalDateTime.now());
            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("[LogAnalysisV2] 提交分析任务失败", e);
            return ApiResponse.error("提交分析任务失败：" + e.getMessage());
        }
    }

    /**
     * 重新分析报告（v2 流程）
     * POST /api/v2/log/report/{id}/reanalyze
     */
    @PostMapping("/report/{id}/reanalyze")
    public ApiResponse<String> reanalyze(@PathVariable("id") Long reportId) {
        try {
            LogAnalysisReportEntity report = repository.findById(reportId);
            if (report == null) {
                return ApiResponse.error(404, "报告不存在");
            }
            executorV2.reanalyze(reportId);
            return ApiResponse.success("已触发重新分析");
        } catch (Exception e) {
            log.error("[LogAnalysisV2] 触发重新分析失败 (reportId={})", reportId, e);
            return ApiResponse.error("触发重新分析失败：" + e.getMessage());
        }
    }

    /**
     * 查询单个任务状态，与 v1 一致
     * GET /api/v2/log/report/{id}/status
     */
    @GetMapping("/report/{id}/status")
    public ApiResponse<Map<String, Object>> getReportStatus(@PathVariable("id") Long reportId) {
        return legacyController.getReportStatus(reportId);
    }

    /**
     * 查询分析报告详情，与 v1 一致（rootCause 里多出 evidenceAnchors、verification）
     * GET /api/v2/log/report/{id}
     */
    @GetMapping("/report/{id}")
    public ApiResponse<DetailedAnalysisReport> getReport(@PathVariable("id") Long reportId) {
        return legacyController.getReport(reportId);
    }
}
