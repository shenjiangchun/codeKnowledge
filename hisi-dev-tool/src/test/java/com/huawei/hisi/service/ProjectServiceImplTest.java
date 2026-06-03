package com.huawei.hisi.service;

import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    private Neo4jMethodNodeRepository mockRepo;
    private ProjectServiceImpl projectService;

    @BeforeEach
    void setUp() {
        mockRepo = mock(Neo4jMethodNodeRepository.class);
        projectService = new ProjectServiceImpl();
        ReflectionTestUtils.setField(projectService, "neo4jMethodNodeRepository", mockRepo);
        ReflectionTestUtils.setField(projectService, "appConfigService", mock(AppConfigService.class));
        ReflectionTestUtils.setField(projectService, "codeHubUser", "testUser");
        ReflectionTestUtils.setField(projectService, "codeHubPassword", "testPassword");
    }

    @Test
    @DisplayName("listProjects - returns projects from Neo4j")
    void testListProjects_success() {
        when(mockRepo.findDistinctProjectPaths()).thenReturn(List.of("project-a", "project-b"));
        List<String> result = projectService.listProjects();
        assertEquals(2, result.size());
        assertTrue(result.contains("project-a"));
    }

    @Test
    @DisplayName("listProjects - returns empty list on Neo4j error")
    void testListProjects_error() {
        when(mockRepo.findDistinctProjectPaths()).thenThrow(new RuntimeException("connection failed"));
        List<String> result = projectService.listProjects();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getStatus - COMPLETED when Neo4j has nodes")
    void testGetStatus_completed() {
        when(mockRepo.countByProjectPath("my-project")).thenReturn(42L);
        var status = projectService.getStatus("my-project");
        assertEquals("COMPLETED", status.get("status"));
    }

    @Test
    @DisplayName("getStatus - UNKNOWN when Neo4j has no nodes")
    void testGetStatus_unknown() {
        when(mockRepo.countByProjectPath("my-project")).thenReturn(0L);
        var status = projectService.getStatus("my-project");
        assertEquals("UNKNOWN", status.get("status"));
    }
}
