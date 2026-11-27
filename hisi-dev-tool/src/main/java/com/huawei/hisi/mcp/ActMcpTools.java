package com.huawei.hisi.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内 MCP 的接口写工具（RAM 对话助手的动作）：以用户身份调后端接口发起分析、提交澄清、确认节点。
 *
 * <p>MCP 的边界是“只封装我们自己接口能做的事”——这里是接口的写操作；文件读写、执行命令一律用 pi 的原生工具，
 * 不进 MCP。写工具必须带用户令牌，绝不以匿名身份执行：令牌由 pi 随请求头带入，端点过滤器按 MCP 会话记下
 * （{@link McpRequestContext}），这里用会话 ID 取出，再以 Bearer 调后端自己的 REST 接口。
 *
 * <p>之所以仍然走回环 HTTP 而不是直接调 controller：这几个接口背后是鉴权过滤器、异步派发与 SSE，
 * 走接口才能与页面上的行为完全一致，也才真正用到令牌里的用户身份。</p>
 *
 * <p>参数名沿用 Node 版的 snake_case（只有这 5 个工具是 snake_case），对模型一侧透明。</p>
 */
@Component
public class ActMcpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DENIED = "缺少用户令牌：动作工具不能以匿名身份执行";

    private final McpRequestContext requestContext;
    private final RestClient restClient;

    public ActMcpTools(McpRequestContext requestContext,
                       @Value("${server.port:8080}") int serverPort) {
        this.requestContext = requestContext;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(120_000);
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:" + serverPort)
                .requestFactory(factory)
                .build();
    }

    @McpTool(name = "act_start_analysis",
            description = "以用户身份发起一次需求分析（RAM 需求分析大师）。用户明确要求分析某个需求时使用。"
                    + "参数 raw_input 为需求原文，project_path 为项目绝对路径，mode 默认 interactive。"
                    + "返回 session_id，后续用 act_submit_clarification / act_confirm_node 与它交互。")
    public Object actStartAnalysis(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "需求原文", required = true) String raw_input,
            @McpToolParam(description = "项目绝对路径", required = true) String project_path,
            @McpToolParam(description = "interactive（默认）", required = false) String mode) {
        String token = userToken(ctx, "act_start_analysis");
        if (token == null) {
            return McpToolSupport.error("act_start_analysis", DENIED);
        }
        // 与 Node 版一致：mode 在入参里有声明，但接口不支持，不发；接口按 interactive 处理
        String projectPath = project_path == null ? "" : project_path;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rawInput", raw_input == null ? "" : raw_input);
        body.put("projectPath", projectPath);
        body.put("projectPaths", List.of(projectPath));
        return post("act_start_analysis", token, "/api/ram/sessions", body);
    }

    @McpTool(name = "act_start_status",
            description = "以用户身份发起一次项目现状分析。用户要求了解项目现状/健康度时使用。返回 session_id。")
    public Object actStartStatus(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "项目绝对路径", required = true) String project_path,
            @McpToolParam(description = "要回答的问题（可选）", required = false) String question) {
        String token = userToken(ctx, "act_start_status");
        if (token == null) {
            return McpToolSupport.error("act_start_status", DENIED);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectPath", project_path == null ? "" : project_path);
        body.put("question", question == null ? "" : question);
        body.put("mode", "full");
        return post("act_start_status", token, "/api/ram/status/start", body);
    }

    @McpTool(name = "act_resume_session", description = "继续一个等待中的 RAM 会话（例如确认门或澄清之后）。")
    public Object actResumeSession(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "会话 id（分析接口返回的那个）", required = true) String session_id) {
        String token = userToken(ctx, "act_resume_session");
        if (token == null) {
            return McpToolSupport.error("act_resume_session", DENIED);
        }
        return post("act_resume_session", token, "/api/ram/sessions/" + enc(session_id) + "/resume", Map.of());
    }

    @McpTool(name = "act_submit_clarification",
            description = "回答需求分析器提出的澄清问题。answers 是「问题原文 → 回答」的映射；只提交用户明确给出的答案，不要替用户编。")
    public Object actSubmitClarification(
            McpSyncRequestContext ctx,
            @McpToolParam(required = true) String session_id,
            @McpToolParam(description = "问题 → 回答 映射", required = true) Map<String, Object> answers) {
        String token = userToken(ctx, "act_submit_clarification");
        if (token == null) {
            return McpToolSupport.error("act_submit_clarification", DENIED);
        }
        return post("act_submit_clarification", token,
                "/api/ram/sessions/" + enc(session_id) + "/clarify", Map.of("answers", answers == null ? Map.of() : answers));
    }

    /** 确认动作。合同里的取值就是 approve / reject，常量名必须与取值逐字相同（框架按常量名转换）。 */
    public enum Action { approve, reject }

    @McpTool(name = "act_confirm_node",
            description = "确认或驳回需求分析某个节点的产出。action 为 approve 或 reject；驳回时 feedback 说明原因。"
                    + "用户在页面上说「确认/继续/驳回」时使用。")
    public Object actConfirmNode(
            McpSyncRequestContext ctx,
            @McpToolParam(required = true) String session_id,
            @McpToolParam(required = true) String node_name,
            @McpToolParam(required = true) Action action,
            @McpToolParam(description = "驳回时的原因（可选）", required = false) String feedback) {
        String token = userToken(ctx, "act_confirm_node");
        if (token == null) {
            return McpToolSupport.error("act_confirm_node", DENIED);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeName", node_name == null ? "" : node_name);
        body.put("action", action == null ? "approve" : action.name());
        body.put("feedback", feedback);
        return post("act_confirm_node", token, "/api/ram/sessions/" + enc(session_id) + "/confirm", body);
    }

    /** 当前 MCP 会话对应的用户令牌；只读会话与匿名调用都拿不到。 */
    private String userToken(McpSyncRequestContext ctx, String tool) {
        return ctx == null ? null : requestContext.token(ctx.sessionId());
    }

    /** 以用户身份 POST 后端接口，返回解析后的 JSON；接口报错或业务失败都转成带原因的 MCP 错误。 */
    private Object post(String tool, String token, String path, Object body) {
        try {
            String json = restClient.post()
                    .uri(URI.create(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (json == null || json.isBlank()) {
                return Map.of("success", true);
            }
            Object parsed = MAPPER.readValue(json, Object.class);
            // 后端通用信封在业务失败时 HTTP 层仍是 200：必须转成 MCP 错误，否则 agent 会把失败当成功
            if (parsed instanceof Map<?, ?> map && map.get("code") instanceof Number code && code.intValue() != 200) {
                return McpToolSupport.error(tool, String.valueOf(map.get("message")));
            }
            return parsed;
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            if (detail != null && detail.length() > 512) {
                detail = detail.substring(0, 512) + "...(truncated)";
            }
            return McpToolSupport.error(tool, "HTTP " + e.getStatusCode().value() + ": " + detail);
        } catch (Exception e) {
            return McpToolSupport.error(tool, e.getMessage());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
