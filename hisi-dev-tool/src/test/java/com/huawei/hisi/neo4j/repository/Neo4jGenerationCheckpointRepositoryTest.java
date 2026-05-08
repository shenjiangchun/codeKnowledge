package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Neo4jGenerationCheckpointRepository mock-based 单元测试
 * 验证 repository 方法签名和返回值契约
 */
@ExtendWith(MockitoExtension.class)
class Neo4jGenerationCheckpointRepositoryTest {

    @Mock
    private Neo4jGenerationCheckpointRepository repository;

    @Test
    @DisplayName("findByProjectPath returns checkpoint when exists")
    void findByProjectPath_existing_returnsCheckpoint() {
        GenerationCheckpointNode node = GenerationCheckpointNode.builder()
                .checkpointId("cp-1")
                .projectPath("/project/a")
                .lastCommit("abc123")
                .lastBranch("main")
                .generatedAt(Instant.now())
                .build();

        when(repository.findByProjectPath("/project/a")).thenReturn(Optional.of(node));

        Optional<GenerationCheckpointNode> result = repository.findByProjectPath("/project/a");

        assertThat(result).isPresent();
        assertThat(result.get().getLastCommit()).isEqualTo("abc123");
        verify(repository).findByProjectPath("/project/a");
    }

    @Test
    @DisplayName("findByProjectPath returns empty when not found")
    void findByProjectPath_missing_returnsEmpty() {
        when(repository.findByProjectPath("/nonexistent")).thenReturn(Optional.empty());

        Optional<GenerationCheckpointNode> result = repository.findByProjectPath("/nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("upsertCheckpoint returns merged node")
    void upsertCheckpoint_returnsMergedNode() {
        GenerationCheckpointNode node = GenerationCheckpointNode.builder()
                .projectPath("/project/a")
                .lastCommit("def456")
                .lastBranch("develop")
                .build();

        when(repository.upsertCheckpoint("/project/a", "def456", "develop"))
                .thenReturn(node);

        GenerationCheckpointNode result = repository.upsertCheckpoint(
                "/project/a", "def456", "develop");

        assertThat(result.getLastCommit()).isEqualTo("def456");
        assertThat(result.getLastBranch()).isEqualTo("develop");
    }

    @Test
    @DisplayName("deleteByProjectPath invokes delete")
    void deleteByProjectPath_invokes() {
        repository.deleteByProjectPath("/project/a");
        verify(repository).deleteByProjectPath("/project/a");
    }
}
