package com.huawei.hisi.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志分析报告数据访问层 (SQLite)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LogAnalysisRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 根据报告编号查询报告
     */
    public LogAnalysisReportEntity findByReportNo(String reportNo) {
        String sql = "SELECT * FROM log_analysis_report WHERE report_no = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new LogAnalysisReportRowMapper(), reportNo);
        } catch (Exception e) {
            log.warn("查询报告失败 (reportNo={}): {}", reportNo, e.getMessage());
            return null;
        }
    }

    /**
     * 根据ID查询报告
     */
    public LogAnalysisReportEntity findById(Long id) {
        String sql = "SELECT * FROM log_analysis_report WHERE id = ? OR report_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new LogAnalysisReportRowMapper(), id, id);
        } catch (Exception e) {
            log.warn("查询报告失败 (id={}): {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 保存报告 (upsert by report_id)
     */
    public void save(LogAnalysisReportEntity report) {
        String sql = """
            INSERT INTO log_analysis_report (report_id, report_no, user_id, query_params, log_message,
                log_stack_trace, filtered_stack_trace, error_type, trace_id, service_name,
                log_summary, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%s','now'), strftime('%s','now'))
            ON CONFLICT(report_id) DO UPDATE SET
                log_summary = excluded.log_summary,
                status = excluded.status,
                updated_at = strftime('%s','now')
            """;

        try {
            String queryParamsJson = report.getQueryParams() != null ?
                objectMapper.writeValueAsString(report.getQueryParams()) : null;

            jdbcTemplate.update(sql,
                report.getReportId(),
                report.getReportNo(),
                report.getUserId(),
                queryParamsJson,
                report.getLogMessage(),
                report.getLogStackTrace(),
                report.getFilteredStackTrace(),
                report.getErrorType(),
                report.getTraceId(),
                report.getServiceName(),
                report.getLogSummary(),
                report.getStatus()
            );
            log.debug("报告保存成功 (reportId={})", report.getReportId());
        } catch (Exception e) {
            log.error("报告保存失败 (reportId={}): {}", report.getReportId(), e.getMessage());
            throw new RuntimeException("保存报告失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新报告状态 (by reportNo)
     */
    public void updateStatus(String reportNo, String status) {
        String sql = "UPDATE log_analysis_report SET status = ?, updated_at = strftime('%s','now') WHERE report_no = ?";
        try {
            jdbcTemplate.update(sql, status, reportNo);
        } catch (Exception e) {
            log.error("报告状态更新失败 (reportNo={}): {}", reportNo, e.getMessage());
            throw new RuntimeException("更新状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新报告状态 (by reportId)
     */
    public void updateStatus(Long reportId, String status) {
        String sql = "UPDATE log_analysis_report SET status = ?, updated_at = strftime('%s','now') WHERE report_id = ?";
        try {
            jdbcTemplate.update(sql, status, reportId);
        } catch (Exception e) {
            log.error("报告状态更新失败 (reportId={}): {}", reportId, e.getMessage());
            throw new RuntimeException("更新状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新报告摘要
     */
    public void updateSummary(String reportNo, String summary) {
        String sql = "UPDATE log_analysis_report SET log_summary = ?, status = 'completed', updated_at = strftime('%s','now') WHERE report_no = ?";
        try {
            jdbcTemplate.update(sql, summary, reportNo);
        } catch (Exception e) {
            log.error("报告摘要更新失败 (reportNo={}): {}", reportNo, e.getMessage());
            throw new RuntimeException("更新摘要失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按状态查询报告列表
     */
    public List<LogAnalysisReportEntity> findByStatus(String status) {
        String sql = "SELECT * FROM log_analysis_report WHERE status = ? ORDER BY created_at DESC";
        try {
            return jdbcTemplate.query(sql, new LogAnalysisReportRowMapper(), status);
        } catch (Exception e) {
            log.error("查询报告列表失败 (status={}): {}", status, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按用户ID和状态查询报告列表
     */
    public List<LogAnalysisReportEntity> findByUserIdAndStatus(String userId, String status) {
        String sql = "SELECT * FROM log_analysis_report WHERE user_id = ? AND status = ? ORDER BY created_at DESC";
        try {
            return jdbcTemplate.query(sql, new LogAnalysisReportRowMapper(), userId, status);
        } catch (Exception e) {
            log.error("查询报告列表失败 (userId={}, status={}): {}", userId, status, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按用户ID分页查询报告
     */
    public PaginatedReports findByUserIdPagination(String userId, int page, int pageSize) {
        String countSql = "SELECT COUNT(*) FROM log_analysis_report WHERE user_id = ?";
        String listSql = "SELECT * FROM log_analysis_report WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";

        try {
            Integer total = jdbcTemplate.queryForObject(countSql, Integer.class, userId);
            int offset = (page - 1) * pageSize;
            List<LogAnalysisReportEntity> list = jdbcTemplate.query(listSql, new LogAnalysisReportRowMapper(), userId, pageSize, offset);
            return new PaginatedReports(total != null ? total : 0, list);
        } catch (Exception e) {
            log.error("分页查询报告列表失败 (userId={}, page={}): {}", userId, page, e.getMessage());
            return new PaginatedReports(0, List.of());
        }
    }

    /**
     * 分页查询所有报告
     */
    public PaginatedReports findAllPaginated(int page, int pageSize) {
        String countSql = "SELECT COUNT(*) FROM log_analysis_report";
        String listSql = "SELECT * FROM log_analysis_report ORDER BY created_at DESC LIMIT ? OFFSET ?";

        try {
            Integer total = jdbcTemplate.queryForObject(countSql, Integer.class);
            int offset = (page - 1) * pageSize;
            List<LogAnalysisReportEntity> list = jdbcTemplate.query(listSql, new LogAnalysisReportRowMapper(), pageSize, offset);
            return new PaginatedReports(total != null ? total : 0, list);
        } catch (Exception e) {
            log.error("分页查询报告列表失败 (page={}): {}", page, e.getMessage());
            return new PaginatedReports(0, List.of());
        }
    }

    /**
     * 更新分析结果
     */
    public void updateAnalysisResult(Long reportId, Map<String, Object> errorSummary,
                                      Map<String, Object> rootCause,
                                      List<Map<String, Object>> fixSuggestions,
                                      List<Map<String, Object>> codeSnippets) {
        String sql = """
            UPDATE log_analysis_report
            SET error_summary = ?, root_cause = ?, fix_suggestions = ?, code_snippets = ?,
                status = 'completed', updated_at = strftime('%s','now')
            WHERE report_id = ?
            """;
        try {
            String errorSummaryJson = errorSummary != null ? objectMapper.writeValueAsString(errorSummary) : null;
            String rootCauseJson = rootCause != null ? objectMapper.writeValueAsString(rootCause) : null;
            String fixSuggestionsJson = fixSuggestions != null ? objectMapper.writeValueAsString(fixSuggestions) : null;
            String codeSnippetsJson = codeSnippets != null ? objectMapper.writeValueAsString(codeSnippets) : null;

            jdbcTemplate.update(sql, errorSummaryJson, rootCauseJson, fixSuggestionsJson, codeSnippetsJson, reportId);
            log.debug("分析结果已更新 (reportId={})", reportId);
        } catch (Exception e) {
            log.error("更新分析结果失败 (reportId={}): {}", reportId, e.getMessage());
            throw new RuntimeException("更新分析结果失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新错误信息
     */
    public void updateError(Long reportId, String errorMessage) {
        String sql = """
            UPDATE log_analysis_report
            SET status = 'failed', log_summary = ?, updated_at = strftime('%s','now')
            WHERE report_id = ?
            """;
        try {
            jdbcTemplate.update(sql, errorMessage, reportId);
        } catch (Exception e) {
            log.error("更新错误信息失败 (reportId={}): {}", reportId, e.getMessage());
        }
    }

    /**
     * 报告实体类
     */
    @SuppressWarnings("unused")
    public static class LogAnalysisReportEntity {
        private Long id;
        private Long reportId;
        private String reportNo;
        private String userId;
        private Map<String, Object> queryParams;
        private String logMessage;
        private String logStackTrace;
        private String filteredStackTrace;
        private String errorType;
        private String traceId;
        private String serviceName;
        private String logSummary;
        private Map<String, Object> errorSummary;
        private Map<String, Object> rootCause;
        private List<Map<String, Object>> fixSuggestions;
        private List<Map<String, Object>> codeSnippets;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public Long getReportId() { return reportId; }
        public void setReportId(Long reportId) { this.reportId = reportId; }

        public String getReportNo() { return reportNo; }
        public void setReportNo(String reportNo) { this.reportNo = reportNo; }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public Map<String, Object> getQueryParams() { return queryParams; }
        public void setQueryParams(Map<String, Object> queryParams) { this.queryParams = queryParams; }

        public String getLogMessage() { return logMessage; }
        public void setLogMessage(String logMessage) { this.logMessage = logMessage; }

        public String getLogStackTrace() { return logStackTrace; }
        public void setLogStackTrace(String logStackTrace) { this.logStackTrace = logStackTrace; }

        public String getFilteredStackTrace() { return filteredStackTrace; }
        public void setFilteredStackTrace(String filteredStackTrace) { this.filteredStackTrace = filteredStackTrace; }

        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }

        public String getLogSummary() { return logSummary; }
        public void setLogSummary(String logSummary) { this.logSummary = logSummary; }

        public Map<String, Object> getErrorSummary() { return errorSummary; }
        public void setErrorSummary(Map<String, Object> errorSummary) { this.errorSummary = errorSummary; }

        public Map<String, Object> getRootCause() { return rootCause; }
        public void setRootCause(Map<String, Object> rootCause) { this.rootCause = rootCause; }

        public List<Map<String, Object>> getFixSuggestions() { return fixSuggestions; }
        public void setFixSuggestions(List<Map<String, Object>> fixSuggestions) { this.fixSuggestions = fixSuggestions; }

        public List<Map<String, Object>> getCodeSnippets() { return codeSnippets; }
        public void setCodeSnippets(List<Map<String, Object>> codeSnippets) { this.codeSnippets = codeSnippets; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    /**
     * 分页报告结果
     */
    public static class PaginatedReports {
        private final int total;
        private final List<LogAnalysisReportEntity> list;

        public PaginatedReports(int total, List<LogAnalysisReportEntity> list) {
            this.total = total;
            this.list = list;
        }

        public int getTotal() { return total; }
        public List<LogAnalysisReportEntity> getList() { return list; }
    }

    /**
     * 行映射器
     */
    private class LogAnalysisReportRowMapper implements org.springframework.jdbc.core.RowMapper<LogAnalysisReportEntity> {
        @Override
        @SuppressWarnings("unchecked")
        public LogAnalysisReportEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            LogAnalysisReportEntity report = new LogAnalysisReportEntity();
            report.setId(rs.getLong("id"));
            report.setReportId(rs.getObject("report_id") != null ? rs.getLong("report_id") : null);
            report.setReportNo(rs.getString("report_no"));
            report.setUserId(rs.getString("user_id"));
            report.setLogMessage(rs.getString("log_message"));
            report.setLogStackTrace(rs.getString("log_stack_trace"));
            report.setFilteredStackTrace(rs.getString("filtered_stack_trace"));
            report.setErrorType(rs.getString("error_type"));
            report.setTraceId(rs.getString("trace_id"));
            report.setServiceName(rs.getString("service_name"));
            report.setLogSummary(rs.getString("log_summary"));
            report.setStatus(rs.getString("status"));
            long createdEpoch = rs.getLong("created_at");
            report.setCreatedAt(createdEpoch > 0 ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(createdEpoch), ZoneId.systemDefault()) : null);
            long updatedEpoch = rs.getLong("updated_at");
            report.setUpdatedAt(updatedEpoch > 0 ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(updatedEpoch), ZoneId.systemDefault()) : null);

            // Parse JSON fields
            try {
                String queryParamsJson = rs.getString("query_params");
                if (queryParamsJson != null && !queryParamsJson.isEmpty()) {
                    report.setQueryParams(objectMapper.readValue(queryParamsJson, HashMap.class));
                }
            } catch (Exception e) {
                log.warn("解析 query_params JSON 字段失败: {}", e.getMessage());
            }

            try {
                String errorSummaryJson = rs.getString("error_summary");
                if (errorSummaryJson != null && !errorSummaryJson.isEmpty()) {
                    report.setErrorSummary(objectMapper.readValue(errorSummaryJson, HashMap.class));
                }
            } catch (Exception e) {
                log.warn("解析 error_summary JSON 字段失败: {}", e.getMessage());
            }

            try {
                String rootCauseJson = rs.getString("root_cause");
                if (rootCauseJson != null && !rootCauseJson.isEmpty()) {
                    report.setRootCause(objectMapper.readValue(rootCauseJson, HashMap.class));
                }
            } catch (Exception e) {
                log.warn("解析 root_cause JSON 字段失败: {}", e.getMessage());
            }

            try {
                String fixSuggestionsJson = rs.getString("fix_suggestions");
                if (fixSuggestionsJson != null && !fixSuggestionsJson.isEmpty()) {
                    report.setFixSuggestions(objectMapper.readValue(fixSuggestionsJson, List.class));
                }
            } catch (Exception e) {
                log.warn("解析 fix_suggestions JSON 字段失败: {}", e.getMessage());
            }

            try {
                String codeSnippetsJson = rs.getString("code_snippets");
                if (codeSnippetsJson != null && !codeSnippetsJson.isEmpty()) {
                    report.setCodeSnippets(objectMapper.readValue(codeSnippetsJson, List.class));
                }
            } catch (Exception e) {
                log.warn("解析 code_snippets JSON 字段失败: {}", e.getMessage());
            }

            return report;
        }
    }
}
