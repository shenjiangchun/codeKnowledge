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
    void cliArgsProduceAllowlistAndExcludes() {
        List<String> args = FlowToolManifest.forFlow("workbench-dev").cliArgs();
        assertEquals(List.of("--tools", "--exclude-tools"), List.of(args.get(0), args.get(2)));
        assertTrue(args.get(1).contains("mcp__hisi_mcp_server__kg_method_detail"));
        assertEquals("powershell", args.get(3));
    }

    @Test
    void versionComparisonOrdersSemanticVersions() {
        assertTrue(PiAgentInstaller.compareVersions("22.19.0", "22.19.0") == 0);
        assertTrue(PiAgentInstaller.compareVersions("24.14.0", "22.19.0") > 0);
        assertTrue(PiAgentInstaller.compareVersions("20.11.0", "22.19.0") < 0);
        assertTrue(PiAgentInstaller.compareVersions("22.19", "22.19.0") == 0);
    }
}
