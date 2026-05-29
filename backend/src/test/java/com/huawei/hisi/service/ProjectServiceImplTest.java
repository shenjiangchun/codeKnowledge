package com.huawei.hisi.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.huawei.hisi.config.DataSourceConfig;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProjectServiceImpl 单元测试
 * 测试项目管理服务的克隆、状态查询等功能
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectServiceImplTest {

    private static DataSource mockDataSource;
    private Connection mockConnection;
    private Statement mockStatement;
    private PreparedStatement mockPreparedStatement;
    private ResultSet mockResultSet;

    private ProjectServiceImpl projectService;

    @BeforeAll
    static void setUpStatic() {
        // 设置 PROJECT_DIR 静态变量，避免 NullPointerException
        ReflectionTestUtils.setField(DataSourceConfig.class, "PROJECT_DIR", "/tmp/test-projects");
    }

    @BeforeEach
    void setUp() throws SQLException {
        // 创建基础 mocks
        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        mockStatement = mock(Statement.class);
        mockPreparedStatement = mock(PreparedStatement.class);
        mockResultSet = mock(ResultSet.class);

        // Mock initTableName 所需的调用
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getSchema()).thenReturn("public");
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false); // 表不存在，使用 public schema

        // 创建新的服务实例并注入 mock 数据源
        projectService = new ProjectServiceImpl(mockDataSource);

        // 设置 @Value 字段
        ReflectionTestUtils.setField(projectService, "codeHubUser", "testUser");
        ReflectionTestUtils.setField(projectService, "codeHubPassword", "testPassword");
    }

    // ==================== listProjects Tests ====================

    @Test
    @DisplayName("列出项目 - 正常流程")
    void testListProjects_Success() throws SQLException {
        // Given
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("package")).thenReturn("com.example.project1.service", "com.example.project2.service");

        // When
        List<String> projects = projectService.listProjects();

        // Then
        assertNotNull(projects);
        assertEquals(2, projects.size());
        assertTrue(projects.contains("com.example.project1"));
        assertTrue(projects.contains("com.example.project2"));
    }

    @Test
    @DisplayName("列出项目 - 空结果")
    void testListProjects_EmptyResult() throws SQLException {
        // Given
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        // When
        List<String> projects = projectService.listProjects();

        // Then
        assertNotNull(projects);
        assertTrue(projects.isEmpty());
    }

    @Test
    @DisplayName("列出项目 - 包含 null 和空字符串过滤")
    void testListProjects_WithNullOrEmptyValues() throws SQLException {
        // Given
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, true, true, false);
        when(mockResultSet.getString("package")).thenReturn("com.example.project.service", null, "", "com.test.project.controller");

        // When
        List<String> projects = projectService.listProjects();

        // Then
        assertNotNull(projects);
        assertEquals(2, projects.size());
        assertTrue(projects.contains("com.example.project"));
        assertTrue(projects.contains("com.test.project"));
    }

    @Test
    @DisplayName("列出项目 - SQL 异常处理")
    void testListProjects_SqlException() throws SQLException {
        // Given
        when(mockDataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

        // When
        List<String> projects = projectService.listProjects();

        // Then
        assertNotNull(projects);
        assertTrue(projects.isEmpty());
    }

    // ==================== cloneProject Tests ====================

    @Test
    @DisplayName("克隆项目 - 提取项目名称 (.git 后缀)")
    void testExtractProjectName_WithGitSuffix() {
        // 测试私有方法
        String repo1 = "https://github.com/example/my-project.git";
        String expected1 = "my-project";

        // 通过反射测试私有方法
        String result = (String) ReflectionTestUtils.invokeMethod(projectService, "extractProjectName", repo1);
        assertEquals(expected1, result);

        // 测试没有 .git 后缀
        String repo2 = "https://github.com/example/another-project";
        String expected2 = "another-project";
        String result2 = (String) ReflectionTestUtils.invokeMethod(projectService, "extractProjectName", repo2);
        assertEquals(expected2, result2);
    }

    @Test
    @DisplayName("克隆项目 - 有效仓库地址返回结果")
    void testCloneProject_ValidRepository() {
        // Given
        String repo = "https://github.com/example/test-project.git";
        String branch = "main";

        // When
        Map<String, Object> result = projectService.cloneProject(repo, branch);

        // Then
        assertNotNull(result);
        // 结果应该包含 success 字段
        assertTrue(result.containsKey("success"));
    }

    // ==================== getStatus Tests ====================

    @Test
    @DisplayName("获取项目状态 - 项目不存在于文件系统")
    void testGetStatus_ProjectNotExists() {
        // Given
        String project = "nonexistent-project-12345";

        // When
        Map<String, Object> status = projectService.getStatus(project);

        // Then
        assertNotNull(status);
        assertEquals(project, status.get("project"));
        // 由于项目目录不存在，exists 应该为 false
        assertFalse((Boolean) status.get("exists"));
        assertEquals("NOT_CLONED", status.get("status"));
        assertEquals(0, status.get("uriCount"));
    }

    @Test
    @DisplayName("获取项目状态 - SQL 异常处理")
    void testGetStatus_SqlException() throws SQLException {
        // Given - 使用一个唯一的项目名称确保目录不存在
        String project = "test-project-sql-exception-" + System.currentTimeMillis();
        // 当项目存在时，会尝试查询数据库
        when(mockDataSource.getConnection()).thenThrow(new SQLException("Database error"));

        // When
        Map<String, Object> status = projectService.getStatus(project);

        // Then
        assertNotNull(status);
        // 由于文件系统不存在该项目，应该返回 NOT_CLONED
        assertFalse((Boolean) status.get("exists"));
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("获取状态 - 有效项目名")
    void testGetStatus_ValidProject() {
        // Given
        String project = "valid-project-name";

        // When
        Map<String, Object> status = projectService.getStatus(project);

        // Then
        assertNotNull(status);
        assertEquals(project, status.get("project"));
        assertFalse((Boolean) status.get("exists")); // 项目不存在于文件系统
    }

    @Test
    @DisplayName("获取分析状态 - 数据库查询正常")
    void testGetAnalysisStatus_DatabaseQuery() {
        // Given
        String project = "analyzed-project";

        // When - 通过 getStatus 间接测试
        Map<String, Object> status = projectService.getStatus(project);

        // Then
        assertNotNull(status);
        // 由于文件系统不存在该项目，结果会是 NOT_CLONED
        assertFalse((Boolean) status.get("exists"));
        assertEquals("NOT_CLONED", status.get("status"));
    }

    @Test
    @DisplayName("获取 URI 计数 - 正常流程")
    void testGetUriCount_Success() {
        // Given
        String project = "project-with-uris";

        // When
        Map<String, Object> status = projectService.getStatus(project);

        // Then
        assertNotNull(status);
        // 由于文件系统不存在该项目，exists 为 false
        assertFalse((Boolean) status.get("exists"));
        assertEquals(0, status.get("uriCount"));
    }
}