package com.huawei.hisi.fixengine.repository.impl;

import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * JdbcTemplate-backed {@link FixSessionRepository}. Table is created by
 * {@link com.huawei.hisi.fixengine.config.FixSchemaInitializer}.
 */
@Repository
public class JdbcFixSessionRepository implements FixSessionRepository {

    private static final String INSERT_SQL = """
            INSERT INTO fix_session
                (report_id, chat_session_id, session_type, status,
                 worktree_path, branch_name, commit_hash,
                 throw_point_sig, error_msg,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_SQL = """
            SELECT id, report_id, chat_session_id, session_type, status,
                   worktree_path, branch_name, commit_hash,
                   throw_point_sig, error_msg,
                   created_at, updated_at
            FROM fix_session WHERE id = ?
            """;

    private static final String SELECT_BY_REPORT_SQL = """
            SELECT id, report_id, chat_session_id, session_type, status,
                   worktree_path, branch_name, commit_hash,
                   throw_point_sig, error_msg,
                   created_at, updated_at
            FROM fix_session WHERE report_id = ? ORDER BY created_at DESC
            """;

    private static final String SELECT_BY_CHAT_SESSION_SQL = """
            SELECT id, report_id, chat_session_id, session_type, status,
                   worktree_path, branch_name, commit_hash,
                   throw_point_sig, error_msg,
                   created_at, updated_at
            FROM fix_session WHERE chat_session_id = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcFixSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<FixSession> mapper = (rs, n) -> FixSession.builder()
            .id(rs.getLong("id"))
            .reportId(rs.getLong("report_id"))
            .chatSessionId(nullableLong(rs.getObject("chat_session_id")))
            .sessionType(rs.getString("session_type"))
            .status(rs.getString("status"))
            .worktreePath(rs.getString("worktree_path"))
            .branchName(rs.getString("branch_name"))
            .commitHash(rs.getString("commit_hash"))
            .throwPointSig(rs.getString("throw_point_sig"))
            .errorMsg(rs.getString("error_msg"))
            .createdAt(rs.getLong("created_at"))
            .updatedAt(rs.getLong("updated_at"))
            .build();

    @Override
    public FixSession save(FixSession s) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, s.getReportId());
            if (s.getChatSessionId() == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, s.getChatSessionId());
            }
            ps.setString(3, s.getSessionType());
            ps.setString(4, s.getStatus());
            ps.setString(5, s.getWorktreePath());
            ps.setString(6, s.getBranchName());
            ps.setString(7, s.getCommitHash());
            ps.setString(8, s.getThrowPointSig());
            ps.setString(9, s.getErrorMsg());
            ps.setLong(10, s.getCreatedAt());
            ps.setLong(11, s.getUpdatedAt());
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to insert fix_session: no key generated");
        }
        s.setId(key.longValue());
        return s;
    }

    @Override
    public Optional<FixSession> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_SQL, mapper, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<FixSession> findByReportId(long reportId) {
        return jdbc.query(SELECT_BY_REPORT_SQL, mapper, reportId);
    }

    @Override
    public Optional<FixSession> findByChatSessionId(long chatSessionId) {
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
                    updated_at = strftime('%s','now')
                WHERE id = ?
                """;
        return jdbc.update(sql,
                s.getReportId(), s.getChatSessionId(), s.getSessionType(), s.getStatus(),
                s.getWorktreePath(), s.getBranchName(), s.getCommitHash(),
                s.getThrowPointSig(), s.getErrorMsg(),
                s.getId());
    }

    @Override
    public int updateStatus(long id, String status) {
        return jdbc.update(
                "UPDATE fix_session SET status = ?, updated_at = strftime('%s','now') WHERE id = ?",
                status, id);
    }

    private static Long nullableLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.valueOf(o.toString());
    }
}
