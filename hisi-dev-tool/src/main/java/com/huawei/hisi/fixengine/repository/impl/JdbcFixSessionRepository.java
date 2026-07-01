package com.huawei.hisi.fixengine.repository.impl;

import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JdbcTemplate-backed {@link FixSessionRepository}. Table is created by
 * {@link com.huawei.hisi.fixengine.config.FixSchemaInitializer}.
 *
 * <p>Uses snowflake-style String IDs (generated in-memory, no AUTOINCREMENT).
 */
@Repository
public class JdbcFixSessionRepository implements FixSessionRepository {

    /** Snowflake epoch offset (2024-01-01 00:00:00 UTC in millis) */
    private static final long EPOCH = 1704067200000L;
    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final long MACHINE_ID = 1L; // 单机部署默认值

    private static final String INSERT_SQL = """
            INSERT INTO fix_session
                (id, report_id, chat_session_id, session_type, status,
                 worktree_path, branch_name, commit_hash,
                 throw_point_sig, error_msg,
                 tenant_id, create_by, update_by, del_flag,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_SQL = """
            SELECT id, report_id, chat_session_id, session_type, status,
                   worktree_path, branch_name, commit_hash,
                   throw_point_sig, error_msg,
                   tenant_id, create_by, update_by, del_flag,
                   created_at, updated_at
            FROM fix_session WHERE id = ? AND del_flag = 0
            """;

    private static final String SELECT_BY_REPORT_SQL = """
            SELECT id, report_id, chat_session_id, session_type, status,
                   worktree_path, branch_name, commit_hash,
                   throw_point_sig, error_msg,
                   tenant_id, create_by, update_by, del_flag,
                   created_at, updated_at
            FROM fix_session WHERE report_id = ? AND del_flag = 0 ORDER BY created_at DESC
            """;

    private static final String SELECT_BY_CHAT_SESSION_SQL = """
            SELECT id, report_id, chat_session_id, session_type, status,
                   worktree_path, branch_name, commit_hash,
                   throw_point_sig, error_msg,
                   tenant_id, create_by, update_by, del_flag,
                   created_at, updated_at
            FROM fix_session WHERE chat_session_id = ? AND del_flag = 0
            """;

    private final JdbcTemplate jdbc;

    public JdbcFixSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<FixSession> mapper = (rs, n) -> FixSession.builder()
            .id(rs.getString("id"))
            .reportId(rs.getString("report_id"))
            .chatSessionId(rs.getString("chat_session_id"))
            .sessionType(rs.getString("session_type"))
            .status(rs.getString("status"))
            .worktreePath(rs.getString("worktree_path"))
            .branchName(rs.getString("branch_name"))
            .commitHash(rs.getString("commit_hash"))
            .throwPointSig(rs.getString("throw_point_sig"))
            .errorMsg(rs.getString("error_msg"))
            .tenantId(rs.getString("tenant_id"))
            .createBy(rs.getString("create_by"))
            .updateBy(rs.getString("update_by"))
            .delFlag(rs.getInt("del_flag"))
            .createdAt(rs.getLong("created_at"))
            .updatedAt(rs.getLong("updated_at"))
            .build();

    @Override
    public FixSession save(FixSession s) {
        String id = generateSnowflakeId();
        s.setId(id);

        jdbc.update(INSERT_SQL,
                id,
                s.getReportId(),
                s.getChatSessionId(),
                s.getSessionType(),
                s.getStatus(),
                s.getWorktreePath(),
                s.getBranchName(),
                s.getCommitHash(),
                s.getThrowPointSig(),
                s.getErrorMsg(),
                s.getTenantId(),
                s.getCreateBy(),
                s.getUpdateBy(),
                s.getDelFlag(),
                s.getCreatedAt(),
                s.getUpdatedAt());
        return s;
    }

    @Override
    public Optional<FixSession> findById(String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_SQL, mapper, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<FixSession> findByReportId(String reportId) {
        return jdbc.query(SELECT_BY_REPORT_SQL, mapper, reportId);
    }

    @Override
    public Optional<FixSession> findByChatSessionId(String chatSessionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_CHAT_SESSION_SQL, mapper, chatSessionId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public int update(FixSession s) {
        String sql = """
                UPDATE fix_session
                SET report_id = ?, chat_session_id = ?, session_type = ?, status = ?,
                    worktree_path = ?, branch_name = ?, commit_hash = ?,
                    throw_point_sig = ?, error_msg = ?,
                    update_by = ?, updated_at = strftime('%s','now')
                WHERE id = ?
                """;
        return jdbc.update(sql,
                s.getReportId(), s.getChatSessionId(), s.getSessionType(), s.getStatus(),
                s.getWorktreePath(), s.getBranchName(), s.getCommitHash(),
                s.getThrowPointSig(), s.getErrorMsg(),
                s.getUpdateBy(),
                s.getId());
    }

    @Override
    public int updateStatus(String id, String status) {
        return jdbc.update(
                "UPDATE fix_session SET status = ?, updated_at = strftime('%s','now') WHERE id = ?",
                status, id);
    }

    /**
     * Generate a snowflake-style String ID.
     * Format: timestamp-offset(41bit) + machine-id(5bit) + sequence(8bit) → hex string
     */
    private static String generateSnowflakeId() {
        long now = System.currentTimeMillis() - EPOCH;
        long seq = SEQUENCE.incrementAndGet() & 0xFF;
        long id = (now << 13) | (MACHINE_ID << 8) | seq;
        return Long.toHexString(id);
    }
}
