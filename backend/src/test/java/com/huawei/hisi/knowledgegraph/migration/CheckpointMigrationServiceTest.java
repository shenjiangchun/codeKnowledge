package com.huawei.hisi.knowledgegraph.migration;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckpointMigrationServiceTest {

    @Mock
    private GenerationTaskRepository taskRepository;

    @Mock
    private Neo4jGenerationCheckpointRepository checkpointRepository;

    private CheckpointMigrationService migrationService;

    @BeforeEach
    void setUp() {
        migrationService = new CheckpointMigrationService(taskRepository, checkpointRepository);
    }

    @Test
    @DisplayName("extractCommitHash: 'commit:' prefix extracts hash")
    void extractCommitHash_commitPrefix_extractsHash() {
        String hash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        String result = CheckpointMigrationService.extractCommitHash("commit:" + hash);
        assertThat(result).isEqualTo(hash);
    }

    @Test
    @DisplayName("extractCommitHash: raw 40-char hex returns hash")
    void extractCommitHash_rawHash_extractsHash() {
        String hash = "abcdef1234567890abcdef1234567890abcdef12";
        String result = CheckpointMigrationService.extractCommitHash(hash);
        assertThat(result).isEqualTo(hash);
    }

    @Test
    @DisplayName("extractCommitHash: embedded hash in error message is found")
    void extractCommitHash_embeddedHash_extractsHash() {
        String hash = "abcdef1234567890abcdef1234567890abcdef12";
        String message = "error at commit " + hash + " during build";
        String result = CheckpointMigrationService.extractCommitHash(message);
        assertThat(result).isEqualTo(hash);
    }

    @Test
    @DisplayName("extractCommitHash: message without hash returns null")
    void extractCommitHash_noHash_returnsNull() {
        assertThat(CheckpointMigrationService.extractCommitHash("some error message")).isNull();
        assertThat(CheckpointMigrationService.extractCommitHash(null)).isNull();
        assertThat(CheckpointMigrationService.extractCommitHash("")).isNull();
    }

    @Test
    @DisplayName("migrate creates checkpoints for KG tasks with commit info")
    void migrate_createsCheckpoints() {
        String hash = "abcdef1234567890abcdef1234567890abcdef12";
        GenerationTask task = GenerationTask.builder()
                .id(1L)
                .taskType("KG")
                .projectPath("/projects/myapp")
                .status("COMPLETED")
                .errorMessage("commit:" + hash)
                .build();

        when(taskRepository.findByTaskType("KG")).thenReturn(List.of(task));
        when(checkpointRepository.findByProjectPath("/projects/myapp"))
                .thenReturn(Optional.empty());

        int result = migrationService.migrate();

        assertThat(result).isEqualTo(1);
        verify(checkpointRepository).upsertCheckpoint(
                eq("/projects/myapp"),
                eq(hash),
                eq("unknown")
        );
    }
}
