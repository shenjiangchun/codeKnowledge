package com.huawei.hisi.piagent;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertTrue(args.get(1).contains("mcp__hisi_mcp_inproc__kg_method_detail"));
        assertEquals("powershell", args.get(3));
    }

    // 清单里的 MCP 工具必须真实存在——由 McpToolCatalogTest 扫描进程内 @McpTool 方法与 Node 版合同逐字核对

    @Test
    void versionComparisonOrdersSemanticVersions() {
        assertTrue(PiAgentInstaller.compareVersions("22.19.0", "22.19.0") == 0);
        assertTrue(PiAgentInstaller.compareVersions("24.14.0", "22.19.0") > 0);
        assertTrue(PiAgentInstaller.compareVersions("20.11.0", "22.19.0") < 0);
        assertTrue(PiAgentInstaller.compareVersions("22.19", "22.19.0") == 0);
    }
}
