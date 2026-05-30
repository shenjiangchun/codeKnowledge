package com.huawei.hisi.service.impl;

import com.huawei.hisi.model.DiagnosisCase;
import com.huawei.hisi.service.CaseMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 案例匹配服务实现
 * 提供历史诊断案例的存储和相似度匹配功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseMatchingServiceImpl implements CaseMatchingService {

    private final JdbcTemplate jdbcTemplate;

    private static final String TABLE_NAME = "diagnosis_cases";

    @PostConstruct
    public void init() {
        ensureTableExists();
    }

    private void ensureTableExists() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS diagnosis_cases (
                    id TEXT PRIMARY KEY,
                    project_path TEXT,
                    error_type TEXT,
                    error_message TEXT,
                    stack_trace_summary TEXT,
                    root_cause_analysis TEXT,
                    solution_description TEXT,
                    verification_status TEXT,
                    usage_count INTEGER DEFAULT 0,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        } catch (Exception e) {
            log.warn("Failed to create diagnosis_cases table: {}", e.getMessage());
        }
    }

    private final RowMapper<DiagnosisCase> caseRowMapper = (rs, rowNum) -> DiagnosisCase.builder()
            .id(rs.getString("id"))
            .projectPath(rs.getString("project_path"))
            .errorType(rs.getString("error_type"))
            .errorMessage(rs.getString("error_message"))
            .stackTraceSummary(rs.getString("stack_trace_summary"))
            .rootCauseAnalysis(rs.getString("root_cause_analysis"))
            .solutionDescription(rs.getString("solution_description"))
            .verificationStatus(DiagnosisCase.VerificationStatus.valueOf(rs.getString("verification_status")))
            .usageCount(rs.getInt("usage_count"))
            .createdAt(rs.getTimestamp("created_at") != null ?
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .updatedAt(rs.getTimestamp("updated_at") != null ?
                    rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .build();

    @Override
    public DiagnosisCase saveCase(DiagnosisCase diagnosisCase) {
        if (diagnosisCase.getId() == null || diagnosisCase.getId().isEmpty()) {
            diagnosisCase.setId(UUID.randomUUID().toString());
        }
        diagnosisCase.setCreatedAt(LocalDateTime.now());
        diagnosisCase.setUpdatedAt(LocalDateTime.now());
        diagnosisCase.setVerificationStatus(DiagnosisCase.VerificationStatus.PENDING);
        diagnosisCase.setUsageCount(0);

        String sql = """
            INSERT INTO {} (id, project_path, error_type, error_message, stack_trace_summary,
                root_cause_analysis, solution_description, verification_status, usage_count,
                created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                project_path = EXCLUDED.project_path,
                error_type = EXCLUDED.error_type,
                error_message = EXCLUDED.error_message,
                stack_trace_summary = EXCLUDED.stack_trace_summary,
                root_cause_analysis = EXCLUDED.root_cause_analysis,
                solution_description = EXCLUDED.solution_description,
                updated_at = EXCLUDED.updated_at
            """.replace("{}", TABLE_NAME);

        jdbcTemplate.update(sql,
                diagnosisCase.getId(),
                diagnosisCase.getProjectPath(),
                diagnosisCase.getErrorType(),
                diagnosisCase.getErrorMessage(),
                diagnosisCase.getStackTraceSummary(),
                diagnosisCase.getRootCauseAnalysis(),
                diagnosisCase.getSolutionDescription(),
                diagnosisCase.getVerificationStatus().name(),
                diagnosisCase.getUsageCount(),
                diagnosisCase.getCreatedAt(),
                diagnosisCase.getUpdatedAt()
        );

        log.info("Saved diagnosis case: id={}, errorType={}", diagnosisCase.getId(), diagnosisCase.getErrorType());
        return diagnosisCase;
    }

    @Override
    public List<DiagnosisCase> matchSimilarCases(DiagnosisContext context, int limit) {
        List<DiagnosisCase> results = new ArrayList<>();

        // 策略1: 精确匹配错误类型
        if (context.getErrorType() != null && !context.getErrorType().isEmpty()) {
            String sql = """
                SELECT * FROM {} WHERE error_type = ? ORDER BY usage_count DESC LIMIT ?
                """.replace("{}", TABLE_NAME);
            List<DiagnosisCase> exactMatches = jdbcTemplate.query(sql, caseRowMapper,
                    context.getErrorType(), limit);
            results.addAll(exactMatches);
        }

        // 策略2: 关键词匹配错误消息
        if (context.getErrorMessage() != null && !context.getErrorMessage().isEmpty()) {
            String[] keywords = extractKeywords(context.getErrorMessage());
            for (String keyword : keywords) {
                if (results.size() >= limit) break;
                String sql = """
                    SELECT * FROM {} WHERE error_message LIKE ? ORDER BY usage_count DESC LIMIT ?
                    """.replace("{}", TABLE_NAME);
                List<DiagnosisCase> keywordMatches = jdbcTemplate.query(sql, caseRowMapper,
                        "%" + keyword + "%", limit - results.size());
                for (DiagnosisCase c : keywordMatches) {
                    if (results.stream().noneMatch(r -> r.getId().equals(c.getId()))) {
                        results.add(c);
                    }
                }
            }
        }

        // 按使用次数排序
        results.sort((a, b) -> Integer.compare(b.getUsageCount(), a.getUsageCount()));

        log.info("Matched {} similar cases for errorType={}", results.size(), context.getErrorType());
        return results.stream().limit(limit).collect(Collectors.toList());
    }

    @Override
    public Optional<DiagnosisCase> findById(String id) {
        String sql = "SELECT * FROM {} WHERE id = ?".replace("{}", TABLE_NAME);
        List<DiagnosisCase> results = jdbcTemplate.query(sql, caseRowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<DiagnosisCase> findByErrorType(String errorType) {
        String sql = "SELECT * FROM {} WHERE error_type = ? ORDER BY usage_count DESC".replace("{}", TABLE_NAME);
        return jdbcTemplate.query(sql, caseRowMapper, errorType);
    }

    @Override
    public void verifyCase(String id, String feedback) {
        DiagnosisCase.VerificationStatus status = "valid".equalsIgnoreCase(feedback) ?
                DiagnosisCase.VerificationStatus.VERIFIED :
                DiagnosisCase.VerificationStatus.INVALIDATED;

        String sql = "UPDATE {} SET verification_status = ?, updated_at = ? WHERE id = ?".replace("{}", TABLE_NAME);
        jdbcTemplate.update(sql, status.name(), LocalDateTime.now(), id);
        log.info("Verified case {} as {}", id, status);
    }

    @Override
    public List<DiagnosisCase> getHotCases(String projectPath, int limit) {
        String sql;
        if (projectPath != null && !projectPath.isEmpty()) {
            sql = "SELECT * FROM {} WHERE project_path = ? ORDER BY usage_count DESC LIMIT ?".replace("{}", TABLE_NAME);
            return jdbcTemplate.query(sql, caseRowMapper, projectPath, limit);
        } else {
            sql = "SELECT * FROM {} ORDER BY usage_count DESC LIMIT ?".replace("{}", TABLE_NAME);
            return jdbcTemplate.query(sql, caseRowMapper, limit);
        }
    }

    @Override
    public void incrementUsageCount(String id) {
        String sql = "UPDATE {} SET usage_count = usage_count + 1, updated_at = ? WHERE id = ?".replace("{}", TABLE_NAME);
        jdbcTemplate.update(sql, LocalDateTime.now(), id);
    }

    @Override
    public void deleteCase(String id) {
        String sql = "DELETE FROM {} WHERE id = ?".replace("{}", TABLE_NAME);
        jdbcTemplate.update(sql, id);
        log.info("Deleted case {}", id);
    }

    @Override
    public long getCaseCount() {
        String sql = "SELECT COUNT(*) FROM {}".replace("{}", TABLE_NAME);
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 从错误消息中提取关键词
     */
    private String[] extractKeywords(String errorMessage) {
        // 移除常见的无意义词
        Set<String> stopWords = Set.of("the", "a", "an", "is", "are", "was", "were", "be", "been",
                "being", "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "must", "shall", "can", "need", "dare", "ought",
                "used", "to", "of", "in", "for", "on", "with", "at", "by", "from", "as",
                "into", "through", "during", "before", "after", "above", "below", "between",
                "under", "again", "further", "then", "once", "here", "there", "when", "where",
                "why", "how", "all", "each", "few", "more", "most", "other", "some", "such",
                "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very", "just");

        return Arrays.stream(errorMessage.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(word -> word.length() > 2)
                .filter(word -> !stopWords.contains(word))
                .distinct()
                .limit(5)
                .toArray(String[]::new);
    }
}