package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.neo4j.model.ModuleNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BuildModuleGraphAssembler 拼边")
class BuildModuleGraphAndCycleTest {

    private final BuildModuleGraphAssembler assembler = new BuildModuleGraphAssembler();

    private ModuleNode module(String ga, String version, List<String> deps) {
        return ModuleNode.builder()
            .moduleId(ga + ":" + version)
            .moduleName(ga)
            .level("build-module")
            .version(version)
            .dependencyCoordinates(deps)
            .build();
    }

    @Test
    @DisplayName("坐标匹配拼边")
    void assemblesEdgeByCoordinateMatch() {
        ModuleNode a = module("com.a:app", "1.0", List.of("com.b:common:2.0"));
        ModuleNode b = module("com.b:common", "2.0", List.of());

        var graph = assembler.assemble(List.of(a, b));

        assertThat(graph.edges()).containsExactlyInAnyOrder(
            new BuildModuleGraphAssembler.BuildEdge("com.a:app", "com.b:common"));
    }

    @Test
    @DisplayName("version 差异仍拼边")
    void assemblesEdgeDespiteVersionDiff() {
        ModuleNode a = module("com.a:app", "1.0", List.of("com.b:common:1.0"));
        ModuleNode b = module("com.b:common", "2.0", List.of());

        var graph = assembler.assemble(List.of(a, b));

        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).target()).isEqualTo("com.b:common");
    }

    @Test
    @DisplayName("第三方库不拼边")
    void thirdPartyDoesNotAssemble() {
        ModuleNode a = module("com.a:app", "1.0", List.of("org.springframework:spring-web:6.0"));

        var graph = assembler.assemble(List.of(a));

        assertThat(graph.edges()).isEmpty();
    }
}
