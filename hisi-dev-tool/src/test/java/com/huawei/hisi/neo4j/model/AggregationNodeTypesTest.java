package com.huawei.hisi.neo4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试新增 4 种 Neo4j 节点类型的 POJO 构建和基本属性
 */
class AggregationNodeTypesTest {

    @Test
    @DisplayName("ChurnNode builder 设置所有属性后正确返回")
    void shouldBuildChurnNode() {
        var node = ChurnNode.builder()
                .nodeId("proj:/src/main/Foo.java")
                .filePath("/src/main/Foo.java")
                .commitCount90d(23)
                .linesChanged90d(450)
                .lastCommitAt("2026-08-01")
                .authorCount90d(3)
                .projectPath("proj")
                .build();

        assertThat(node.getNodeId()).isEqualTo("proj:/src/main/Foo.java");
        assertThat(node.getCommitCount90d()).isEqualTo(23);
        assertThat(node.getLinesChanged90d()).isEqualTo(450);
        assertThat(node.getAuthorCount90d()).isEqualTo(3);
        assertThat(node.getProjectPath()).isEqualTo("proj");
    }

    @Test
    @DisplayName("ModuleNode builder 设置所有属性后正确返回")
    void shouldBuildModuleNode() {
        var node = ModuleNode.builder()
                .moduleId("proj:com.example.service")
                .moduleName("com.example.service")
                .level("package")
                .methodCount(47)
                .classCount(12)
                .entryPointCount(8)
                .avgComplexity(8.3)
                .inDegree(156)
                .outDegree(23)
                .instability(0.128)
                .layerRole("SERVICE")
                .projectPath("proj")
                .language("java")
                .springRoles("{\"@Service\":12}")
                .build();

        assertThat(node.getModuleName()).isEqualTo("com.example.service");
        assertThat(node.getLevel()).isEqualTo("package");
        assertThat(node.getMethodCount()).isEqualTo(47);
        assertThat(node.getInstability()).isEqualTo(0.128);
        assertThat(node.getLayerRole()).isEqualTo("SERVICE");
    }

    @Test
    @DisplayName("DomainNode builder 设置所有属性后正确返回")
    void shouldBuildDomainNode() {
        var node = DomainNode.builder()
                .domainId("proj:community-0")
                .domainName("订单域")
                .confidence(0.87)
                .packageRoots(List.of("com.example.order"))
                .methodCount(327)
                .classCount(72)
                .entryPoints(List.of("POST /api/orders"))
                .projectPath("proj")
                .build();

        assertThat(node.getDomainName()).isEqualTo("订单域");
        assertThat(node.getConfidence()).isEqualTo(0.87);
        assertThat(node.getPackageRoots()).containsExactly("com.example.order");
        assertThat(node.getMethodCount()).isEqualTo(327);
    }

    @Test
    @DisplayName("AggregationCheckpoint builder 设置所有属性后正确返回")
    void shouldBuildAggregationCheckpoint() {
        var cp = AggregationCheckpoint.builder()
                .checkpointId("proj:ModuleStats")
                .projectPath("proj")
                .stageName("ModuleStats")
                .status("SUCCESS")
                .lastSuccessAt("2026-08-11T20:00:00")
                .errorMessage(null)
                .dataHash("abc123")
                .build();

        assertThat(cp.getCheckpointId()).isEqualTo("proj:ModuleStats");
        assertThat(cp.getStageName()).isEqualTo("ModuleStats");
        assertThat(cp.getStatus()).isEqualTo("SUCCESS");
        assertThat(cp.getErrorMessage()).isNull();
        assertThat(cp.getDataHash()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("AggregationCheckpoint FAILED 状态包含错误信息")
    void shouldBuildFailedAggregationCheckpoint() {
        var cp = AggregationCheckpoint.builder()
                .checkpointId("proj:Hotspot")
                .projectPath("proj")
                .stageName("Hotspot")
                .status("FAILED")
                .errorMessage("git log timeout after 30s")
                .build();

        assertThat(cp.getStatus()).isEqualTo("FAILED");
        assertThat(cp.getErrorMessage()).isEqualTo("git log timeout after 30s");
    }

    @Test
    @DisplayName("ModuleNode instability 为 NaN 时仍可存储")
    void shouldHandleZeroDegreeModule() {
        var node = ModuleNode.builder()
                .moduleId("proj:com.example.isolated")
                .moduleName("com.example.isolated")
                .inDegree(0)
                .outDegree(0)
                .instability(Double.NaN)  // 0/0
                .build();

        assertThat(node.getInDegree()).isZero();
        assertThat(node.getOutDegree()).isZero();
    }
}
