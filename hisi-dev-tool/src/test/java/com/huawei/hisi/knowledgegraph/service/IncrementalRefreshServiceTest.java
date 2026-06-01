package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.exception.WorkingDirDirtyException;
import com.huawei.hisi.knowledgegraph.link.CrossServiceLinker;
import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.scanner.JavaDataModelScanner;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import com.huawei.hisi.knowledgegraph.vector.VectorWriter;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncrementalRefreshServiceTest {

    @Mock private GitStatusService gitStatusService;
    @Mock private Neo4jGenerationCheckpointRepository checkpointRepository;
    @Mock private VectorWriter vectorWriter;
    @Mock private CrossServiceLinker crossServiceLinker;
    @Mock private Neo4jMethodNodeRepository methodNodeRepository;
    @Mock private Neo4jEntryPointNodeRepository entryPointRepository;
    @Mock private PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder;
    @Mock private CodeAnalysisCoreService coreService;
    @Mock private GlobalAnalysisCache globalCache;
    @Mock private KnowledgeGraphStorageService storageService;
    @Mock private JavaDataModelScanner javaDataModelScanner;
    @Mock private Neo4jDataModelNodeRepository dataModelNodeRepository;

    private IncrementalRefreshService service;

    private static final String PROJECT_PATH = "/workspace/my-service";

    @BeforeEach
    void setUp() {
        service = new IncrementalRefreshService(
                gitStatusService, checkpointRepository, vectorWriter, crossServiceLinker,
                methodNodeRepository, entryPointRepository, pythonKnowledgeGraphBuilder,
                coreService, globalCache, storageService, javaDataModelScanner,
                dataModelNodeRepository);
    }

    @Test
    @DisplayName("refresh throws NoCheckpointException when no checkpoint exists")
    void refresh_noCheckpoint_throwsNoCheckpointException() {
        when(checkpointRepository.findByProjectPath(PROJECT_PATH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(PROJECT_PATH))
                .isInstanceOf(NoCheckpointException.class)
                .hasMessageContaining(PROJECT_PATH);
    }

    @Test
    @DisplayName("refresh propagates WorkingDirDirtyException when working directory is dirty")
    void refresh_dirtyWorkDir_throwsWorkingDirDirtyException() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(PROJECT_PATH))
                .thenReturn(Optional.of(checkpoint));
        doThrow(new WorkingDirDirtyException(PROJECT_PATH))
                .when(gitStatusService).assertClean(PROJECT_PATH);

        assertThatThrownBy(() -> service.refresh(PROJECT_PATH))
                .isInstanceOf(WorkingDirDirtyException.class);
    }

    @Test
    @DisplayName("refresh returns noop when current commit equals last commit")
    void refresh_sameCommit_returnsNoop() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(PROJECT_PATH))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(PROJECT_PATH)).thenReturn("abc123");

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.isNoop()).isTrue();
    }

    @Test
    @DisplayName("refresh deletes and rebuilds when files are modified")
    void refresh_modifiedFiles_deletesAndRebuilds() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(PROJECT_PATH))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(PROJECT_PATH)).thenReturn("def456");
        when(gitStatusService.getChangedFilesJgit(PROJECT_PATH, "abc123", "def456"))
                .thenReturn(List.of("src/Main.java"));
        when(gitStatusService.getCurrentBranch(PROJECT_PATH)).thenReturn("main");

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.isNoop()).isFalse();
        assertThat(result.changedFiles()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);

        verify(entryPointRepository).deleteByFilePathAndProjectPath("src/Main.java", PROJECT_PATH);
        verify(vectorWriter).deleteByFilePath("src/Main.java", PROJECT_PATH);
        verify(checkpointRepository).upsertCheckpoint(PROJECT_PATH, "def456", "main");
    }

    @Test
    @DisplayName("refresh calls crossServiceLinker.link after rebuild")
    void refresh_callsCrossServiceLinker() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(PROJECT_PATH))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(PROJECT_PATH)).thenReturn("def456");
        when(gitStatusService.getChangedFilesJgit(PROJECT_PATH, "abc123", "def456"))
                .thenReturn(List.of("src/Foo.java"));
        when(gitStatusService.getCurrentBranch(PROJECT_PATH)).thenReturn("main");

        service.refresh(PROJECT_PATH);

        verify(crossServiceLinker).link(List.of(PROJECT_PATH));
    }
}
