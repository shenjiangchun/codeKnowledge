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
                uuid                      TEXT,
                intent                    TEXT,
                project_paths             TEXT,
                rerun_from_node           TEXT,
                created_at                INTEGER DEFAULT (strftime('%s','now')),
                updated_at                INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_session_user ON agent_session(user_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_session_status ON agent_session(status)");

        // Idempotent ALTER TABLE migrations for existing databases
        addColumnIfNotExists("agent_session", "uuid", "TEXT");
        addColumnIfNotExists("agent_session", "intent", "TEXT");
        addColumnIfNotExists("agent_session", "project_paths", "TEXT");
        addColumnIfNotExists("agent_session", "rerun_from_node", "TEXT");
        addColumnIfNotExists("agent_session", "source_branch", "TEXT");
        addColumnIfNotExists("agent_session", "target_branch", "TEXT");
        addColumnIfNotExists("agent_session", "session_type", "TEXT DEFAULT 'DEMAND'");

        // Create indexes on new columns AFTER migration ensures they exist
        addIndexIfNotExists("idx_agent_session_uuid", "agent_session(uuid)");

        // B5: Historical data migration — fix merge-analysis sessions misclassified as DEMAND
        migrateMergeAnalysisSessionType();

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

        // Idempotent ALTER TABLE migrations for agent_event (in-turn injection: T11)
        addColumnIfNotExists("agent_event", "interrupted", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfNotExists("agent_event", "turn_id", "TEXT");
        addIndexIfNotExists("agent_event", "idx_agent_event_turn_id", "agent_event(turn_id)");

        log.info("[RAM-SQLite] Schema initialization complete - 2 tables ensured");
    }

    private void addColumnIfNotExists(String table, String column, String type) {
        try {
            boolean exists = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")")
                    .stream().anyMatch(row -> column.equalsIgnoreCase((String) row.get("name")));
            if (!exists) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                log.info("[RAM-SQLite] Added column {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("[RAM-SQLite] Migration {}.{} failed (may already exist): {}", table, column, e.getMessage());
        }
    }

    private void addIndexIfNotExists(String indexName, String indexSpec) {
        addIndexIfNotExists("agent_session", indexName, indexSpec);
    }

    private void addIndexIfNotExists(String table, String indexName, String indexSpec) {
        try {
            boolean exists = jdbcTemplate.queryForList("PRAGMA index_list(" + table + ")")
                    .stream().anyMatch(row -> indexName.equalsIgnoreCase((String) row.get("name")));
            if (!exists) {
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + indexSpec);
                log.info("[RAM-SQLite] Created index {}", indexName);
            }
        } catch (Exception e) {
            log.warn("[RAM-SQLite] Index {} failed: {}", indexName, e.getMessage());
        }
    }

    /**
     * B5: One-time migration — fix merge-analysis sessions that were incorrectly stored
     * as DEMAND because MergeAnalysisService.createSession() didn't set sessionType.
     * Identified by user_id = 'merge-analysis'.
     */
    private void migrateMergeAnalysisSessionType() {
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE agent_session SET session_type = 'MERGE_ANALYSIS' "
                    + "WHERE user_id = 'merge-analysis' AND session_type = 'DEMAND'");
            if (rows > 0) {
                log.info("[RAM-SQLite] Migrated {} merge-analysis sessions from DEMAND to MERGE_ANALYSIS", rows);
            }
        } catch (Exception e) {
            log.warn("[RAM-SQLite] Merge-analysis session_type migration failed: {}", e.getMessage());
        }
    }
}
