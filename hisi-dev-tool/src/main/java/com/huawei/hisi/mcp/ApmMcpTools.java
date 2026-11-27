package com.huawei.hisi.mcp;

import com.huawei.hisi.apm.controller.ApmController;
import com.huawei.hisi.apm.model.ApmRequest.ExecuteRequest;
import com.huawei.hisi.apm.model.ApmRequest.LaunchRequest;
import com.huawei.hisi.apm.model.ApmRequest.StopRequest;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 进程内 MCP 的 APM 调试工具（6 个）：由 Node 版 hisi-mcp-server 的 apmTools.ts 迁来，直接调用 {@link ApmController}，
 * 对应原来的 6 个 REST 接口：POST /api/apm/launch、POST /api/apm/execute、POST /api/apm/stop、
 * GET /api/apm/spans/{sessionId}、GET /api/apm/trace/{traceId}、GET /api/apm/report/{sessionId}。
 *
 * <p>apm_* 全部不参与范围控制。Node 版在分发前会对所有工具的入参做一次路径归一化（反斜杠→正斜杠），
 * 命中的键是 projectPath 和 path，所以 apm_start_session 的 projectPath、apm_execute_request 的 path
 * 都要过 {@link McpToolSupport#path}。</p>
 */
@Component
public class ApmMcpTools {

    /** 合同里 method 带 enum；常量名与合同取值逐字相同，框架按常量名绑定入参，也就是发给接口的字符串。 */
    public enum HttpMethod {
        GET, POST, PUT, DELETE, PATCH
    }

    private final ApmController apmController;

    public ApmMcpTools(ApmController apmController) {
        this.apmController = apmController;
    }

    // ==================== apm_start_session ====================

    @McpTool(name = "apm_start_session",
            description = "Start an APM debug session — launches a Spring Boot project with the OpenTelemetry agent "
                    + "attached for trace collection. Returns a sessionId to use with other APM tools.")
    public Object apmStartSession(
            @McpToolParam(description = "Absolute path to the Spring Boot project root", required = true) String projectPath,
            @McpToolParam(description = "Port for the target app (0 = auto-assign). Optional, default 0", required = false) Integer targetPort,
            @McpToolParam(description = "OTel service name. Optional, derived from project directory if omitted", required = false) String serviceName) {
        // targetPort 没传按 0（自动分配）；entryNodeId / instrumentationMode 这两个工具没开放的字段与 REST 路径一样是 null
        LaunchRequest request = new LaunchRequest(
                McpToolSupport.path(projectPath),
                targetPort == null ? 0 : targetPort,
                serviceName,
                null,
                null);
        return McpToolSupport.envelope("apm_start_session", apmController.launch(request));
    }

    // ==================== apm_execute_request ====================

    @McpTool(name = "apm_execute_request",
            description = "Execute an HTTP request against the target service and trigger trace collection. "
                    + "The session must be in READY or RUNNING state.")
    public Object apmExecuteRequest(
            @McpToolParam(description = "APM session ID from apm_start_session", required = true) String sessionId,
            @McpToolParam(description = "HTTP method", required = true) HttpMethod method,
            @McpToolParam(description = "Request path (e.g., /api/users)", required = true) String path,
            @McpToolParam(description = "Request body (JSON string). Optional for GET/DELETE", required = false) String body,
            @McpToolParam(description = "Request headers as key-value pairs. Optional", required = false) Map<String, String> headers) {
        if (missing(sessionId)) {
            return McpToolSupport.error("apm_execute_request", "sessionId 不能为空");
        }
        if (method == null) {
            return McpToolSupport.error("apm_execute_request", "method 不能为空");
        }
        if (missing(path)) {
            return McpToolSupport.error("apm_execute_request", "path 不能为空");
        }
        ExecuteRequest request = new ExecuteRequest(sessionId, method.name(), McpToolSupport.path(path), body, headers);
        return McpToolSupport.envelope("apm_execute_request", apmController.execute(request));
    }

    // ==================== apm_list_traces ====================

    @McpTool(name = "apm_list_traces", description = "List captured traces and spans for an APM session.")
    public Object apmListTraces(
            @McpToolParam(description = "APM session ID", required = true) String sessionId) {
        if (missing(sessionId)) {
            return McpToolSupport.error("apm_list_traces", "sessionId 不能为空");
        }
        return McpToolSupport.envelope("apm_list_traces", apmController.getSpans(sessionId));
    }

    // ==================== apm_get_trace ====================

    @McpTool(name = "apm_get_trace",
            description = "Get the full span tree for a specific trace with KG (knowledge graph) method mapping.")
    public Object apmGetTrace(
            @McpToolParam(description = "Trace ID", required = true) String traceId) {
        if (missing(traceId)) {
            return McpToolSupport.error("apm_get_trace", "traceId 不能为空");
        }
        return McpToolSupport.envelope("apm_get_trace", apmController.getTrace(traceId));
    }

    // ==================== apm_get_report ====================

    @McpTool(name = "apm_get_report",
            description = "Get a debug execution report for an APM session, including span tree, performance hotspots, "
                    + "and error points.")
    public Object apmGetReport(
            @McpToolParam(description = "APM session ID", required = true) String sessionId) {
        if (missing(sessionId)) {
            return McpToolSupport.error("apm_get_report", "sessionId 不能为空");
        }
        return McpToolSupport.envelope("apm_get_report", apmController.getReport(sessionId));
    }

    // ==================== apm_stop_session ====================

    @McpTool(name = "apm_stop_session", description = "Stop the target process and end the APM debug session.")
    public Object apmStopSession(
            @McpToolParam(description = "APM session ID to stop", required = true) String sessionId) {
        if (missing(sessionId)) {
            return McpToolSupport.error("apm_stop_session", "sessionId 不能为空");
        }
        return McpToolSupport.envelope("apm_stop_session", apmController.stop(new StopRequest(sessionId)));
    }

    // ==================== 内部辅助 ====================

    /** MCP 库不校验 required：漏传的必填参数会以 null 进来（REST 路径上路径变量不可能为空），明确报错而不是带着 null 去查。 */
    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
