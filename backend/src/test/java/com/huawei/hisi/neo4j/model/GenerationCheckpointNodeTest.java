package com.huawei.hisi.neo4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GenerationCheckpointNode 单元测试
 * 验证 Builder、NoArgsConstructor、getter/setter 正常工作
 */
class GenerationCheckpointNodeTest {

    @Test
    @DisplayName("Builder creates node with all fields set")
    void builder_allFields_setsCorrectly() {
        Instant now = Instant.now();
        GenerationCheckpointNode node = GenerationCheckpointNode.builder()
                .checkpointId("cp-001")
                .projectPath("/home/user/project-a")
                .lastCommit("abc123def")
                .lastBranch("main")
                .generatedAt(now)
                .build();

        assertThat(node.getCheckpointId()).isEqualTo("cp-001");
        assertThat(node.getProjectPath()).isEqualTo("/home/user/project-a");
        assertThat(node.getLastCommit()).isEqualTo("abc123def");
        assertThat(node.getLastBranch()).isEqualTo("main");
        assertThat(node.getGeneratedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("NoArgsConstructor creates empty node, setters populate fields")
    void noArgsConstructor_setters_roundTrip() {
        GenerationCheckpointNode node = new GenerationCheckpointNode();
        node.setProjectPath("/opt/project-b");
        node.setLastCommit("deadbeef");
        node.setLastBranch("feature/x");

        assertThat(node.getProjectPath()).isEqualTo("/opt/project-b");
        assertThat(node.getLastCommit()).isEqualTo("deadbeef");
        assertThat(node.getLastBranch()).isEqualTo("feature/x");
    }

    @Test
    @DisplayName("AllArgsConstructor creates node with all parameters")
    void allArgsConstructor_allFields() {
        Instant now = Instant.now();
        GenerationCheckpointNode node = new GenerationCheckpointNode(
                "cp-002", "/path/a", "commit1", "develop", now);

        assertThat(node.getCheckpointId()).isEqualTo("cp-002");
        assertThat(node.getProjectPath()).isEqualTo("/path/a");
        assertThat(node.getGeneratedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("equals and hashCode work correctly via @Data")
    void equalsAndHashCode() {
        Instant now = Instant.now();
        GenerationCheckpointNode a = GenerationCheckpointNode.builder()
                .checkpointId("id1").projectPath("/p").generatedAt(now).build();
        GenerationCheckpointNode b = GenerationCheckpointNode.builder()
                .checkpointId("id1").projectPath("/p").generatedAt(now).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
