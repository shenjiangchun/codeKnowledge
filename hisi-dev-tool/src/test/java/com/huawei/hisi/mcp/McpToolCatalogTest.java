package com.huawei.hisi.mcp;

import com.huawei.hisi.piagent.FlowToolManifest;
import com.huawei.hisi.piagent.PiAgentInstaller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内 MCP 的合同测试：暴露的工具必须与原 Node 版 MCP 服务的 40 个工具逐字一致
 * （工具名、入参名、每个入参的必填标记、入参类型与枚举取值）。agent 的提示词、skill 和工具白名单
 * 都是按这份合同写的，任何一处漂移都会让模型调不到工具、漏传参数或传了不合法的值。
 *
 * <p>类型规则（实测得出）：入参 schema 的属性名与类型直接取自 Java 形参，字符串入参只有在声明成
 * Java 枚举、且常量名与合同取值逐字相同时才会带上枚举；框架按常量名转换取值。
 * number 入参必须能装下雪花 ID（Long），Integer 会在 3.8e17 量级的 reportId 上溢出。</p>
 */
@DisplayName("进程内 MCP 工具与 Node 版合同一致")
class McpToolCatalogTest {

    private static final String MCP_PREFIX = "mcp__" + PiAgentInstaller.MCP_SERVER_NAME.replace('-', '_') + "__";
    private static final String TOOL_PACKAGE = "com.huawei.hisi.mcp";

    /** 一个入参在合同里的形态：必填、JSON 类型、枚举取值（没有枚举时为 null）。 */
    private record Param(boolean required, String type, List<String> allowed) {
    }

    /** 原 Node 版 MCP 服务真实吐出的 tools/list（2026-11-27 导出）：工具名 → （入参名 → 形态）。 */
    private static final Map<String, Map<String, Param>> CONTRACT = contract();

