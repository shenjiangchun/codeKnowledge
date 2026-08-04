package com.huawei.hisi.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 错误日志与向量嵌入映射关系数据访问层 (SQLite)
 * 用于追踪相似日志的向量检索结果
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ErrorEmbeddingMapRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 保存映射记录
     */
    public void save(ErrorEmbeddingMapEntity entity) {
        String sql = """
            INSERT INTO log_error_embedding_map (report_id, embedding_id, similarity_score, matched_report_id)
            VALUES (?, ?, ?, ?)
            """;
        try {
            jdbcTemplate.update(sql,
                entity.getReportId(),
                entity.getEmbeddingId(),
                entity.getSimilarityScore(),
                entity.getMatchedReportId()
            );
            log.debug("映射记录保存成功 (reportId={}, embeddingId={})", entity.getReportId(), entity.getEmbeddingId());
        } catch (Exception e) {
            log.error("映射记录保存失败: {}", e.getMessage());
            throw new RuntimeException("保存映射记录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据报告ID查询所有映射记录
     */
    public List<ErrorEmbeddingMapEntity> findByReportId(Long reportId) {
        String sql = "SELECT * FROM log_error_embedding_map WHERE report_id = ? ORDER BY created_at DESC";
        try {
            return jdbcTemplate.query(sql, new ErrorEmbeddingMapRowMapper(), reportId);
        } catch (Exception e) {
            log.error("查询映射记录失败 (reportId={}): {}", reportId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 映射实体类
     */
    @SuppressWarnings("unused")
    public static class ErrorEmbeddingMapEntity {
        private Long id;
        private Long reportId;
        private String embeddingId;
        private Double similarityScore;
        private Long matchedReportId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getReportId() { return reportId; }
        public void setReportId(Long reportId) { this.reportId = reportId; }
        public String getEmbeddingId() { return embeddingId; }
        public void setEmbeddingId(String embeddingId) { this.embeddingId = embeddingId; }
        public Double getSimilarityScore() { return similarityScore; }
        public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }
        public Long getMatchedReportId() { return matchedReportId; }
        public void setMatchedReportId(Long matchedReportId) { this.matchedReportId = matchedReportId; }
    }

    /**
     * 行映射器
     */
    private static class ErrorEmbeddingMapRowMapper implements RowMapper<ErrorEmbeddingMapEntity> {
        @Override
        public ErrorEmbeddingMapEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            ErrorEmbeddingMapEntity entity = new ErrorEmbeddingMapEntity();
            entity.setId(rs.getLong("id"));
            entity.setReportId(rs.getLong("report_id"));
            entity.setEmbeddingId(rs.getString("embedding_id"));
            entity.setSimilarityScore(rs.getDouble("similarity_score"));
            entity.setMatchedReportId(rs.getObject("matched_report_id") != null ? rs.getLong("matched_report_id") : null);
            return entity;
        }
    }
}