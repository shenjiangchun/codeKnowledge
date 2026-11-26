package com.huawei.hisi.piagent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具注册清单的守卫测试：只读流不得持有任何写/执行工具，清单在代码里一处声明即可被锁死。
 */
class FlowToolManifestTest {

    @Test
    void readOnlyFlowsHaveNoWriteOrExecTools() {
        for (String flow : List.of("arch-react", "log-analysis", "ram", "workbench-read")) {
            FlowToolManifest manifest = FlowToolManifest.forFlow(flow);
            assertTrue(manifest.readOnly(), flow + " 应为只读");
            for (String tool : manifest.tools()) {
                String base = tool.replace("mcp__hisi_mcp_server__", "");
                assertFalse(base.equals("write") || base.equals("edit") || base.equals("bash")
                                || base.equals("powershell") || base.startsWith("git"),
                        flow + " 清单里出现了危险工具: " + tool);
            }
            // 白名单里必须明确列出 MCP 工具（有 mcp__ 前缀条目时 MCP 才按白名单过滤）
            assertTrue(manifest.tools().stream().anyMatch(t -> t.startsWith("mcp__")), flow + " 应带 MCP 只读工具");
        }
    }

    @Test
    void workbenchDevFlowAddsWriteToolsOnly() {
        FlowToolManifest manifest = FlowToolManifest.forFlow("workbench-dev");

        assertFalse(manifest.readOnly());
        assertTrue(manifest.tools().contains("write"));
        assertTrue(manifest.tools().contains("edit"));
        assertTrue(manifest.tools().contains("bash"));
        // git 永远不属于任何清单：提交推送由后端执行
        assertFalse(manifest.tools().contains("git"));
        assertFalse(manifest.tools().stream().anyMatch(t -> t.contains("commit") || t.contains("push")));
    }

    @Test
    void excludeListCoversDangerousBuiltinsOnReadOnlyFlows() {
        FlowToolManifest manifest = FlowToolManifest.forFlow("arch-react");
        String excludes = manifest.excludeToolsArg();
        assertTrue(excludes.contains("write"));
        assertTrue(excludes.contains("edit"));
        assertTrue(excludes.contains("bash"));
        assertTrue(excludes.contains("powershell"));
    }

    @Test
    void unknownFlowDefaultsToReadOnlyWithoutMcp() {
        FlowToolManifest manifest = FlowToolManifest.forFlow("nonsense");
        assertTrue(manifest.readOnly());
        assertFalse(manifest.useMcp());
    }

    @Test
    void oneShotCallsGetNoToolsNoMcpAndNoThinking() {
        FlowToolManifest manifest = FlowToolManifest.forFlow(FlowToolManifest.ONE_SHOT);

        assertTrue(manifest.tools().isEmpty(), "一次性结构化调用不需要任何工具");
        assertFalse(manifest.useMcp());
        assertTrue(manifest.thinkingOff());
        assertEquals(List.of("--no-tools", "--no-mcp", "--no-approve", "--no-context-files", "--thinking", "off"),
                manifest.cliArgs());
    }

    @Test
    void agentSessionsKeepTheGatewayThinkingDefault() {
        for (String flow : List.of("arch-react", "log-analysis", "ram", "workbench-read", "workbench-dev")) {
            assertFalse(FlowToolManifest.forFlow(flow).thinkingOff(), flow + " 是 agent 会话，思考与否沿用网关默认");
        }
    }

    @Test
    void cliArgsProduceAllowlistAndExcludes() {
        List<String> args = FlowToolManifest.forFlow("workbench-dev").cliArgs();
        assertEquals(List.of("--tools", "--exclude-tools"), List.of(args.get(0), args.get(2)));
        assertTrue(args.get(1).contains("mcp__hisi_mcp_server__kg_method_detail"));
        assertEquals("powershell", args.get(3));
    }

    /**
     * 清单里的 MCP 工具名必须真实存在于 hisi-mcp-server。此前对话清单里写着 {@code apm_spans}、
     * {@code apm_metrics}——MCP 服务里根本没有这两个工具，而真正的 APM 查询工具又不在清单里，
     * 结果 APM 诊断对话拿不到任何 APM 数据。以 MCP 服务源码里 {@code name: 'xxx'} 的定义为准。
     * hisi-mcp-server 不在同级目录（隔离副本里跑测试）时跳过。
     */
    @Test
    void everyMcpToolInEveryManifestExistsInTheMcpServer() throws Exception {
        List<Path> sources = mcpToolSources();
        Assumptions.assumeTrue(!sources.isEmpty(), "同级目录下没有 hisi-mcp-server 源码，跳过");
        Set<String> defined = new TreeSet<>();
        Matcher m = Pattern.compile("name:\\s*'([a-z0-9_]+)'").matcher("");
        for (Path file : sources) {
            m.reset(Files.readString(file));
            while (m.find()) {
                defined.add(m.group(1));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String flow : List.of("arch-react", "log-analysis", "ram", "ram-act", "chat-read",
                "workbench-read", "workbench-dev", FlowToolManifest.ONE_SHOT)) {
            for (String tool : FlowToolManifest.forFlow(flow).tools()) {
                if (tool.startsWith("mcp__") && !defined.contains(tool.substring("mcp__hisi_mcp_server__".length()))) {
                    missing.add(flow + " → " + tool);
                }
            }
        }
        assertTrue(missing.isEmpty(), "清单里有 MCP 服务不存在的工具: " + missing);
    }

    /**
     * MCP 服务里定义工具的源文件：入口 {@code src/index.ts}（{@code kg_select_scope} 等自带工具）
     * 加上 {@code src/tools/} 下的全部文件。从工作目录逐级向上找 hisi-mcp-server；找不到返回空列表。
     */
    private static List<Path> mcpToolSources() throws IOException {
        Path src = null;
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null && src == null; i++) {
            Path candidate = dir.resolve("hisi-mcp-server").resolve("src");
            if (Files.isDirectory(candidate.resolve("tools"))) {
                src = candidate;
            }
            dir = dir.getParent();
        }
        if (src == null) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        files.add(src.resolve("index.ts"));
        try (var tools = Files.list(src.resolve("tools"))) {
            tools.filter(p -> p.toString().endsWith(".ts")).forEach(files::add);
        }
        return files;
    }

    @Test
    void versionComparisonOrdersSemanticVersions() {
        assertTrue(PiAgentInstaller.compareVersions("22.19.0", "22.19.0") == 0);
        assertTrue(PiAgentInstaller.compareVersions("24.14.0", "22.19.0") > 0);
        assertTrue(PiAgentInstaller.compareVersions("20.11.0", "22.19.0") < 0);
        assertTrue(PiAgentInstaller.compareVersions("22.19", "22.19.0") == 0);
    }
}
