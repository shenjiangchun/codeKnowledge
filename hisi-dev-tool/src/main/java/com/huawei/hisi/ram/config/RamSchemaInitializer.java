package com.huawei.hisi.ram.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * SQLite schema auto-initialization for the RAM (Requirement Analysis Master) module.
 * Creates {@code agent_session} and {@code agent_event} on startup using
 * CREATE TABLE IF NOT EXISTS. Follows the same pattern as
 * {@code com.huawei.hisi.config.SQLiteSchemaInitializer}.
 */
@Component
public class RamSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(RamSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public RamSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        log.info("[RAM-SQLite] Initializing schema...");

        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS agent_session (
                id                        INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id                   TEXT NOT NULL,
                plan_id                   TEXT,
                status                    TEXT NOT NULL,
                current_node              TEXT,
                step_count                INTEGER NOT NULL DEFAULT 0,
                last_checkpoint_event_id  INTEGER,
                cache_key                 TEXT,
                version                   INTEGER NOT NULL DEFAULT 0,
                created_at                INTEGER DEFAULT (strftime('%s','now')),
                updated_at                INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_session_user ON agent_session(user_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_session_status ON agent_session(status)");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS agent_event (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id        INTEGER NOT NULL REFERENCES agent_session(id),
                seq               INTEGER NOT NULL,
                type              TEXT NOT NULL,
                payload           TEXT,
                tool_use_id       TEXT,
                parent_event_id   INTEGER,
                idempotency_key   TEXT NOT NULL,
                cumulative_tokens INTEGER NOT NULL DEFAULT 0,
                retry_count       INTEGER NOT NULL DEFAULT 0,
                clarify_round_no  INTEGER,
                inputs_hash       CHAR(64),
                circuit_state     TEXT NOT NULL DEFAULT 'OK',
                cost_usd_cents    INTEGER NOT NULL DEFAULT 0,
                validator_status  TEXT NOT NULL DEFAULT 'OK',
                created_at        INTEGER DEFAULT (strftime('%s','now')),
                CONSTRAINT uk_agent_event_idem UNIQUE (idempotency_key),
                CONSTRAINT uk_agent_event_seq  UNIQUE (session_id, seq)
            )
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_event_session_tokens "
                + "ON agent_event(session_id, cumulative_tokens)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_event_session_seq "
                + "ON agent_event(session_id, seq)");

        try {
            jdbcTemplate.execute("ALTER TABLE agent_session ADD COLUMN session_type TEXT DEFAULT 'RAM'");
            log.info("[RAM] Added session_type column to agent_session");
        } catch (Exception e) {
            // Column already exists — safe to ignore on subsequent startups
        }

        log.info("[RAM-SQLite] Schema initialization complete - 2 tables ensured");
    }
}
