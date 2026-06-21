package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
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

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncrementalRefreshServiceV2Test {

    @Mock private GlobalAnalysisCache globalCache;
    @Mock private CodeAnalysisCoreService coreService;
    @Mock private GitStatusService gitStatusService;
    @Mock private Neo4jMethodNodeRepository methodNodeRepository;
    @Mock private Neo4jGenerationCheckpointRepository checkpointRepository;
    @Mock private KnowledgeGraphStorageService storageService;
    @Mock private KnowledgeGraphBuilder knowledgeGraphBuilder;
    @Mock private PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder;
    @Mock private Neo4jEntryPointNodeRepository entryPointRepository;
    @Mock private PythonCallGraphResolver pythonCallGraphResolver;

    private IncrementalRefreshServiceV2 service;

    private static final String PROJECT_PATH = "/workspace/my-service";

    @BeforeEach
    void setUp() {
        service = new IncrementalRefreshServiceV2(
                globalCache, coreService, gitStatusService, methodNodeRepository,
                checkpointRepository, storageService, knowledgeGraphBuilder,
                pythonKnowledgeGraphBuilder, entryPointRepository, pythonCallGraphResolver);
    }

    @Test
    @DisplayName("refresh returns noop when no checkpoint exists")
    void refresh_noCheckpoint_returnsNoop() {
        when(checkpointRepository.findByProjectPath(anyString()))
                .thenReturn(Optional.empty());

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.success()).isTrue();
        assertThat(result.projectPath()).isNull();
        assertThat(result.changedFiles()).isEqualTo(0);
    }

    @Test
    @DisplayName("refresh returns noop when current commit equals last commit")
    void refresh_sameCommit_returnsNoop() {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(anyString()))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(anyString())).thenReturn("abc123");

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.success()).isTrue();
        assertThat(result.changedFiles()).isEqualTo(0);
    }

    @Test
    @DisplayName("refresh returns success with zero changes when no Java or Python files changed")
    void refresh_noCodeFilesChanged_returnsSuccessWithZero() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(anyString()))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(anyString())).thenReturn("def456");
        when(gitStatusService.getChangedFilesJgit(anyString(), anyString(), anyString()))
                .thenReturn(List.of("README.md", "docs/guide.txt"));

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.success()).isTrue();
        assertThat(result.changedFiles()).isEqualTo(2);
        assertThat(result.rebuiltNodes()).isEqualTo(0);
        assertThat(result.rebuiltEdges()).isEqualTo(0);
    }

    @Test
    @DisplayName("refresh processes Java files when changed")
    void refresh_javaFilesChanged_processesJavaFiles() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(anyString()))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(anyString())).thenReturn("def456");
        when(gitStatusService.getChangedFilesJgit(anyString(), anyString(), anyString()))
                .thenReturn(List.of("src/Main.java"));
        when(methodNodeRepository.findByProjectPathAndFilePath(anyString(), anyString()))
                .thenReturn(List.of());
        when(globalCache.getTypeSolver()).thenReturn(null);

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.success()).isTrue();
        assertThat(result.changedFiles()).isEqualTo(1);
        // Entry points should be deleted for changed file
        verify(entryPointRepository).deleteByFilePathAndProjectPath(anyString(), anyString());
    }

    @Test
    @DisplayName("refresh processes Python files when changed")
    void refresh_pythonFilesChanged_processesPythonFiles() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(anyString()))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(anyString())).thenReturn("def456");
        when(gitStatusService.getChangedFilesJgit(anyString(), anyString(), anyString()))
                .thenReturn(List.of("app/main.py"));
        when(methodNodeRepository.findByProjectPathAndFilePath(anyString(), anyString()))
                .thenReturn(List.of());

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.success()).isTrue();
        assertThat(result.changedFiles()).isEqualTo(1);
        // Entry points should be deleted for changed file
        verify(entryPointRepository).deleteByFilePathAndProjectPath(anyString(), anyString());
    }

    @Test
    @DisplayName("refresh handles IOException gracefully")
    void refresh_ioException_returnsFailureResult() throws Exception {
        GenerationCheckpointNode checkpoint = new GenerationCheckpointNode();
        checkpoint.setLastCommit("abc123");
        when(checkpointRepository.findByProjectPath(anyString()))
                .thenReturn(Optional.of(checkpoint));
        when(gitStatusService.getCurrentCommitHash(anyString())).thenReturn("def456");
        when(gitStatusService.getChangedFilesJgit(anyString(), anyString(), anyString()))
                .thenThrow(new IOException("Git error"));

        var result = service.refresh(PROJECT_PATH);

        assertThat(result.success()).isFalse();
        assertThat(result.changedFiles()).isEqualTo(0);
    }

    @Test
    @DisplayName("debugCheckpoint returns null when checkpoint not found after manual save")
    void debugCheckpoint_noCheckpoint_returnsNull() {
        when(checkpointRepository.findByProjectPath(anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(gitStatusService.getCurrentCommitHash(anyString())).thenReturn("test123");

        var result = service.debugCheckpoint(PROJECT_PATH);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("RefreshResult noop returns correct default values")
    void refreshResult_noop_returnsCorrectDefaults() {
        var noop = IncrementalRefreshServiceV2.RefreshResult.noop();

        assertThat(noop.projectPath()).isNull();
        assertThat(noop.lastCommit()).isNull();
        assertThat(noop.currentCommit()).isNull();
        assertThat(noop.changedFiles()).isEqualTo(0);
        assertThat(noop.deletedNodes()).isEqualTo(0);
        assertThat(noop.rebuiltNodes()).isEqualTo(0);
        assertThat(noop.rebuiltEdges()).isEqualTo(0);
        assertThat(noop.rebuiltEntryPoints()).isEqualTo(0);
        assertThat(noop.vectorsGenerated()).isEqualTo(0);
        assertThat(noop.success()).isTrue();
    }
}