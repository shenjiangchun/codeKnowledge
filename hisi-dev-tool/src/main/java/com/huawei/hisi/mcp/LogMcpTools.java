package com.huawei.hisi.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.controller.LogAnalysisController;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.LogAnalyzeRequest;
import com.huawei.hisi.model.LogQueryDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 进程内 MCP 的日志工具（4 个）：由 Node 版 hisi-mcp-server 的 logTools.ts 迁来，直接调用
 * {@link LogAnalysisController}，对应原来的 4 个 REST 接口：
 * POST /api/log/query、POST /api/log/analyze、GET /api/log/report/{id}、GET /api/log/report/{id}/status。
 *
 * <p>几处刻意的写法：</p>
 * <ul>
 *   <li><b>不参与范围控制</b>：Node 的范围表把 log_query / log_analyze 登记为「受范围控制」，但范围注入往入参里塞的是
 *       projectPaths / projectPath，而这两个工具建请求体时走字段白名单，塞进来的键到不了接口——实际行为就是不受范围影响，
 *       这里照搬实际行为。</li>
 *   <li><b>请求体只带传了的字段</b>（与 Node 的白名单一致）：没传的键不出现，接口 DTO 的默认值
 *       （size=100、errorOnly=true、sortOrder=desc）才保得住。DTO 由应用自己的 ObjectMapper 从请求体还原，
 *       和 REST 路径上 {@code @RequestBody} 的反序列化是同一套规则（时间格式、类型转换）。</li>
 *   <li><b>先做 {@code @Valid} 的校验</b>：REST 路径上请求体先过 Bean Validation（失败是 400、报告不创建），
 *       直接调方法则整步跳过；这里用同一个 Validator 先校验，结果与 REST 一致。</li>
 * </ul>
 *
 * <p>结果统一交给 {@link McpToolSupport#envelope} 输出：业务码非 200 转成 MCP 错误；成功结果由它用后端自己的
 * ObjectMapper 序列化（Long 是字符串、时间是 ISO 字符串），与 REST 响应体一致。</p>
 */
@Component
public class LogMcpTools {

    /** 合同里 logLevel 带 enum；常量名与合同取值逐字相同，框架按常量名绑定入参。 */
    public enum LogLevel {
        ERROR, WARN, INFO, DEBUG
    }

    /** 合同里 sortOrder 带 enum；常量名与合同取值逐字相同，就是发给接口的字符串。 */
    public enum SortOrder {
        asc, desc
    }

    private final LogAnalysisController logAnalysisController;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public LogMcpTools(LogAnalysisController logAnalysisController, ObjectMapper objectMapper, Validator validator) {
        this.logAnalysisController = logAnalysisController;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    // ==================== log_query ====================

    @McpTool(name = "log_query",
            description = "查询日志，支持关键字、日志级别、TraceId、时间范围等过滤条件。"
                    + "如需精确控制可提供 dslQuery（原始DSL，优先级最高）。"
                    + "示例：查询某TraceId的所有ERROR日志 → logLevel:\"ERROR\", traceId:\"abc123\"")
    public Object logQuery(
            @McpToolParam(description = "关键字搜索，模糊匹配日志内容，可选", required = false) String keyword,
            @McpToolParam(description = "日志级别过滤，可选", required = false) LogLevel logLevel,
            @McpToolParam(description = "应用ID，可选", required = false) String appId,
            @McpToolParam(description = "分布式追踪ID，可选", required = false) String traceId,
            @McpToolParam(description = "日志内容精确包含的文本，可选", required = false) String contentContains,
            @McpToolParam(description = "原始DSL查询字符串，优先级最高，设置后覆盖其他过滤条件。可选", required = false) String dslQuery,
            @McpToolParam(description = "查询开始时间，ISO格式，如 \"2024-01-01T00:00:00\"，可选", required = false) String startTime,
            @McpToolParam(description = "查询结束时间，ISO格式，如 \"2024-01-01T23:59:59\"，可选", required = false) String endTime,
            @McpToolParam(description = "返回条数，默认100，最大1000", required = false) Integer size,
            @McpToolParam(description = "是否仅返回错误日志，默认true。设为false可查询所有级别", required = false) Boolean errorOnly,
            @McpToolParam(description = "按时间排序方向，默认desc（最新优先）", required = false) SortOrder sortOrder) {
        ObjectNode body = objectMapper.createObjectNode();
        put(body, "keyword", keyword);
        put(body, "logLevel", logLevel == null ? null : logLevel.name());
        put(body, "appId", appId);
        put(body, "traceId", traceId);
        put(body, "contentContains", contentContains);
        put(body, "dslQuery", dslQuery);
        put(body, "startTime", startTime);
        put(body, "endTime", endTime);
        if (size != null) {
            body.put("size", size);
        }
        if (errorOnly != null) {
            body.put("errorOnly", errorOnly);
        }
        put(body, "sortOrder", sortOrder == null ? null : sortOrder.name());

        LogQueryDto query;
        try {
            query = objectMapper.treeToValue(body, LogQueryDto.class);
        } catch (JsonProcessingException e) {
            // REST 路径上这一步失败是 Jackson 读请求体抛的，经全局异常处理变成 500「服务异常: JSON parse error: …」
            String chain = e instanceof JsonMappingException mapping ? mapping.getPathReference() : "";
            return McpToolSupport.error("log_query", "服务异常: JSON parse error: " + e.getOriginalMessage()
                    + (chain == null || chain.isEmpty() ? "" : " (through reference chain: " + chain + ")"));
        }
        return McpToolSupport.envelope("log_query", logAnalysisController.queryLogs(query));
    }

    // ==================== log_analyze ====================

    @McpTool(name = "log_analyze",
            description = "提交日志异步分析任务，使用AI分析错误日志的根因并给出修复建议。"
                    + "提交后返回reportId，可通过 log_report_status 查询进度，通过 log_report 获取分析结果")
    public Object logAnalyze(
            @McpToolParam(description = "错误消息文本，与 stackTrace 至少提供一个", required = false) String message,
            @McpToolParam(description = "异常堆栈信息，与 message 至少提供一个", required = false) String stackTrace,
            @McpToolParam(description = "发生错误的服务名，可选", required = false) String serviceName,
            @McpToolParam(description = "分布式追踪ID，可选", required = false) String traceId,
            @McpToolParam(description = "错误类型，可选", required = false) String errorType) {
        // 没传的就是 null，与 REST 路径上键缺省一致（Node 版不会给接口发默认值）
        LogAnalyzeRequest request = new LogAnalyzeRequest();
        request.setMessage(message);
        request.setStackTrace(stackTrace);
        request.setServiceName(serviceName);
        request.setTraceId(traceId);
        request.setErrorType(errorType);

        // 与 REST 路径一致：@Valid 校验失败是 400，报告不会创建
        Set<ConstraintViolation<LogAnalyzeRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            return McpToolSupport.error("log_analyze", "参数校验失败: " + errors);
        }
        return McpToolSupport.envelope("log_analyze", logAnalysisController.analyze(request));
    }

    // ==================== log_report ====================

    @McpTool(name = "log_report",
            description = "获取日志分析报告详情，包含根因分析、修复建议和代码片段。传入 log_analyze 返回的 reportId。"
                    + "若报告仍在处理中，先调用 log_report_status 确认完成。")
    public Object logReport(
            @McpToolParam(description = "由 log_analyze 返回的报告ID", required = true) Long reportId) {
        if (reportId == null) {
            return McpToolSupport.error("log_report", "reportId 不能为空");
        }
        return McpToolSupport.envelope("log_report", logAnalysisController.getReport(reportId));
    }

    // ==================== log_report_status ====================

    @McpTool(name = "log_report_status",
            description = "查询日志分析报告状态（pending/processing/completed/failed）")
    public Object logReportStatus(
            @McpToolParam(description = "由 log_analyze 返回的报告ID", required = true) Long reportId) {
        if (reportId == null) {
            return McpToolSupport.error("log_report_status", "reportId 不能为空");
        }
        return McpToolSupport.envelope("log_report_status", logAnalysisController.getReportStatus(reportId));
    }

    // ==================== 内部辅助 ====================

    /** 没传的字段不进请求体（与 Node 版建请求体的写法一致）。 */
    private static void put(ObjectNode body, String field, String value) {
        if (value != null) {
            body.put(field, value);
        }
    }
}
