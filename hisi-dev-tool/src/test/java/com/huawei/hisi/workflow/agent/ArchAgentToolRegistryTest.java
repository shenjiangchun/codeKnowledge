package com.huawei.hisi.workflow.agent;

import com.huawei.hisi.archdiagnosis.service.ArchSourceScanService;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 工具注册表单测：18 个只读工具注册齐全、按名可查、invoke 正确解包 ApiResponse
 * 并提取 args 参数委托到对应 KG 端点。
 */
@ExtendWith(MockitoExtension.class)
class ArchAgentToolRegistryTest {

    @Mock
    private KnowledgeGraphController kg;

    private ArchAgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ArchAgentToolRegistry(kg, new ArchSourceScanService());
    }

    @Test
    @DisplayName("注册 20 个只读工具，顺序稳定")
    void registersTwentyTools() {
        assertThat(registry.tools()).hasSize(20);
        assertThat(registry.tools()).extracting(ArchAgentTool::name).containsExactly(
                "dashboard", "dsm", "package_cycles", "module_cycles", "class_ego_net", "callees_tree",
                "downstream", "upstream", "root_entries", "method_detail", "method_by_class", "blast_radius",
                "implementations", "interfaces", "class_dependencies", "hotspots", "service_topology", "domains",
                "injection_scan", "grep_source");
    }

    @Test
    @DisplayName("无参工具：解包 ApiResponse 返回 data")
    void unpackSuccessData() {
        when(kg.getDashboard(isNull(), anyList(), isNull()))
                .thenReturn(ApiResponse.success(Map.of("healthScore", 72)));

        Object data = registry.get("dashboard").invoke(Map.of(), List.of("p1"));

        assertThat(data).isEqualTo(Map.of("healthScore", 72));
    }

    @Test
    @DisplayName("失败响应：解包为 error 摘要而不抛异常")
    void unpackError() {
        when(kg.getDashboard(isNull(), anyList(), isNull()))
                .thenReturn(ApiResponse.error(400, "bad request"));

        Object data = registry.get("dashboard").invoke(Map.of(), List.of("p1"));

        assertThat(data).isEqualTo(Map.of("error", "bad request"));
    }

    @Test
    @DisplayName("带参工具：从 args 提取 className 委托端点")
    void extractsArgsAndDelegates() {
        when(kg.getMethodsByClass("com.foo.Bar", null, List.of("p1")))
                .thenReturn(ApiResponse.success(List.of()));

        Object data = registry.get("method_by_class").invoke(Map.of("className", "com.foo.Bar"), List.of("p1"));

        assertThat(data).isEqualTo(List.of());
    }

    @Test
    @DisplayName("未知工具：get 返回 null")
    void unknownToolReturnsNull() {
        assertThat(registry.get("no_such_tool")).isNull();
    }

    @Test
    @DisplayName("injection_scan 带 injectedType：走反向查询，返回 items/total + scannedFileCount 诊断字段")
    void injectionScan_injectedType_delegatesToReverseQuery() {
        Object data = registry.get("injection_scan")
                .invoke(Map.of("injectedType", "com.foo.UserServiceImpl"), List.of("/no/such/path"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) data;
        assertThat(result).containsKey("items");
        assertThat(result).containsKey("total");
        assertThat(result).containsKey("scannedFileCount");
        assertThat(result.get("scannedFileCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("grep_source：按关键字检索源码，返回命中行与扫描计数")
    void grepSource_delegatesToTextSearch(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("com/example"));
        Files.writeString(root.resolve("com/example/A.java"),
                "package com.example;\npublic class A { void m() {} }\n");
        Files.writeString(root.resolve("com/example/B.java"),
                "package com.example;\npublic class B { A a; }\n");

        Object data = registry.get("grep_source")
                .invoke(Map.of("pattern", "A a"), List.of(root.toString()));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) data;
        assertThat(result).containsKey("scannedFileCount");
        assertThat(result.get("scannedFileCount")).isEqualTo(2);
        assertThat((Integer) result.get("total")).isGreaterThanOrEqualTo(1);
    }
}
