package com.huawei.hisi.ram.repository.impl;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JdbcTemplate-backed {@link AgentEventRepository}. Table is created by
 * {@link com.huawei.hisi.ram.config.RamSchemaInitializer}.
 *
 * <p>{@link #append(AgentEvent)} relies on SQLite's
 * {@code INSERT ... ON CONFLICT(idempotency_key) DO NOTHING} (SQLite >= 3.24) to
 * make retries safe.
 */
@Repository
public class JdbcAgentEventRepository implements AgentEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO agent_event
                (session_id, seq, type, payload, tool_use_id, parent_event_id,
                 idempotency_key, cumulative_tokens, retry_count, clarify_round_no,
                 inputs_hash, circuit_state, cost_usd_cents, validator_status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(idempotency_key) DO NOTHING
            """;

    private static final String SELECT_COLS = """
            SELECT id, session_id, seq, type, payload, tool_use_id, parent_event_id,
                   idempotency_key, cumulative_tokens, retry_count, clarify_round_no,
                   inputs_hash, circuit_state, cost_usd_cents, validator_status, created_at
            FROM agent_event
            """;

    private final JdbcTemplate jdbc;

    public JdbcAgentEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<AgentEvent> mapper = (rs, n) -> AgentEvent.builder()
            .id(rs.getLong("id"))
            .sessionId(rs.getLong("session_id"))
            .seq(rs.getLong("seq"))
            .type(EventType.valueOf(rs.getString("type")))
            .payload(rs.getString("payload"))
            .toolUseId(rs.getString("tool_use_id"))
            .parentEventId(nullableLong(rs.getObject("parent_event_id")))
            .idempotencyKey(rs.getString("idempotency_key"))
            .cumulativeTokens(rs.getLong("cumulative_tokens"))
            .retryCount(rs.getInt("retry_count"))
            .clarifyRoundNo(nullableInt(rs.getObject("clarify_round_no")))
            .inputsHash(rs.getString("inputs_hash"))
            .circuitState(rs.getString("circuit_state"))
            .costUsdCents(rs.getInt("cost_usd_cents"))
            .validatorStatus(rs.getString("validator_status"))
            .createdAt(rs.getLong("created_at"))
            .build();

    @Override
    public AgentEvent append(AgentEvent e) {
        if (e.getIdempotencyKey() == null || e.getIdempotencyKey().isBlank()) {
            throw new IllegalArgumentException("AgentEvent.idempotencyKey is required");
        }
        if (e.getType() == null) {
            throw new IllegalArgumentException("AgentEvent.type is required");
        }
        jdbc.update(INSERT_SQL,
                e.getSessionId(),
                e.getSeq(),
                e.getType().name(),
                e.getPayload(),
                e.getToolUseId(),
                e.getParentEventId(),
                e.getIdempotencyKey(),
                e.getCumulativeTokens(),
                e.getRetryCount(),
                e.getClarifyRoundNo(),
                e.getInputsHash(),
                e.getCircuitState() == null ? "OK" : e.getCircuitState(),
                e.getCostUsdCents(),
                e.getValidatorStatus() == null ? "OK" : e.getValidatorStatus(),
                e.getCreatedAt());
        return findByIdempotencyKey(e.getIdempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "append failed: " + e.getIdempotencyKey()));
    }

    @Override
    public Optional<AgentEvent> findByIdempotencyKey(String idempotencyKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    SELECT_COLS + " WHERE idempotency_key = ?", mapper, idempotencyKey));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<AgentEvent> findBySessionId(long sessionId) {
        return jdbc.query(SELECT_COLS + " WHERE session_id = ? ORDER BY seq ASC", mapper, sessionId);
    }

    @Override
    public long countBySessionId(long sessionId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_event WHERE session_id = ?", Long.class, sessionId);
        return c == null ? 0L : c;
    }

    @Override
    public long findMaxSeq(long sessionId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM agent_event WHERE session_id = ?",
                Long.class, sessionId);
        return max == null ? 0L : max;
    }

    private static Long nullableLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.valueOf(o.toString());
    }

    private static Integer nullableInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        return Integer.valueOf(o.toString());
    }
}
