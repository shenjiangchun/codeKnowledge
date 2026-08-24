package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("类级职责三级回退 + 分层违规标疑似")
class ClassLayerRoleDetectorTest {

    private final ClassLayerRoleDetector roleDetector = new ClassLayerRoleDetector();
    private final ClassLayerViolationDetector violationDetector = new ClassLayerViolationDetector(roleDetector);

    @Test
    @DisplayName("注解优先识别")
    void annotationFirst() {
        assertThat(roleDetector.detect(List.of("RestController"), "com.foo.AnyClass", "com.foo.x"))
            .isEqualTo(ClassLayerRoleDetector.CONTROLLER);
        assertThat(roleDetector.detect(List.of("Service"), "com.foo.AnyClass", "com.foo.x"))
            .isEqualTo(ClassLayerRoleDetector.SERVICE);
        assertThat(roleDetector.detect(List.of("Repository"), "com.foo.AnyClass", "com.foo.x"))
            .isEqualTo(ClassLayerRoleDetector.REPOSITORY);
    }

    @Test
    @DisplayName("类名后缀回退")
    void classNameFallback() {
        assertThat(roleDetector.detect(List.of(), "com.foo.OrderController", "com.foo.x"))
            .isEqualTo(ClassLayerRoleDetector.CONTROLLER);
        assertThat(roleDetector.detect(List.of(), "com.foo.OrderService", "com.foo.x"))
            .isEqualTo(ClassLayerRoleDetector.SERVICE);
        assertThat(roleDetector.detect(List.of(), "com.foo.OrderRepository", "com.foo.x"))
            .isEqualTo(ClassLayerRoleDetector.REPOSITORY);
    }

    @Test
    @DisplayName("包名后缀兜底")
    void packageFallback() {
        assertThat(roleDetector.detect(List.of(), "com.foo.OrderManager", "com.foo.controller"))
            .isEqualTo(ClassLayerRoleDetector.CONTROLLER);
        assertThat(roleDetector.detect(List.of(), "com.foo.OrderManager", "com.foo.service"))
            .isEqualTo(ClassLayerRoleDetector.SERVICE);
    }

    @Test
    @DisplayName("三级均无法识别返回 UNKNOWN")
    void unknown() {
        assertThat(roleDetector.detect(List.of(), "com.foo.Mystery", "com.foo.mystery"))
            .isEqualTo(ClassLayerRoleDetector.UNKNOWN);
    }

    @Test
    @DisplayName("来源标记：注解/类名/包名/未知")
    void roleSource() {
        assertThat(roleDetector.detectWithSource(List.of("Service"), "com.foo.Any", "com.foo.x").source())
            .isEqualTo("ANNOTATION");
        assertThat(roleDetector.detectWithSource(List.of(), "com.foo.OrderController", "com.foo.x").source())
            .isEqualTo("NAME");
        assertThat(roleDetector.detectWithSource(List.of(), "com.foo.Any", "com.foo.service").source())
            .isEqualTo("PACKAGE");
        assertThat(roleDetector.detectWithSource(List.of(), "com.foo.Mystery", "com.foo.mystery").source())
            .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("类级反向依赖标疑似（service 依赖 controller）")
    void reverseDependency() {
        Map<String, String> roleMap = Map.of(
            "com.foo.OrderService", ClassLayerRoleDetector.SERVICE,
            "com.foo.OrderController", ClassLayerRoleDetector.CONTROLLER);
        var edges = List.of(new TarjanSccDetector.Edge("com.foo.OrderService", "com.foo.OrderController"));
        var violations = violationDetector.detect(edges, roleMap);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).sourceRole()).isEqualTo(ClassLayerRoleDetector.SERVICE);
        assertThat(violations.get(0).message()).contains("疑似");
    }

    @Test
    @DisplayName("正向依赖不标（controller 依赖 service）")
    void forwardDependency() {
        Map<String, String> roleMap = Map.of(
            "com.foo.OrderController", ClassLayerRoleDetector.CONTROLLER,
            "com.foo.OrderService", ClassLayerRoleDetector.SERVICE);
        var edges = List.of(new TarjanSccDetector.Edge("com.foo.OrderController", "com.foo.OrderService"));
        var violations = violationDetector.detect(edges, roleMap);
        assertThat(violations).isEmpty();
    }
}
