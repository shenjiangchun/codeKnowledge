package com.huawei.hisi.ram.repository.impl;

import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

/**
 * JdbcTemplate-backed {@link AgentSessionRepository}. Table is created by
 * {@link com.huawei.hisi.ram.config.RamSchemaInitializer}.
 */
@Repository
public class JdbcAgentSessionRepository implements AgentSessionRepository {

    private static final String INSERT_SQL = """
            INSERT INTO agent_session
                (user_id, plan_id, status, current_node, step_count,
                 last_checkpoint_event_id, cache_key, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_SQL = """
            SELECT id, user_id, plan_id, status, current_node, step_count,
                   last_checkpoint_event_id, cache_key, version, created_at, updated_at
            FROM agent_session WHERE id = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcAgentSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<AgentSession> mapper = (rs, n) -> AgentSession.builder()
            .id(rs.getLong("id"))
            .userId(rs.getString("user_id"))
            .planId(rs.getString("plan_id"))
            .status(SessionStatus.valueOf(rs.getString("status")))
            .currentNode(rs.getString("current_node"))
            .stepCount(rs.getInt("step_count"))
            .lastCheckpointEventId(nullableLong(rs.getObject("last_checkpoint_event_id")))
            .cacheKey(rs.getString("cache_key"))
            .version(rs.getInt("version"))
            .createdAt(rs.getLong("created_at"))
            .updatedAt(rs.getLong("updated_at"))
            .build();

    @Override
    public AgentSession save(AgentSession s) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, s.getUserId());
            ps.setString(2, s.getPlanId());
            ps.setString(3, s.getStatus().name());
            ps.setString(4, s.getCurrentNode());
            ps.setInt(5, s.getStepCount());
            if (s.getLastCheckpointEventId() == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setLong(6, s.getLastCheckpointEventId());
            }
            ps.setString(7, s.getCacheKey());
            ps.setInt(8, s.getVersion());
            ps.setLong(9, s.getCreatedAt());
            ps.setLong(10, s.getUpdatedAt());
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to insert agent_session: no key generated");
        }
        s.setId(key.longValue());
        return s;
    }

    @Override
    public Optional<AgentSession> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_SQL, mapper, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Optimistic-lock update keyed on {@code (id, version)}. The input {@code s}
     * is NOT mutated by this method &mdash; in particular, its {@code version}
     * field still reflects the pre-update value on return. Callers MUST use the
     * returned {@link Optional} to obtain the freshly-loaded session with the
     * bumped version; reusing {@code s} for further updates will fail the
     * version check.
     *
     * @return the updated session (with incremented version) on success, or
     *         {@link Optional#empty()} if no row matched {@code (id, version)}
     *         (concurrent update lost the race).
     */
    @Override
    public Optional<AgentSession> update(AgentSession s) {
        String sql = """
                UPDATE agent_session
                SET plan_id = ?, status = ?, current_node = ?, step_count = ?,
                    last_checkpoint_event_id = ?, cache_key = ?,
                    version = version + 1,
                    updated_at = strftime('%s','now')
                WHERE id = ? AND version = ?
                """;
        int rows = jdbc.update(sql,
                s.getPlanId(), s.getStatus().name(), s.getCurrentNode(), s.getStepCount(),
                s.getLastCheckpointEventId(), s.getCacheKey(),
                s.getId(), s.getVersion());
        if (rows == 0) {
            return Optional.empty();
        }
        return findById(s.getId());
    }

    @Override
    public int updateStatus(long id, SessionStatus status) {
        return jdbc.update(
                "UPDATE agent_session SET status = ?, updated_at = strftime('%s','now') WHERE id = ?",
                status.name(), id);
    }

    private static Long nullableLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.valueOf(o.toString());
    }
}
