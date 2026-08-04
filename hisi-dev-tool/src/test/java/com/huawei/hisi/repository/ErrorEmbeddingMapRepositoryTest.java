package com.huawei.hisi.repository;

import com.huawei.hisi.repository.ErrorEmbeddingMapRepository.ErrorEmbeddingMapEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ErrorEmbeddingMapRepository 单元测试
 * 测试错误日志与向量嵌入映射关系的数据访问层
 */
@ExtendWith(MockitoExtension.class)
class ErrorEmbeddingMapRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ErrorEmbeddingMapRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ErrorEmbeddingMapRepository(jdbcTemplate);
    }

    // ==================== save Tests ====================

    @Test
    @DisplayName("保存映射 - 正常保存")
    void testSaveEmbeddingMap_Success() {
        // Given
        ErrorEmbeddingMapEntity entity = createSampleEntity();

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.save(entity));

        // Then
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("保存映射 - 包含匹配报告ID")
    void testSaveEmbeddingMap_WithMatchedReportId() {
        // Given
        ErrorEmbeddingMapEntity entity = createSampleEntity();
        entity.setMatchedReportId(67890L);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> repository.save(entity));

        // Then
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    // ==================== findByReportId Tests ====================

    @Test
    @DisplayName("根据报告ID查询 - 正常查询")
    void testFindByReportId_Success() {
        // Given
        Long reportId = 12345L;
        ErrorEmbeddingMapEntity entity = createSampleEntity();
        List<ErrorEmbeddingMapEntity> mockList = List.of(entity);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(reportId)))
            .thenReturn(mockList);

        // When
        List<ErrorEmbeddingMapEntity> result = repository.findByReportId(reportId);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(12345L, result.get(0).getReportId());
    }

    @Test
    @DisplayName("根据报告ID查询 - 无结果返回空列表")
    void testFindByReportId_EmptyResult() {
        // Given
        Long reportId = 999L;
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(reportId)))
            .thenReturn(List.of());

        // When
        List<ErrorEmbeddingMapEntity> result = repository.findByReportId(reportId);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== Entity Tests ====================

    @Test
    @DisplayName("实体类 - Getter 和 Setter 测试")
    void testEntity_GettersAndSetters() {
        // Given
        ErrorEmbeddingMapEntity entity = new ErrorEmbeddingMapEntity();

        // When
        entity.setId(1L);
        entity.setReportId(12345L);
        entity.setEmbeddingId("neo4j-node-abc");
        entity.setSimilarityScore(0.92);
        entity.setMatchedReportId(67890L);

        // Then
        assertEquals(1L, entity.getId());
        assertEquals(12345L, entity.getReportId());
        assertEquals("neo4j-node-abc", entity.getEmbeddingId());
        assertEquals(0.92, entity.getSimilarityScore());
        assertEquals(67890L, entity.getMatchedReportId());
    }

    // ==================== Helper Methods ====================

    private ErrorEmbeddingMapEntity createSampleEntity() {
        ErrorEmbeddingMapEntity entity = new ErrorEmbeddingMapEntity();
        entity.setReportId(12345L);
        entity.setEmbeddingId("neo4j-node-abc");
        entity.setSimilarityScore(0.92);
        return entity;
    }
}