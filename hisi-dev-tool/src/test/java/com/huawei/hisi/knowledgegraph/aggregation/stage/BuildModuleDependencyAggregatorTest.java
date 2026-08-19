package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BuildModuleDependencyAggregator 键逻辑")
class BuildModuleDependencyAggregatorTest {

    @Test
    @DisplayName("ga 生成匹配键（不带 version）")
    void ga_buildsMatchKey() {
        assertThat(BuildModuleDependencyAggregator.ga("com.a", "app")).isEqualTo("com.a:app");
        assertThat(BuildModuleDependencyAggregator.ga(null, "app")).isEqualTo(":app");
    }

    @Test
    @DisplayName("gav 生成唯一键（带 version）")
    void gav_buildsUniqueKey() {
        assertThat(BuildModuleDependencyAggregator.gav("com.a", "app", "1.0")).isEqualTo("com.a:app:1.0");
        assertThat(BuildModuleDependencyAggregator.gav("com.a", "app", null)).isEqualTo("com.a:app:");
    }

    @Test
    @DisplayName("coordinateToGa 剥离 version")
    void coordinateToGa_stripsVersion() {
        assertThat(BuildModuleDependencyAggregator.coordinateToGa("com.a:app:1.0")).isEqualTo("com.a:app");
        assertThat(BuildModuleDependencyAggregator.coordinateToGa("com.a:app:")).isEqualTo("com.a:app");
        assertThat(BuildModuleDependencyAggregator.coordinateToGa("com.a:app")).isEqualTo("com.a:app");
    }

    @Test
    @DisplayName("同 ga 不同 version 的坐标剥离后匹配键一致")
    void differentVersion_sameMatchKey() {
        String ga1 = BuildModuleDependencyAggregator.coordinateToGa("com.foo:B:1.0");
        String ga2 = BuildModuleDependencyAggregator.coordinateToGa("com.foo:B:2.0");
        assertThat(ga1).isEqualTo(ga2).isEqualTo("com.foo:B");
    }
}
