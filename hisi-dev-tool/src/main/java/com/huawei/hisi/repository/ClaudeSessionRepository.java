package com.huawei.hisi.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ClaudeMessage;
import com.huawei.hisi.model.ClaudeSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Claude 会话数据访问层 (SQLite)
 * Tables created by SQLiteSchemaInitializer.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ClaudeSessionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // ==================== Session CRUD ====================

    /**
     * 保存会话 (upsert)
     */
    public void saveSession(ClaudeSession session) {
        String sql = """
            INSERT INTO claude_session (id, title, scene, status, metadata, working_directory, claude_session_code, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%s','now'), strftime('%s','now'))
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                scene = excluded.scene,
                status = excluded.status,
                metadata = excluded.metadata,
                working_directory = excluded.working_directory,
                claude_session_code = excluded.claude_session_code,
                updated_at = strftime('%s','now')
            """;

        try {
            jdbcTemplate.update(sql,
                session.getId(),
                session.getTitle(),
                session.getScene(),
                session.getStatus() != null ? session.getStatus() : "active",
                session.getMetadata(),
                session.getWorkingDirectory(),
                session.getClaudeSessionCode()
            );
            log.debug("会话保存成功: {}", session.getId());
        } catch (Exception e) {
            log.error("保存会话失败: {}", e.getMessage());
            throw new RuntimeException("保存会话失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据ID查找会话
     */
    public Optional<ClaudeSession> findSessionById(String id) {
        String sql = "SELECT * FROM claude_session WHERE id = ?";
        try {
            List<ClaudeSession> results = jdbcTemplate.query(sql, new SessionRowMapper(), id);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            log.error("查询会话失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 根据状态查询会话列表
     */
    public List<ClaudeSession> findSessionsByStatus(String status, int limit) {
        String sql = "SELECT * FROM claude_session WHERE status = ? ORDER BY created_at DESC LIMIT ?";
        try {
            return jdbcTemplate.query(sql, new SessionRowMapper(), status, limit);
        } catch (Exception e) {
            log.error("查询会话列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询所有会话列表（分页）
     */
    public List<ClaudeSession> findAllSessions(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String sql = "SELECT * FROM claude_session ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try {
            return jdbcTemplate.query(sql, new SessionRowMapper(), pageSize, offset);
        } catch (Exception e) {
            log.error("查询会话列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 统计会话总数
     */
    public int countSessions(String status) {
        String sql = status != null ?
            "SELECT COUNT(*) FROM claude_session WHERE status = ?" :
            "SELECT COUNT(*) FROM claude_session";
        try {
            Integer count = status != null ?
                jdbcTemplate.queryForObject(sql, Integer.class, status) :
                jdbcTemplate.queryForObject(sql, Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("统计会话数失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 更新会话状态
     */
    public void updateSessionStatus(String id, String status) {
        String sql = "UPDATE claude_session SET status = ?, updated_at = strftime('%s','now') WHERE id = ?";
        try {
            jdbcTemplate.update(sql, status, id);
        } catch (Exception e) {
            log.error("更新会话状态失败: {}", e.getMessage());
        }
    }

    /**
     * 更新会话标题
     */
    public void updateSessionTitle(String id, String title) {
        String sql = "UPDATE claude_session SET title = ?, updated_at = strftime('%s','now') WHERE id = ?";
        try {
            jdbcTemplate.update(sql, title, id);
        } catch (Exception e) {
            log.error("更新会话标题失败: {}", e.getMessage());
        }
    }

    /**
     * 更新 Claude 会话码
     */
    public void updateClaudeSessionCode(String id, String claudeSessionCode) {
        String sql = "UPDATE claude_session SET claude_session_code = ?, updated_at = strftime('%s','now') WHERE id = ?";
        try {
            jdbcTemplate.update(sql, claudeSessionCode, id);
        } catch (Exception e) {
            log.error("更新Claude会话码失败: {}", e.getMessage());
        }
    }

    /**
     * 删除会话
     */
    public void deleteSession(String id) {
        try {
            jdbcTemplate.update("DELETE FROM claude_message WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM claude_session WHERE id = ?", id);
        } catch (Exception e) {
            log.error("删除会话失败: {}", e.getMessage());
        }
    }

    // ==================== Message CRUD ====================

    /**
     * 保存消息
     */
    public void saveMessage(ClaudeMessage message) {
        String sql = "INSERT INTO claude_message (session_id, role, content, created_at) VALUES (?, ?, ?, strftime('%s','now'))";
        try {
            jdbcTemplate.update(sql,
                message.getSessionId(),
                message.getRole(),
                message.getContent()
            );
        } catch (Exception e) {
            log.error("保存消息失败: {}", e.getMessage());
        }
    }

    /**
     * 查询会话的所有消息
     */
    public List<ClaudeMessage> findMessagesBySessionId(String sessionId) {
        String sql = "SELECT * FROM claude_message WHERE session_id = ? ORDER BY created_at ASC";
        try {
            return jdbcTemplate.query(sql, new MessageRowMapper(), sessionId);
        } catch (Exception e) {
            log.error("查询消息列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除会话的所有消息
     */
    public void deleteMessagesBySessionId(String sessionId) {
        String sql = "DELETE FROM claude_message WHERE session_id = ?";
        try {
            jdbcTemplate.update(sql, sessionId);
        } catch (Exception e) {
            log.error("清除会话消息失败: {}", e.getMessage());
        }
    }

    // ==================== Row Mappers ====================

    private static LocalDateTime readEpoch(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long val = rs.getLong(col);
        return rs.wasNull() ? null : Instant.ofEpochSecond(val).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static class SessionRowMapper implements RowMapper<ClaudeSession> {
        @Override
        public ClaudeSession mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            ClaudeSession session = new ClaudeSession();
            session.setId(rs.getString("id"));
            session.setTitle(rs.getString("title"));
            session.setScene(rs.getString("scene"));
            session.setStatus(rs.getString("status"));
            session.setMetadata(rs.getString("metadata"));
            session.setWorkingDirectory(rs.getString("working_directory"));
            session.setClaudeSessionCode(rs.getString("claude_session_code"));
            session.setCreatedAt(readEpoch(rs, "created_at"));
            session.setUpdatedAt(readEpoch(rs, "updated_at"));
            return session;
        }
    }

    private static class MessageRowMapper implements RowMapper<ClaudeMessage> {
        @Override
        public ClaudeMessage mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            ClaudeMessage message = new ClaudeMessage();
            message.setId(rs.getLong("id"));
            message.setSessionId(rs.getString("session_id"));
            message.setRole(rs.getString("role"));
            message.setContent(rs.getString("content"));
            message.setCreatedAt(readEpoch(rs, "created_at"));
            return message;
        }
    }
}