    static Map<String, Map<String, Param>> contract() {
        Map<String, Map<String, Param>> out = new TreeMap<>();
        out.put("act_confirm_node", params("session_id", p(true, "string"), "node_name", p(true, "string"), "action", p(true, "string", "approve", "reject"), "feedback", p(false, "string")));
        out.put("act_resume_session", params("session_id", p(true, "string")));
        out.put("act_start_analysis", params("raw_input", p(true, "string"), "project_path", p(true, "string"), "mode", p(false, "string")));
        out.put("act_start_status", params("project_path", p(true, "string"), "question", p(false, "string")));
        out.put("act_submit_clarification", params("session_id", p(true, "string"), "answers", p(true, "object")));
        out.put("apm_execute_request", params("sessionId", p(true, "string"), "method", p(true, "string", "GET", "POST", "PUT", "DELETE", "PATCH"), "path", p(true, "string"), "body", p(false, "string"), "headers", p(false, "object")));
        out.put("apm_get_report", params("sessionId", p(true, "string")));
        out.put("apm_get_trace", params("traceId", p(true, "string")));
        out.put("apm_list_traces", params("sessionId", p(true, "string")));
        out.put("apm_start_session", params("projectPath", p(true, "string"), "targetPort", p(false, "number"), "serviceName", p(false, "string")));
        out.put("apm_stop_session", params("sessionId", p(true, "string")));
        out.put("hybrid_search", params("query", p(true, "string"), "projectPath", p(false, "string"), "projectPaths", p(false, "array"), "limit", p(false, "number"), "graphDepth", p(false, "number"), "language", p(false, "string", "java", "python")));
        out.put("kg_affecting", params("className", p(true, "string"), "methodName", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_api_consumers", params("entryId", p(true, "string")));
        out.put("kg_backend_deps", params("apiClientId", p(true, "string")));
        out.put("kg_blast_radius", params("nodeId", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript"), "maxDepth", p(false, "number")));
        out.put("kg_bridge_stats", params("projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_bridges", params("nodeId", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_callees_tree", params("className", p(true, "string"), "methodName", p(true, "string"), "maxDepth", p(false, "number"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_dashboard", params("projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string")));
        out.put("kg_domains", params("projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string")));
        out.put("kg_downstream", params("nodeId", p(true, "string"), "maxDepth", p(false, "number"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_dsm", params("projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string"), "level", p(false, "string")));
        out.put("kg_entry_points", params("entryType", p(false, "string", "CONTROLLER", "SCHEDULED", "MQ_LISTENER", "FEIGN_CLIENT", "ALL"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_feign_chain", params("serviceName", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_hotspots", params("projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string"), "limit", p(false, "number")));
        out.put("kg_implementations", params("interfaceName", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_list_projects", params());
        out.put("kg_method_by_class", params("className", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_method_detail", params("nodeId", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_mq_chain", params("topic", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_mybatis_sql", params("mapperInterface", p(false, "string"), "statementType", p(false, "string", "SELECT", "INSERT", "UPDATE", "DELETE"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_root_entries", params("className", p(true, "string"), "methodName", p(true, "string"), "projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("kg_select_scope", params("action", p(true, "string", "list", "set", "get", "clear"), "projectPaths", p(false, "array")));
        out.put("kg_service_topology", params("projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string")));
        out.put("kg_status", params("projectPaths", p(true, "array"), "projectPath", p(false, "string"), "language", p(false, "string", "java", "python", "typescript")));
        out.put("log_analyze", params("message", p(false, "string"), "stackTrace", p(false, "string"), "serviceName", p(false, "string"), "traceId", p(false, "string"), "errorType", p(false, "string")));
        out.put("log_query", params("keyword", p(false, "string"), "logLevel", p(false, "string", "ERROR", "WARN", "INFO", "DEBUG"), "appId", p(false, "string"), "traceId", p(false, "string"), "contentContains", p(false, "string"), "dslQuery", p(false, "string"), "startTime", p(false, "string"), "endTime", p(false, "string"), "size", p(false, "number"), "errorOnly", p(false, "boolean"), "sortOrder", p(false, "string", "asc", "desc")));
        out.put("log_report", params("reportId", p(true, "number")));
        out.put("log_report_status", params("reportId", p(true, "number")));
        return Map.copyOf(out);
    }

    private static Map<String, Param> params(Object... kv) {
        Map<String, Param> params = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put((String) kv[i], (Param) kv[i + 1]);
        }
        return params;
    }

    private static Param p(boolean required, String type, String... allowed) {
        return new Param(required, type, allowed.length == 0 ? null : List.of(allowed));
    }

    /** 进程内某个工具方法的入参形态：入参名 → （必填、Java 类型）。 */
    private record ExposedParam(boolean required, Class<?> type) {
    }

    @Test
    @DisplayName("暴露的工具集合与每个工具的入参名、必填标记、类型、枚举取值都与合同一致")
    void exposedToolsMatchTheContractExactly() throws Exception {
        Map<String, Map<String, ExposedParam>> exposed = exposedTools();

        assertThat(exposed.keySet()).as("工具集合").isEqualTo(CONTRACT.keySet());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, Param>> expected : CONTRACT.entrySet()) {
            Map<String, ExposedParam> actual = exposed.get(expected.getKey());
            if (!actual.keySet().equals(expected.getValue().keySet())) {
                problems.add(String.format("%s 入参名不一致: 合同=%s 实际=%s", expected.getKey(),
                        expected.getValue().keySet(), actual.keySet()));
                continue;
            }
            for (Map.Entry<String, Param> e : expected.getValue().entrySet()) {
                ExposedParam a = actual.get(e.getKey());
                Param c = e.getValue();
                if (a.required() != c.required()) {
                    problems.add(String.format("%s.%s 必填标记: 合同=%s 实际=%s", expected.getKey(), e.getKey(),
                            c.required(), a.required()));
                }
                if (!typeMatches(c, a.type())) {
                    problems.add(String.format("%s.%s 类型: 合同=%s%s 实际=%s", expected.getKey(), e.getKey(),
                            c.type(), c.allowed() == null ? "" : c.allowed(), a.type().getName()));
                }
            }
        }
        assertThat(problems).as("与 Node 版合同不一致的地方").isEmpty();
    }

    @Test
    @DisplayName("每张流工具清单里的 MCP 工具都被进程内服务真实暴露")
    void everyManifestMcpToolIsExposed() throws Exception {
        Set<String> exposed = exposedTools().keySet();
        List<String> missing = new ArrayList<>();
        for (String flow : List.of("arch-react", "log-analysis", "ram", "ram-act", "chat-read",
                "workbench-read", "workbench-dev", FlowToolManifest.ONE_SHOT)) {
            for (String tool : FlowToolManifest.forFlow(flow).tools()) {
                if (tool.startsWith("mcp__") && !exposed.contains(tool.substring(MCP_PREFIX.length()))) {
                    missing.add(flow + " → " + tool);
                }
            }
        }
        assertThat(missing).as("清单里有进程内服务没有暴露的工具").isEmpty();
    }

    private static boolean typeMatches(Param expected, Class<?> actual) {
        return switch (expected.type()) {
            case "string" -> expected.allowed() == null
                    ? actual == String.class
                    : actual.isEnum() && enumConstants(actual).equals(new TreeSet<>(expected.allowed()));
            case "number", "integer" -> McpToolSupport.isNumber(actual);
            case "boolean" -> actual == Boolean.class || actual == boolean.class;
            case "array" -> McpToolSupport.isCollection(actual);
            case "object" -> Map.class.isAssignableFrom(actual);
            default -> true;
        };
    }

    private static Set<String> enumConstants(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Object constant : type.getEnumConstants()) {
            names.add(((Enum<?>) constant).name());
        }
        return names;
    }

    /** 扫描 {@link #TOOL_PACKAGE} 下所有 @Component 的 @McpTool 方法：工具名 → （入参名 → 形态）。 */
    private static Map<String, Map<String, ExposedParam>> exposedTools() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        ParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();
        Map<String, Map<String, ExposedParam>> exposed = new TreeMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(TOOL_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                McpTool mcpTool = method.getAnnotation(McpTool.class);
                if (mcpTool == null) {
                    continue;
                }
                String[] parameterNames = names.getParameterNames(method);
                Map<String, ExposedParam> params = new LinkedHashMap<>();
                for (int i = 0; i < method.getParameterCount(); i++) {
                    McpToolParam param = method.getParameters()[i].getAnnotation(McpToolParam.class);
                    if (param == null) {
                        continue; // 框架注入的上下文参数（McpSyncRequestContext）
                    }
                    params.put(parameterNames[i], new ExposedParam(param.required(), method.getParameterTypes()[i]));
                }
                Map<String, ExposedParam> previous = exposed.put(mcpTool.name(), params);
                assertThat(previous).as("工具名重复: %s", mcpTool.name()).isNull();
            }
        }
        return exposed;
    }
}
