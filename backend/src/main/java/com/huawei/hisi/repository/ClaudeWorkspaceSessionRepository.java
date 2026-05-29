package com.huawei.hisi.repository;

import com.huawei.hisi.model.ClaudeWorkspaceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Claude 工作空间会话数据访问层 (SQLite)
 * Table created by SQLiteSchemaInitializer as "workspace_session".
 */
@Repository
public class ClaudeWorkspaceSessionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeWorkspaceSessionRepository.class);
    private static final String TABLE = "workspace_session";

    private final JdbcTemplate jdbcTemplate;

    public ClaudeWorkspaceSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<ClaudeWorkspaceSession> rowMapper = (rs, rowNum) -> {
        ClaudeWorkspaceSession session = new ClaudeWorkspaceSession();
        session.setId(rs.getString("id"));
        session.setStatus(rs.getString("status"));
        long createdEpoch = rs.getLong("created_at");
        session.setCreatedAt(createdEpoch > 0 ?
            LocalDateTime.ofInstant(Instant.ofEpochSecond(createdEpoch), ZoneId.systemDefault()) : null);
        // workspace_session schema has: id, project_path, initial_prompt, status, created_at
        try { session.setWorkingDirectory(rs.getString("project_path")); } catch (Exception ignored) {}
        try { session.setInitialPrompt(rs.getString("initial_prompt")); } catch (Exception ignored) {}
        return session;
    };

    /**
     * 保存会话 (upsert)
     */
    public ClaudeWorkspaceSession save(ClaudeWorkspaceSession session) {
        String sql = """
            INSERT INTO workspace_session (id, project_path, initial_prompt, status, created_at)
            VALUES (?, ?, ?, ?, strftime('%s','now'))
            ON CONFLICT(id) DO UPDATE SET
                project_path = excluded.project_path,
                initial_prompt = excluded.initial_prompt,
                status = excluded.status
            """;
        jdbcTemplate.update(sql,
            session.getId(),
            session.getWorkingDirectory(),
            session.getInitialPrompt(),
            session.getStatus() != null ? session.getStatus() : "active"
        );
        return session;
    }

    /**
     * 根据ID查询会话
     */
    public Optional<ClaudeWorkspaceSession> findById(String id) {
        String sql = "SELECT * FROM " + TABLE + " WHERE id = ?";
        try {
            ClaudeWorkspaceSession session = jdbcTemplate.queryForObject(sql, rowMapper, id);
            return Optional.ofNullable(session);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 查询所有会话
     */
    public List<ClaudeWorkspaceSession> findAll() {
        String sql = "SELECT * FROM " + TABLE + " ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    /**
     * 根据状态查询会话列表
     */
    public List<ClaudeWorkspaceSession> findByStatus(String status) {
        String sql = "SELECT * FROM " + TABLE + " WHERE status = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper, status);
    }

    /**
     * 删除会话
     */
    public void deleteById(String id) {
        String sql = "DELETE FROM " + TABLE + " WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    /**
     * 检查会话是否存在
     */
    public boolean existsById(String id) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return count != null && count > 0;
    }
}
