package com.huawei.hisi.piagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 每个 agent 流的工具注册清单 —— 本基建的核心交付物。
 *
 * <p>清单在每次流启动时解析（{@link #forFlow}），生成 pi 的 {@code --tools}/{@code --exclude-tools}
 * 白名单。规则：
 * <ul>
 *   <li>只读流（日志、架构、RAM、工作台的前三个 LLM 阶段）只拿到 READ 级工具，
 *       不包含任何写文件或执行命令的工具；</li>
 *   <li>写流（工作台 develop/tests/e2e）额外拿 {@code write/edit/bash}，且 pi 的
 *      工作目录被限定在 worktree 内；</li>
 *   <li>git（commit/push）永远不属于任何清单 —— 提交推送由后端执行；</li>
 *   <li>pi 的工具白名单只在启动时生效，会话内不可改，因此清单即权限边界。</li>
 * </ul>
 */
public final class FlowToolManifest {

    public enum Level { READ, WRITE, EXEC }

    /**
     * pi 内置只读工具。codemode 是 QuickJS 沙箱脚本（无文件系统/网络/Node API），
     * 只能调用会话白名单里的工具——它是 pi 1.6 里 MCP 工具的唯一入口，
     * 只读流带上它并不会获得写/执行能力（write/edit/bash 不在清单里）。
     */
    static final List<String> READ_BUILTIN = List.of("read", "grep", "find", "ls", "codemode");
    /** pi 内置写/执行工具（仅写流）。 */
    static final List<String> WRITE_BUILTIN = List.of("write", "edit", "bash");

    /** 架构 ReAct 用到的 MCP 只读工具（对应 ArchAgentToolRegistry 中 MCP 已覆盖的部分）。 */
    static final List<String> ARCH_MCP = List.of(
            "kg_select_scope", "kg_list_projects", "kg_status", "kg_method_detail", "kg_method_by_class",
            "kg_root_entries", "kg_callees_tree", "kg_entry_points", "kg_downstream",
            "kg_affecting", "kg_bridges", "kg_implementations", "kg_dashboard", "kg_dsm",
            "kg_hotspots", "kg_domains", "kg_service_topology", "kg_blast_radius",
            "kg_api_consumers", "kg_backend_deps", "hybrid_search");

    /** 日志分析用到的 MCP 只读工具。 */
    static final List<String> LOG_MCP = List.of(
            "hybrid_search", "kg_method_detail", "kg_method_by_class", "kg_root_entries",
            "kg_callees_tree", "kg_entry_points", "kg_downstream", "kg_affecting",
            "kg_bridges", "kg_mybatis_sql", "log_query", "log_report", "log_report_status");

    /** RAM 需求分析用到的 MCP 只读工具。 */
    static final List<String> RAM_MCP = List.of(
            "hybrid_search", "kg_method_detail", "kg_method_by_class", "kg_callees_tree",
            "kg_root_entries", "kg_entry_points", "kg_bridges", "kg_mybatis_sql");

    private static final String MCP_PREFIX = "mcp__hisi_mcp_server__";

    private final String flow;
    private final Set<String> tools = new LinkedHashSet<>();
    private final Level maxLevel;
    private final boolean useMcp;

    private FlowToolManifest(String flow, List<String> builtin, List<String> mcpTools, Level maxLevel, boolean useMcp) {
        this.flow = flow;
        this.maxLevel = maxLevel;
        this.useMcp = useMcp;
        tools.addAll(builtin);
        for (String tool : mcpTools) {
            tools.add(MCP_PREFIX + tool);
        }
    }

    /** 按流类型解析清单。未知流一律按纯只读处理（宁缺毋滥）。 */
    public static FlowToolManifest forFlow(String flow) {
        return switch (flow) {
            case "arch-react" -> new FlowToolManifest(flow, READ_BUILTIN, ARCH_MCP, Level.READ, true);
            case "log-analysis" -> new FlowToolManifest(flow, READ_BUILTIN, LOG_MCP, Level.READ, true);
            case "ram" -> new FlowToolManifest(flow, READ_BUILTIN, RAM_MCP, Level.READ, true);
            case "workbench-read" -> new FlowToolManifest(flow, READ_BUILTIN, RAM_MCP, Level.READ, true);
            case "workbench-dev" -> new FlowToolManifest(flow,
                    new ArrayList<>(java.util.stream.Stream.concat(READ_BUILTIN.stream(), WRITE_BUILTIN.stream()).toList()),
                    RAM_MCP, Level.EXEC, true);
            default -> new FlowToolManifest(flow, READ_BUILTIN, List.of(), Level.READ, false);
        };
    }

    public String flow() { return flow; }
    public boolean readOnly() { return maxLevel == Level.READ; }
    public boolean useMcp() { return useMcp; }
    public Set<String> tools() { return Set.copyOf(tools); }

    /** pi --tools 参数：全量白名单（含 mcp__ 前缀条目时 MCP 按白名单过滤）。 */
    public String toolsArg() {
        return String.join(",", tools);
    }

    /** pi --exclude-tools 参数：写流才为空；只读流防御性排除全部写/执行工具（MCP 只有只读工具，保留）。 */
    public String excludeToolsArg() {
        if (readOnly()) {
            return "write,edit,bash,powershell";
        }
        return "powershell";
    }

    /**
     * 生成 pi 启动参数里的工具相关片段。
     *
     * <p>{@code --no-approve}：不信任工作目录里的项目级文件（pi 的扩展、skill、提示模板都从那里自动发现，
     * 被分析的项目或待修复的 worktree 不该因此获得执行代码的机会；需要的 skill 由流显式传入）。
     * 只读流再加 {@code --no-context-files}：不把项目里的 AGENTS.md / CLAUDE.md 读进系统上下文——
     * 那是来自被分析仓库的不可信文本。
     */
    public List<String> cliArgs() {
        List<String> args = new ArrayList<>();
        args.add("--tools");
        args.add(toolsArg());
        args.add("--exclude-tools");
        args.add(excludeToolsArg());
        if (!useMcp()) {
            args.add("--no-mcp");
        }
        args.add("--no-approve");
        if (readOnly()) {
            args.add("--no-context-files");
        }
        return args;
    }
}
