package com.huawei.hisi.knowledgegraph.vector;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FrontendNodeDescriptionBuilder")
class FrontendNodeDescriptionBuilderTest {

    @Test
    @DisplayName("Component 描述 = 组件名 + 描述")
    void component_combinesNameAndDescription() {
        ComponentNode node = ComponentNode.builder().componentName("Home").description("首页组件").build();
        assertThat(FrontendNodeDescriptionBuilder.forComponent(node)).contains("Home", "首页组件");
    }

    @Test
    @DisplayName("Component 无描述时不拼接冒号")
    void component_withoutDescription() {
        ComponentNode node = ComponentNode.builder().componentName("Home").build();
        assertThat(FrontendNodeDescriptionBuilder.forComponent(node)).isEqualTo("组件 Home");
    }

    @Test
    @DisplayName("Route 描述 = 路径 + 目标组件")
    void route_combinesPathAndComponent() {
        FrontendRouteNode node = FrontendRouteNode.builder().path("/home").componentName("Home").build();
        assertThat(FrontendNodeDescriptionBuilder.forRoute(node)).contains("/home", "Home");
    }

    @Test
    @DisplayName("ApiClient 描述 = method + URL + 源文件 + 组件")
    void apiClient_combinesAllFields() {
        ApiClientNode node = ApiClientNode.builder()
            .method("GET").url("/api/x").sourceFile("src/x.ts").componentName("Home").build();
        String desc = FrontendNodeDescriptionBuilder.forApiClient(node);
        assertThat(desc).contains("GET", "/api/x", "src/x.ts", "Home");
    }
}
