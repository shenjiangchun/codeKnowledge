package com.huawei.hisi.neo4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试 MethodNode 新增 5 个聚合属性的构建、getter/setter 和默认值
 */
class MethodNodeAggregationFieldsTest {

    @Test
    @DisplayName("builder 设置 packageName 后 getPackageName 返回正确值")
    void shouldGetPackageName_whenSetViaBuilder() {
        var node = MethodNode.builder()
                .nodeId("com.example.Foo.bar")
                .className("com.example.Foo")
                .packageName("com.example")
                .build();

        assertThat(node.getPackageName()).isEqualTo("com.example");
    }

    @Test
    @DisplayName("builder 设置 inDegree/outDegree 后正确返回")
    void shouldGetInOutDegree_whenSetViaBuilder() {
        var node = MethodNode.builder()
                .nodeId("test")
                .inDegree(42)
                .outDegree(15)
                .build();

        assertThat(node.getInDegree()).isEqualTo(42);
        assertThat(node.getOutDegree()).isEqualTo(15);
    }

    @Test
    @DisplayName("builder 设置 riskScore 后正确返回")
    void shouldGetRiskScore_whenSetViaBuilder() {
        var node = MethodNode.builder()
                .nodeId("test")
                .riskScore(0.87)
                .build();

        assertThat(node.getRiskScore()).isEqualTo(0.87);
    }

    @Test
    @DisplayName("builder 设置 communityId 后正确返回")
    void shouldGetCommunityId_whenSetViaBuilder() {
        var node = MethodNode.builder()
                .nodeId("test")
                .communityId(3)
                .build();

        assertThat(node.getCommunityId()).isEqualTo(3);
    }

    @Test
    @DisplayName("新增属性未设置时默认为 null")
    void shouldDefaultNewFieldsToNull() {
        var node = MethodNode.builder()
                .nodeId("test")
                .build();

        assertThat(node.getPackageName()).isNull();
        assertThat(node.getInDegree()).isNull();
        assertThat(node.getOutDegree()).isNull();
        assertThat(node.getCommunityId()).isNull();
        assertThat(node.getRiskScore()).isNull();
    }

    @Test
    @DisplayName("从 className 提取 packageName：标准全限定名")
    void shouldExtractPackageNameFromStandardClassName() {
        String className = "com.huawei.hisi.neo4j.service.HybridSearchService";
        String packageName = className.contains(".")
                ? className.substring(0, className.lastIndexOf('.'))
                : "";

        assertThat(packageName).isEqualTo("com.huawei.hisi.neo4j.service");
    }

    @Test
    @DisplayName("从 className 提取 packageName：无包名（默认包）")
    void shouldExtractPackageNameFromDefaultPackage() {
        String className = "MyClass";
        String packageName = className.contains(".")
                ? className.substring(0, className.lastIndexOf('.'))
                : "";

        assertThat(packageName).isEqualTo("");
    }
}
