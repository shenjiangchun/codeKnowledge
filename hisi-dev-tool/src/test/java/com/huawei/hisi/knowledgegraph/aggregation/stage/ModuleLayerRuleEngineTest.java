package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.neo4j.model.ModuleNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("module 分层规则引擎")
class ModuleLayerRuleEngineTest {

    private final ModuleLayerRoleDetector roleDetector = new ModuleLayerRoleDetector();
    private final ModuleLayerRuleEngine engine = new ModuleLayerRuleEngine(roleDetector);

    private ModuleNode node(String ga, String artifactId) {
        return ModuleNode.builder().moduleName(ga).artifactId(artifactId).build();
    }

    @Test
    @DisplayName("命名约定识别职责")
    void roleDetector_recognizesBySuffix() {
        assertThat(roleDetector.detect("user-model")).isEqualTo(1);
        assertThat(roleDetector.detect("user-client")).isEqualTo(2);
        assertThat(roleDetector.detect("user-service")).isEqualTo(3);
        assertThat(roleDetector.detect("api-gw")).isEqualTo(5);
        assertThat(roleDetector.detect("mystery-thing")).isEqualTo(ModuleLayerRoleDetector.UNKNOWN);
    }

    @Test
    @DisplayName("反向依赖违规 model→client")
    void reverseDependency_violation() {
        var nodes = List.of(node("com.a:model", "user-model"), node("com.a:client", "user-client"));
        var edges = List.of(new BuildModuleGraphAssembler.BuildEdge("com.a:model", "com.a:client"));

        var violations = engine.detect(edges, nodes);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).type()).isEqualTo("REVERSE");
    }

    @Test
    @DisplayName("正常分层 client→model 不违规")
    void normalLayering_noViolation() {
        var nodes = List.of(node("com.a:client", "user-client"), node("com.a:model", "user-model"));
        var edges = List.of(new BuildModuleGraphAssembler.BuildEdge("com.a:client", "com.a:model"));

        var violations = engine.detect(edges, nodes);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("网关依赖下游不违规")
    void gatewayDependsDownstream_noViolation() {
        var nodes = List.of(node("com.a:gw", "api-gw"), node("com.a:client", "user-client"));
        var edges = List.of(new BuildModuleGraphAssembler.BuildEdge("com.a:gw", "com.a:client"));

        var violations = engine.detect(edges, nodes);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("相对层级约束矛盾（未知 module 下界>上界）")
    void relativeConstraint_contradiction() {
        var nodes = List.of(
            node("com.a:x", "mystery-thing"),     // 未知
            node("com.a:client", "user-client"),  // L2
            node("com.a:model", "user-model"));   // L1
        // 未知 X 依赖 client(2)，被 model(1) 依赖 → 下界2 > 上界1
        var edges = List.of(
            new BuildModuleGraphAssembler.BuildEdge("com.a:x", "com.a:client"),
            new BuildModuleGraphAssembler.BuildEdge("com.a:model", "com.a:x"));

        var violations = engine.detect(edges, nodes);

        assertThat(violations).extracting(ModuleLayerRuleEngine.Violation::type)
            .contains("CONTRADICTION");
    }

    @Test
    @DisplayName("相对层级约束一致不误报")
    void relativeConstraint_consistent() {
        var nodes = List.of(
            node("com.a:x", "mystery-thing"),
            node("com.a:model", "user-model"),    // L1
            node("com.a:service", "user-service")); // L3
        // 未知 X 依赖 model(1)，被 service(3) 依赖 → 下界1 <= 上界3
        var edges = List.of(
            new BuildModuleGraphAssembler.BuildEdge("com.a:x", "com.a:model"),
            new BuildModuleGraphAssembler.BuildEdge("com.a:service", "com.a:x"));

        var violations = engine.detect(edges, nodes);

        assertThat(violations).extracting(ModuleLayerRuleEngine.Violation::type)
            .doesNotContain("CONTRADICTION");
    }
}
