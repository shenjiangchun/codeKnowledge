package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.link.CrossServiceLinker;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrossServiceBuildServiceTest {

    @Mock
    private Neo4jMethodNodeRepository methodRepo;

    @Mock
    private IncrementalRefreshService refreshService;

    @Mock
    private CrossServiceLinker linker;

    @InjectMocks
    private CrossServiceBuildService buildService;

    @Test
    @DisplayName("build throws when a project has no knowledge graph")
    void build_throwsWhenNoKg() {
        when(methodRepo.countByProjectPath("/project-a")).thenReturn(0L);

        assertThatThrownBy(() -> buildService.build(List.of("/project-a", "/project-b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/project-a");
    }

    @Test
    @DisplayName("build completes successfully with valid projects")
    void build_completesSuccessfully() throws Exception {
        List<String> paths = List.of("/project-a", "/project-b");
        when(methodRepo.countByProjectPath("/project-a")).thenReturn(10L);
        when(methodRepo.countByProjectPath("/project-b")).thenReturn(5L);
        when(methodRepo.deleteExternalCallsBetween(paths)).thenReturn(3L);

        buildService.build(paths);

        verify(refreshService).refresh("/project-a");
        verify(refreshService).refresh("/project-b");
        verify(methodRepo).deleteExternalCallsBetween(paths);
        verify(linker).link(paths);
    }

    @Test
    @DisplayName("build continues when refresh fails for one project")
    void build_continuesWhenRefreshFails() throws Exception {
        List<String> paths = List.of("/project-a", "/project-b");
        when(methodRepo.countByProjectPath("/project-a")).thenReturn(10L);
        when(methodRepo.countByProjectPath("/project-b")).thenReturn(5L);
        doThrow(new RuntimeException("refresh failed")).when(refreshService).refresh("/project-a");
        when(methodRepo.deleteExternalCallsBetween(paths)).thenReturn(0L);

        buildService.build(paths);

        verify(refreshService).refresh("/project-a");
        verify(refreshService).refresh("/project-b");
        verify(linker).link(paths);
    }
}
