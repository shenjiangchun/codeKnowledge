package com.huawei.hisi.workflow.agent;

import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        registry = new ArchAgentToolRegistry(kg);
    }

    @Test
    @DisplayName("注册 18 个只读工具，顺序稳定")
    void registersEighteenTools() {
        assertThat(registry.tools()).hasSize(18);
        assertThat(registry.tools()).extracting(ArchAgentTool::name).containsExactly(
                "dashboard", "dsm", "package_cycles", "module_cycles", "class_ego_net", "callees_tree",
                "downstream", "upstream", "root_entries", "method_detail", "method_by_class", "blast_radius",
                "implementations", "interfaces", "class_dependencies", "hotspots", "service_topology", "domains");
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
}
