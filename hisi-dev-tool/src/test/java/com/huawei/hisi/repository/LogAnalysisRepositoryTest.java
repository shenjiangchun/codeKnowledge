package com.huawei.hisi.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LogAnalysisRepository 单元测试
 * 测试日志分析报告数据访问层的 CRUD 操作
 */
@ExtendWith(MockitoExtension.class)
class LogAnalysisRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private LogAnalysisRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        repository = new LogAnalysisRepository(jdbcTemplate, objectMapper);
    }

    // ==================== findById Tests ====================

    @Test
    @DisplayName("根据 ID 查询 - 正常查询")
    void testFindById_Success() {
        // Given
        Long reportId = 1L;
        LogAnalysisRepository.LogAnalysisReportEntity entity = createSampleEntity();
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(reportId), eq(reportId)))
            .thenReturn(entity);

        // When
        LogAnalysisRepository.LogAnalysisReportEntity result = repository.findById(reportId);

        // Then
        assertNotNull(result);
        assertEquals(reportId, result.getReportId());
    }

    @Test
    @DisplayName("根据 ID 查询 - 报告不存在返回 null")
    void testFindById_NotFound() {
        // Given
        Long reportId = 999L;
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(reportId)))
            .thenThrow(new RuntimeException("Not found"));

        // When
        LogAnalysisRepository.LogAnalysisReportEntity result = repository.findById(reportId);

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("根据 ID 查询 - null ID 处理")
    void testFindById_NullId() {
        // Given
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), isNull()))
            .thenThrow(new RuntimeException("Invalid ID"));

        // When
        LogAnalysisRepository.LogAnalysisReportEntity result = repository.findById(null);

        // Then
        assertNull(result);
    }

    // ==================== save Tests ====================

    @Test
    @DisplayName("保存报告 - 正常保存")
    void testSave_Success() throws Exception {
        // Given
        LogAnalysisRepository.LogAnalysisReportEntity entity = createSampleEntity();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.save(entity));

        // Then
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("保存报告 - 包含 JSON 字段")
    void testSave_WithJsonFields() throws Exception {
        // Given
        LogAnalysisRepository.LogAnalysisReportEntity entity = createSampleEntity();
        Map<String, Object> errorSummary = new HashMap<>();
        errorSummary.put("errorType", "NullPointerException");
        entity.setErrorSummary(errorSummary);

        Map<String, Object> rootCause = new HashMap<>();
        rootCause.put("cause", "null reference");
        entity.setRootCause(rootCause);

        List<Map<String, Object>> suggestions = new ArrayList<>();
        suggestions.add(Map.of("suggestion", "Add null check"));
        entity.setFixSuggestions(suggestions);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.save(entity));

        // Then
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("保存报告 - 数据库异常")
    void testSave_DatabaseException() {
        // Given
        LogAnalysisRepository.LogAnalysisReportEntity entity = createSampleEntity();
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
            .thenThrow(new RuntimeException("Database error"));

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            repository.save(entity);
        });

        assertTrue(exception.getMessage().contains("保存报告失败"));
    }

    @Test
    @DisplayName("保存报告 - 空 JSON 字段")
    void testSave_EmptyJsonFields() throws Exception {
        // Given
        LogAnalysisRepository.LogAnalysisReportEntity entity = createSampleEntity();
        entity.setErrorSummary(null);
        entity.setRootCause(null);
        entity.setFixSuggestions(null);
        entity.setCodeSnippets(null);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.save(entity));

        // Then
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    // ==================== updateStatus Tests ====================

    @Test
    @DisplayName("更新状态 - 正常更新")
    void testUpdateStatus_Success() {
        // Given
        Long reportId = 1L;
        String status = "completed";
        when(jdbcTemplate.update(anyString(), eq(status), eq(reportId)))
            .thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.updateStatus(reportId, status));

        // Then
        verify(jdbcTemplate, times(1)).update(anyString(), eq(status), eq(reportId));
    }

    @Test
    @DisplayName("更新状态 - 处理中状态")
    void testUpdateStatus_Processing() {
        // Given
        Long reportId = 1L;
        String status = "processing";
        when(jdbcTemplate.update(anyString(), eq(status), eq(reportId)))
            .thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.updateStatus(reportId, status));

        // Then
        verify(jdbcTemplate).update(anyString(), eq(status), eq(reportId));
    }

    @Test
    @DisplayName("更新状态 - 失败状态")
    void testUpdateStatus_Failed() {
        // Given
        Long reportId = 1L;
        String status = "failed";
        when(jdbcTemplate.update(anyString(), eq(status), eq(reportId)))
            .thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.updateStatus(reportId, status));

        // Then
        verify(jdbcTemplate).update(anyString(), eq(status), eq(reportId));
    }

    @Test
    @DisplayName("更新状态 - 数据库异常")
    void testUpdateStatus_DatabaseException() {
        // Given
        Long reportId = 1L;
        String status = "completed";
        when(jdbcTemplate.update(anyString(), eq(status), eq(reportId)))
            .thenThrow(new RuntimeException("Database error"));

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            repository.updateStatus(reportId, status);
        });

        assertTrue(exception.getMessage().contains("更新状态失败"));
    }

    // ==================== updateAnalysisResult Tests ====================

    @Test
    @DisplayName("更新分析结果 - 正常更新")
    void testUpdateAnalysisResult_Success() throws Exception {
        // Given
        Long reportId = 1L;
        Map<String, Object> errorSummary = Map.of("type", "NullPointerException");
        Map<String, Object> rootCause = Map.of("cause", "null reference");
        List<Map<String, Object>> suggestions = List.of(Map.of("suggestion", "Add null check"));
        List<Map<String, Object>> codeSnippets = List.of(Map.of("code", "if (obj != null)"));

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.updateAnalysisResult(reportId, errorSummary, rootCause, suggestions, codeSnippets));

        // Then
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("更新分析结果 - 空建议列表")
    void testUpdateAnalysisResult_EmptySuggestions() throws Exception {
        // Given
        Long reportId = 1L;
        Map<String, Object> errorSummary = Map.of("type", "Error");
        Map<String, Object> rootCause = Map.of("cause", "unknown");
        List<Map<String, Object>> suggestions = Collections.emptyList();
        List<Map<String, Object>> codeSnippets = Collections.emptyList();

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.updateAnalysisResult(reportId, errorSummary, rootCause, suggestions, codeSnippets));

        // Then
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("更新分析结果 - 数据库异常")
    void testUpdateAnalysisResult_DatabaseException() {
        // Given
        Long reportId = 1L;
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
            .thenThrow(new RuntimeException("Database error"));

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            repository.updateAnalysisResult(reportId, Map.of(), Map.of(), List.of(), List.of());
        });

        assertTrue(exception.getMessage().contains("更新分析结果失败"));
    }

    // ==================== updateError Tests ====================

    @Test
    @DisplayName("更新错误信息 - 正常更新")
    void testUpdateError_Success() {
        // Given
        Long reportId = 1L;
        String errorMessage = "Analysis failed due to timeout";

        // When
        assertDoesNotThrow(() -> repository.updateError(reportId, errorMessage));

        // Then
        verify(jdbcTemplate).update(anyString(), eq(errorMessage), eq(reportId));
    }

    @Test
    @DisplayName("更新错误信息 - 空错误消息")
    void testUpdateError_EmptyMessage() {
        // Given
        Long reportId = 1L;
        String errorMessage = "";

        // When
        assertDoesNotThrow(() -> repository.updateError(reportId, errorMessage));

        // Then
        verify(jdbcTemplate).update(anyString(), eq(errorMessage), eq(reportId));
    }

    @Test
    @DisplayName("更新错误信息 - null 错误消息")
    void testUpdateError_NullMessage() {
        // Given
        Long reportId = 1L;

        // When
        assertDoesNotThrow(() -> repository.updateError(reportId, null));

        // Then
        verify(jdbcTemplate).update(anyString(), isNull(), eq(reportId));
    }

    @Test
    @DisplayName("更新错误信息 - 数据库异常不抛出")
    void testUpdateError_DatabaseExceptionNoThrow() {
        // Given
        Long reportId = 1L;
        String errorMessage = "Error";
        doThrow(new RuntimeException("Database error"))
            .when(jdbcTemplate).update(anyString(), any(), anyLong());

        // When & Then - 不应该抛出异常
        assertDoesNotThrow(() -> repository.updateError(reportId, errorMessage));
    }

    // ==================== findByUserIdAndStatus Tests ====================

    @Test
    @DisplayName("根据用户和状态查询 - 正常查询")
    void testFindByUserIdAndStatus_Success() {
        // Given
        String userId = "user1";
        String status = "completed";
        List<LogAnalysisRepository.LogAnalysisReportEntity> mockList = List.of(createSampleEntity());

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId), eq(status)))
            .thenReturn(mockList);

        // When
        List<LogAnalysisRepository.LogAnalysisReportEntity> result = repository.findByUserIdAndStatus(userId, status);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("根据用户和状态查询 - 无结果返回空列表")
    void testFindByUserIdAndStatus_EmptyResult() {
        // Given
        String userId = "nonexistent";
        String status = "completed";
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId), eq(status)))
            .thenReturn(Collections.emptyList());

        // When
        List<LogAnalysisRepository.LogAnalysisReportEntity> result = repository.findByUserIdAndStatus(userId, status);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("根据用户和状态查询 - 数据库异常返回空列表")
    void testFindByUserIdAndStatus_DatabaseException() {
        // Given
        String userId = "user1";
        String status = "completed";
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId), eq(status)))
            .thenThrow(new RuntimeException("Database error"));

        // When
        List<LogAnalysisRepository.LogAnalysisReportEntity> result = repository.findByUserIdAndStatus(userId, status);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== findByUserIdPagination Tests ====================

    @Test
    @DisplayName("分页查询 - 正常查询第一页")
    void testFindByUserIdPagination_FirstPage() {
        // Given
        String userId = "user1";
        int page = 1;
        int pageSize = 10;
        List<LogAnalysisRepository.LogAnalysisReportEntity> mockList = List.of(createSampleEntity());

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(userId)))
            .thenReturn(15);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId), eq(pageSize), eq(0)))
            .thenReturn(mockList);

        // When
        LogAnalysisRepository.PaginatedReports result = repository.findByUserIdPagination(userId, page, pageSize);

        // Then
        assertNotNull(result);
        assertEquals(15, result.getTotal());
        assertEquals(1, result.getList().size());
    }

    @Test
    @DisplayName("分页查询 - 查询第二页")
    void testFindByUserIdPagination_SecondPage() {
        // Given
        String userId = "user1";
        int page = 2;
        int pageSize = 10;
        List<LogAnalysisRepository.LogAnalysisReportEntity> mockList = List.of(createSampleEntity());

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(userId)))
            .thenReturn(15);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId), eq(pageSize), eq(10)))
            .thenReturn(mockList);

        // When
        LogAnalysisRepository.PaginatedReports result = repository.findByUserIdPagination(userId, page, pageSize);

        // Then
        assertNotNull(result);
        assertEquals(15, result.getTotal());
        assertEquals(1, result.getList().size());
    }

    @Test
    @DisplayName("分页查询 - 空结果")
    void testFindByUserIdPagination_EmptyResult() {
        // Given
        String userId = "nonexistent";
        int page = 1;
        int pageSize = 10;

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(userId)))
            .thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId), eq(pageSize), eq(0)))
            .thenReturn(Collections.emptyList());

        // When
        LogAnalysisRepository.PaginatedReports result = repository.findByUserIdPagination(userId, page, pageSize);

        // Then
        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    @DisplayName("分页查询 - 数据库异常返回空结果")
    void testFindByUserIdPagination_DatabaseException() {
        // Given
        String userId = "user1";
        int page = 1;
        int pageSize = 10;

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(userId)))
            .thenThrow(new RuntimeException("Database error"));

        // When
        LogAnalysisRepository.PaginatedReports result = repository.findByUserIdPagination(userId, page, pageSize);

        // Then
        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    @DisplayName("分页查询 - null 总数处理")
    void testFindByUserIdPagination_NullTotal() {
        // Given
        String userId = "user1";
        int page = 1;
        int pageSize = 10;

        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(userId)))
            .thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId), eq(pageSize), eq(0)))
            .thenReturn(Collections.emptyList());

        // When
        LogAnalysisRepository.PaginatedReports result = repository.findByUserIdPagination(userId, page, pageSize);

        // Then
        assertNotNull(result);
        assertEquals(0, result.getTotal());
    }

    // ==================== Entity Tests ====================

    @Test
    @DisplayName("实体类 - Getter 和 Setter 测试")
    void testEntity_GettersAndSetters() {
        // Given
        LogAnalysisRepository.LogAnalysisReportEntity entity = new LogAnalysisRepository.LogAnalysisReportEntity();

        // When
        entity.setReportId(1L);
        entity.setUserId("user1");
        entity.setStatus("completed");
        entity.setLogMessage("Test message");
        entity.setLogStackTrace("Test stack trace");
        entity.setFilteredStackTrace("Filtered trace");
        entity.setErrorType("NullPointerException");
        entity.setTraceId("trace-123");
        entity.setServiceName("test-service");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        // Then
        assertEquals(1L, entity.getReportId());
        assertEquals("user1", entity.getUserId());
        assertEquals("completed", entity.getStatus());
        assertEquals("Test message", entity.getLogMessage());
        assertEquals("Test stack trace", entity.getLogStackTrace());
        assertEquals("Filtered trace", entity.getFilteredStackTrace());
        assertEquals("NullPointerException", entity.getErrorType());
        assertEquals("trace-123", entity.getTraceId());
        assertEquals("test-service", entity.getServiceName());
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
    }

    @Test
    @DisplayName("实体类 - Map 类型字段设置")
    void testEntity_MapFields() {
        // Given
        LogAnalysisRepository.LogAnalysisReportEntity entity = new LogAnalysisRepository.LogAnalysisReportEntity();
        Map<String, Object> errorSummary = new HashMap<>();
        errorSummary.put("type", "NullPointerException");
        errorSummary.put("message", "Null reference");

        Map<String, Object> rootCause = new HashMap<>();
        rootCause.put("cause", "Variable was null");

        List<Map<String, Object>> suggestions = new ArrayList<>();
        suggestions.add(Map.of("suggestion", "Add null check"));

        List<Map<String, Object>> codeSnippets = new ArrayList<>();
        codeSnippets.add(Map.of("file", "Test.java", "line", 10));

        // When
        entity.setErrorSummary(errorSummary);
        entity.setRootCause(rootCause);
        entity.setFixSuggestions(suggestions);
        entity.setCodeSnippets(codeSnippets);

        // Then
        assertEquals(errorSummary, entity.getErrorSummary());
        assertEquals(rootCause, entity.getRootCause());
        assertEquals(suggestions, entity.getFixSuggestions());
        assertEquals(codeSnippets, entity.getCodeSnippets());
    }

    @Test
    @DisplayName("分页结果类 - Getter 测试")
    void testPaginatedReports_Getters() {
        // Given
        List<LogAnalysisRepository.LogAnalysisReportEntity> list = List.of(createSampleEntity());
        LogAnalysisRepository.PaginatedReports paginated = new LogAnalysisRepository.PaginatedReports(10, list);

        // When & Then
        assertEquals(10, paginated.getTotal());
        assertEquals(1, paginated.getList().size());
    }

    @Test
    @DisplayName("分页结果类 - 空列表")
    void testPaginatedReports_EmptyList() {
        // Given
        LogAnalysisRepository.PaginatedReports paginated = new LogAnalysisRepository.PaginatedReports(0, Collections.emptyList());

        // When & Then
        assertEquals(0, paginated.getTotal());
        assertTrue(paginated.getList().isEmpty());
    }

    // ==================== Helper Methods ====================

    private LogAnalysisRepository.LogAnalysisReportEntity createSampleEntity() {
        LogAnalysisRepository.LogAnalysisReportEntity entity = new LogAnalysisRepository.LogAnalysisReportEntity();
        entity.setReportId(1L);
        entity.setUserId("user1");
        entity.setStatus("completed");
        entity.setLogMessage("Test error message");
        entity.setLogStackTrace("at com.example.Test.method(Test.java:10)");
        entity.setErrorType("NullPointerException");
        entity.setTraceId("trace-123");
        entity.setServiceName("test-service");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}