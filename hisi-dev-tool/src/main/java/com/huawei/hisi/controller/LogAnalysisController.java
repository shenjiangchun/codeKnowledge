package com.huawei.hisi.controller;

import com.huawei.hisi.model.*;
import com.huawei.hisi.loganalysis.dto.LogFollowupRequest;
import com.huawei.hisi.loganalysis.dto.LogFollowupResponse;
import com.huawei.hisi.loganalysis.dto.FollowupSessionDto;
import com.huawei.hisi.loganalysis.service.LogFollowupService;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.service.LogAnalysisExecutor;
import com.huawei.hisi.service.LogCloudService;
import com.huawei.hisi.service.ReportExportService;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志分析控制器
 * 支持异步任务提交和状态查询
 */
@Slf4j
@RestController
@RequestMapping("/api/log")
@RequiredArgsConstructor
@Validated
public class LogAnalysisController {

    private final LogCloudService logCloudService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final LogAnalysisRepository repository;
    private final LogAnalysisExecutor logAnalysisExecutor;
    private final ReportExportService reportExportService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LogFollowupService logFollowupService;

    // 默认用户 ID
    private static final String DEFAULT_USER_ID = "sys_admin";

    /**
     * 查询日志
     * POST /api/log/query
     */
    @PostMapping("/query")
    public ApiResponse<Object> queryLogs(@RequestBody LogQueryDto query) {
        try {
            List<LogEntry> logs = logCloudService.queryLogs(query);
            Map<String, Object> data = new HashMap<>();
            data.put("total", logs.size());
            data.put("logs", logs);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 提交日志分析任务（异步）
     * POST /api/log/analyze
     *
     * @param request 日志分析请求
     * @return 任务 ID 和状态
     */
    @PostMapping("/analyze")
    public ApiResponse<AnalyzeTaskResponse> analyze(@Valid @RequestBody LogAnalyzeRequest request) {
        try {
            // 1. 检查请求是否为空
            if (request == null) {
                return ApiResponse.error(400, "请求参数不能为空");
            }

            // 业务逻辑验证：message 或 stackTrace 至少提供一个
            if (request.getMessage() == null && request.getStackTrace() == null) {
                return ApiResponse.error(400, "请求参数不能为空，需要提供日志消息或堆栈信息");
            }

            // 2. 确保表存在
            // Table initialization handled by repository @PostConstruct

            // 3. 生成报告 ID（雪花算法）
            Long reportId = snowflakeIdGenerator.nextId();
            log.info("生成新的分析任务 (reportId={})", reportId);

            // 4. 获取用户 ID（默认为 sys_admin）
            String userId = request.getUserId() != null ? request.getUserId() : DEFAULT_USER_ID;

            // 5. 创建报告实体
            LogAnalysisReportEntity report = new LogAnalysisReportEntity();
            report.setReportId(reportId);
            report.setUserId(userId);
            report.setStatus("pending");
            report.setLogMessage(request.getMessage());
            report.setLogStackTrace(request.getStackTrace());
            report.setFilteredStackTrace(null); // 由分析服务填充
            report.setErrorType(request.getErrorType());
            report.setTraceId(request.getTraceId());
            report.setServiceName(request.getServiceName());
            report.setCreatedAt(LocalDateTime.now());
            report.setUpdatedAt(LocalDateTime.now());

            // 6.5 设置 queryParams（包含 projectPath 用于 KG 检索）
            if (request.getProjectPath() != null && !request.getProjectPath().isEmpty()) {
                Map<String, Object> queryParams = new HashMap<>();
                queryParams.put("projectPath", request.getProjectPath());
                report.setQueryParams(queryParams);
            }

            // 7. 保存报告（状态为 pending）
            repository.save(report);
            log.info("分析报告已保存 (reportId={}, status=pending)", reportId);

            // 7. 触发异步分析任务
            logAnalysisExecutor.executeAnalysis(reportId);
            log.info("异步分析任务已提交 (reportId={})", reportId);

            // 8. 立即返回任务 ID
            AnalyzeTaskResponse response = new AnalyzeTaskResponse();
            response.setReportId(reportId);
            response.setStatus("pending");
            response.setCreatedAt(LocalDateTime.now());

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("提交分析任务失败", e);
            return ApiResponse.error("提交分析任务失败：" + e.getMessage());
        }
    }

    /**
     * 查询分析任务列表
     * GET /api/log/reports
     *
     * @param userId 用户 ID（可选，默认为 sys_admin）
     * @param status 状态过滤（可选）
     * @param page   页码（可选，默认 1）
     * @param pageSize 每页大小（可选，默认 10）
     * @return 任务列表
     */
    @GetMapping("/reports")
    public ApiResponse<ReportListResponse> getReports(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        try {
            String finalUserId = userId != null ? userId : DEFAULT_USER_ID;

            List<LogAnalysisReportEntity> reports;

            // 如果指定了状态，按状态查询
            if (status != null && !status.isEmpty()) {
                reports = repository.findByUserIdAndStatus(finalUserId, status);
            } else {
                // 否则分页查询所有
                var paginated = repository.findByUserIdPagination(finalUserId, page, pageSize);
                reports = paginated.getList();

                // 构建完整响应
                ReportListResponse response = new ReportListResponse();
                response.setTotal(paginated.getTotal());
                response.setPage(page);
                response.setPageSize(pageSize);
                response.setList(ReportListResponse.fromEntities(reports));

                return ApiResponse.success(response);
            }

            // 按状态查询时返回全部匹配结果
            ReportListResponse response = new ReportListResponse();
            response.setTotal(reports.size());
            response.setPage(1);
            response.setPageSize(reports.size());
            response.setList(ReportListResponse.fromEntities(reports));

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("查询任务列表失败", e);
            return ApiResponse.error("查询任务列表失败：" + e.getMessage());
        }
    }

    /**
     * Task 71: 查询指定应用的报告列表
     * GET /api/log/reports/by-app/{appId}
     *
     * @param appId 应用 ID（来自 AppLogConfig）
     * @return 该应用的所有报告列表
     */
    @GetMapping("/reports/by-app/{appId}")
    public ApiResponse<ReportListResponse> getReportsByAppId(@PathVariable("appId") String appId) {
        try {
            List<LogAnalysisReportEntity> reports = repository.findByAppId(appId);

            ReportListResponse response = new ReportListResponse();
            response.setTotal(reports.size());
            response.setPage(1);
            response.setPageSize(reports.size());
            response.setList(ReportListResponse.fromEntities(reports));

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("查询应用报告列表失败 (appId={})", appId, e);
            return ApiResponse.error("查询应用报告列表失败：" + e.getMessage());
        }
    }

    /**
     * Task 71: 查询指定配置的报告列表
     * GET /api/log/reports/by-config/{configId}
     *
     * @param configId 配置 ID（来自 AppLogConfig.id）
     * @return 该配置的所有报告列表
     */
    @GetMapping("/reports/by-config/{configId}")
    public ApiResponse<ReportListResponse> getReportsByConfigId(@PathVariable("configId") Long configId) {
        try {
            List<LogAnalysisReportEntity> reports = repository.findByConfigId(configId);

            ReportListResponse response = new ReportListResponse();
            response.setTotal(reports.size());
            response.setPage(1);
            response.setPageSize(reports.size());
            response.setList(ReportListResponse.fromEntities(reports));

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("查询配置报告列表失败 (configId={})", configId, e);
            return ApiResponse.error("查询配置报告列表失败：" + e.getMessage());
        }
    }

    /**
     * 获取分析报告详情
     * GET /api/log/report/{id}
     *
     * @param reportId 报告 ID
     * @return 分析报告详情
     */
    @GetMapping("/report/{id}")
    public ApiResponse<DetailedAnalysisReport> getReport(@PathVariable("id") Long reportId) {
        try {
            LogAnalysisReportEntity report = repository.findById(reportId);

            if (report == null) {
                return ApiResponse.error(404, "报告不存在");
            }

            // 如果任务还在处理中，返回状态提示
            if ("pending".equals(report.getStatus()) || "processing".equals(report.getStatus())) {
                return ApiResponse.error(400, "报告尚未完成，当前状态：" + report.getStatus());
            }

            // 构建详细报告响应
            // v3: rootCause 已含 markdown 字段，无需单独提取 causalChain/multiFactorAnalysis/timeline
            DetailedAnalysisReport response = DetailedAnalysisReport.builder()
                    .reportId(report.getReportId())
                    .status(report.getStatus())
                    .errorSummary(report.getErrorSummary())
                    .rootCause(report.getRootCause())
                    .fixSuggestions(report.getFixSuggestions())
                    .codeSnippets(report.getCodeSnippets())
                    .createdAt(report.getCreatedAt())
                    .updatedAt(report.getUpdatedAt())
                    .occurrenceCount(report.getOccurrenceCount())
                    .build();

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("获取报告详情失败 (reportId={})", reportId, e);
            return ApiResponse.error("获取报告详情失败：" + e.getMessage());
        }
    }

    /**
     * 重新分析报告
     * POST /api/log/report/{id}/reanalyze
     */
    @PostMapping("/report/{id}/reanalyze")
    public ApiResponse<String> reanalyze(@PathVariable("id") Long reportId) {
        try {
            LogAnalysisReportEntity report = repository.findById(reportId);
            if (report == null) {
                return ApiResponse.error(404, "报告不存在");
            }
            logAnalysisExecutor.reanalyze(reportId);
            return ApiResponse.success("已触发重新分析");
        } catch (Exception e) {
            log.error("触发重新分析失败 (reportId={})", reportId, e);
            return ApiResponse.error("触发重新分析失败：" + e.getMessage());
        }
    }

    /**
     * 查询单个任务状态（扩展版）
     * GET /api/log/report/{id}/status
     *
     * Task 7: Extended status API with pipeline progress
     *
     * @param reportId 报告 ID
     * @return 任务状态（含progress, stage, etaSeconds, queuePosition）
     */
    @GetMapping("/report/{id}/status")
    public ApiResponse<Map<String, Object>> getReportStatus(@PathVariable("id") Long reportId) {
        try {
            LogAnalysisReportEntity report = repository.findById(reportId);

            if (report == null) {
                return ApiResponse.error(404, "报告不存在");
            }

            Map<String, Object> status = new HashMap<>();
            status.put("reportId", report.getReportId());
            status.put("status", report.getStatus());
            status.put("createdAt", report.getCreatedAt());
            status.put("updatedAt", report.getUpdatedAt());

            // Task 7: Extended progress info
            status.put("progress", calculateProgress(report));
            status.put("stage", determineStage(report));
            status.put("etaSeconds", estimateRemainingTime(report));
            status.put("queuePosition", getQueuePosition(report));
            status.put("occurrenceCount", report.getOccurrenceCount());
            status.put("analysisStatus", report.getAnalysisStatus());

            return ApiResponse.success(status);

        } catch (Exception e) {
            log.error("查询任务状态失败 (reportId={})", reportId, e);
            return ApiResponse.error("查询任务状态失败：" + e.getMessage());
        }
    }

    // ==================== Task 7: Helper methods for extended status ====================

    private int calculateProgress(LogAnalysisReportEntity report) {
        String analysisStatus = report.getAnalysisStatus();
        if (analysisStatus == null) analysisStatus = "pending";

        switch (analysisStatus) {
            case "pending": return 0;
            case "parsing": return 25;
            case "deduplicating": return 50;
            case "analyzing": return 75;
            case "completed": return 100;
            case "failed": return 100;
            default: return 0;
        }
    }

    private String determineStage(LogAnalysisReportEntity report) {
        String analysisStatus = report.getAnalysisStatus();
        if (analysisStatus == null) {
            return "pending";
        }
        return analysisStatus;
    }

    private int estimateRemainingTime(LogAnalysisReportEntity report) {
        String analysisStatus = report.getAnalysisStatus();
        if ("completed".equals(analysisStatus) || "failed".equals(analysisStatus)) {
            return 0;
        }
        // Rough estimate based on stage (in seconds)
        switch (analysisStatus) {
            case "pending": return 120; // ~2 min wait
            case "parsing": return 90;  // ~1.5 min left
            case "deduplicating": return 60; // ~1 min left
            case "analyzing": return 30; // ~30 sec left
            default: return 60;
        }
    }

    private int getQueuePosition(LogAnalysisReportEntity report) {
        String analysisStatus = report.getAnalysisStatus();
        if (analysisStatus == null || !"pending".equals(analysisStatus)) {
            return 0;
        }
        // Count pending reports created before this one
        return repository.countPendingBefore(report.getCreatedAt());
    }

    /**
     * 删除分析报告
     * DELETE /api/log/report/{id}
     *
     * @param reportId 报告 ID
     * @return 操作结果
     */
    @DeleteMapping("/report/{id}")
    public ApiResponse<String> deleteReport(@PathVariable("id") Long reportId) {
        try {
            LogAnalysisReportEntity report = repository.findById(reportId);

            if (report == null) {
                return ApiResponse.error(404, "报告不存在");
            }

            repository.deleteById(reportId);
            log.info("报告已删除 (reportId={})", reportId);

            return ApiResponse.success("报告已删除");

        } catch (Exception e) {
            log.error("删除报告失败 (reportId={})", reportId, e);
            return ApiResponse.error("删除报告失败：" + e.getMessage());
        }
    }

    /**
     * 导出报告为 Markdown 格式
     * GET /api/log/report/{id}/export/md
     *
     * @param id 报告 ID
     * @return Markdown 文件
     */
    @GetMapping("/report/{id}/export/md")
    public ResponseEntity<String> exportReportMd(@PathVariable("id") Long id) {
        try {
            String markdown = reportExportService.exportLogReportAsMd(id);
            return ResponseEntity.ok()
                .header("Content-Type", "text/markdown; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"report-" + id + ".md\"")
                .body(markdown);
        } catch (IllegalArgumentException e) {
            log.warn("导出报告失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("报告不存在: " + id);
        }
    }

    /**
     * 批量导出报告为 ZIP 文件
     * GET /api/log/reports/export/zip?startTime=&endTime=
     *
     * @param startTime 开始时间 (ISO 格式: yyyy-MM-ddTHH:mm:ss，可选)
     * @param endTime 结束时间 (ISO 格式: yyyy-MM-ddTHH:mm:ss，可选)
     * @return ZIP 文件
     */
    @GetMapping("/reports/export/zip")
    public ResponseEntity<byte[]> exportReportsZip(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        try {
            // 如果不传日期，导出全部报告
            if (startTime == null || endTime == null) {
                startTime = LocalDateTime.of(2020, 1, 1, 0, 0);
                endTime = LocalDateTime.now();
            }

            byte[] zipContent = reportExportService.exportLogReportsAsZip(startTime, endTime);

            String filename = "reports-" + startTime.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".zip";

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(zipContent);
        } catch (Exception e) {
            log.error("批量导出报告失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== 追问 (Follow-up Q&A) ====================

    /**
     * 发起追问对话
     * POST /api/log/report/{id}/followup
     */
    @PostMapping("/report/{id}/followup")
    public ApiResponse<LogFollowupResponse> startFollowup(
            @PathVariable("id") Long reportId,
            @RequestBody LogFollowupRequest request) {
        if (logFollowupService == null) {
            return ApiResponse.error(503, "追问服务未启用（需要配置 Claude API Key）");
        }
        try {
            LogAnalysisReportEntity report = repository.findById(reportId);
            if (report == null) {
                return ApiResponse.error(404, "报告不存在");
            }

            String projectPath = null;
            if (report.getQueryParams() != null) {
                Object pp = report.getQueryParams().get("projectPath");
                if (pp instanceof String s && !s.isBlank()) {
                    projectPath = s;
                }
            }

            String sessionId = logFollowupService.startFollowup(reportId, request.message(), projectPath);
            return ApiResponse.success(new LogFollowupResponse(sessionId, "processing"));
        } catch (Exception e) {
            log.error("发起追问失败 (reportId={})", reportId, e);
            return ApiResponse.error("发起追问失败：" + e.getMessage());
        }
    }

    /**
     * 继续追问对话
     * POST /api/log/followup/{sessionId}/message
     */
    @PostMapping("/followup/{sessionId}/message")
    public ApiResponse<String> continueFollowup(
            @PathVariable("sessionId") String sessionId,
            @RequestBody LogFollowupRequest request) {
        if (logFollowupService == null) {
            return ApiResponse.error(503, "追问服务未启用");
        }
        try {
            logFollowupService.continueFollowup(sessionId, request.message());
            return ApiResponse.success("processing");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("继续追问失败 (sessionId={})", sessionId, e);
            return ApiResponse.error("继续追问失败：" + e.getMessage());
        }
    }

    /**
     * 获取追问会话状态
     * GET /api/log/followup/{sessionId}
     */
    @GetMapping("/followup/{sessionId}")
    public ApiResponse<FollowupSessionDto> getFollowupSession(@PathVariable("sessionId") String sessionId) {
        if (logFollowupService == null) {
            return ApiResponse.error(503, "追问服务未启用");
        }
        FollowupSessionDto session = logFollowupService.getSession(sessionId);
        if (session == null) {
            return ApiResponse.error(404, "追问会话不存在");
        }
        return ApiResponse.success(session);
    }
}