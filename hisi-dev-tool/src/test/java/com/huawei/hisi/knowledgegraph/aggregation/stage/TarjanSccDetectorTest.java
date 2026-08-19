package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tarjan SCC 环检测 + 循环依赖分级")
class TarjanSccDetectorTest {

    private final TarjanSccDetector detector = new TarjanSccDetector();
    private final CycleClassifier classifier = new CycleClassifier();

    private TarjanSccDetector.Edge e(String s, String t) {
        return new TarjanSccDetector.Edge(s, t);
    }

    @Test
    @DisplayName("直接双向环 A↔B 检测为一个簇")
    void directCycle() {
        var clusters = detector.detect(List.of(e("A", "B"), e("B", "A")));
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).nodes()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    @DisplayName("多节点环 A→B→C→A 检测为一个簇")
    void multiNodeCycle() {
        var clusters = detector.detect(List.of(e("A", "B"), e("B", "C"), e("C", "A")));
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).nodes()).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    @DisplayName("无环 DAG 不产生簇")
    void dagNoCycle() {
        var clusters = detector.detect(List.of(e("A", "B"), e("B", "C")));
        assertThat(clusters).isEmpty();
    }

    @Test
    @DisplayName("自环检测")
    void selfLoop() {
        var clusters = detector.detect(List.of(e("A", "A")));
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).nodes()).containsExactly("A");
    }

    @Test
    @DisplayName("包级跨层环必报")
    void packageCrossLayerCycle() {
        var clusters = detector.detect(List.of(e("controller", "service"), e("service", "controller")));
        Map<String, String> role = Map.of("controller", "CONTROLLER", "service", "SERVICE");
        var result = classifier.classifyPackageCycles(clusters, role);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).level()).isEqualTo("CROSS_LAYER");
    }

    @Test
    @DisplayName("包级同层环降级提示")
    void packageSameLayerCycle() {
        var clusters = detector.detect(List.of(e("util1", "util2"), e("util2", "util1")));
        Map<String, String> role = Map.of("util1", "UTILITY", "util2", "UTILITY");
        var result = classifier.classifyPackageCycles(clusters, role);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).level()).isEqualTo("SAME_LAYER");
    }

    @Test
    @DisplayName("module 级环定性坏味道")
    void moduleCycle() {
        var clusters = detector.detect(List.of(e("com.a:app", "com.b:common"), e("com.b:common", "com.a:app")));
        var result = classifier.classifyModuleCycles(clusters);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).level()).isEqualTo("MODULE");
    }
}
