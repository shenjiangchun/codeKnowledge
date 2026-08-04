package com.huawei.hisi.fixengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * SQLite schema auto-initialization for the fix-engine module.
 * Creates {@code fix_session} on startup using CREATE TABLE IF NOT EXISTS.
 * Follows the same pattern as
 * {@code com.huawei.hisi.ram.config.RamSchemaInitializer}.
 */
@Component
public class FixSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(FixSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public FixSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        log.info("[Fix-SQLite] Initializing schema...");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS fix_session (
                id              TEXT PRIMARY KEY,
                report_id       TEXT NOT NULL,
                chat_session_id TEXT,
                session_type    TEXT    DEFAULT 'FIX',
                status          TEXT    DEFAULT 'RUNNING',
                worktree_path   TEXT,
                branch_name     TEXT,
                commit_hash     TEXT,
                throw_point_sig TEXT,
                error_msg       TEXT,
                tenant_id       TEXT    DEFAULT 'default',
                create_by       TEXT    DEFAULT 'system',
                update_by       TEXT    DEFAULT 'system',
                del_flag        INTEGER DEFAULT 0,
                created_at      INTEGER DEFAULT (strftime('%s','now')),
                updated_at      INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_fix_session_report ON fix_session(report_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_fix_session_chat ON fix_session(chat_session_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_fix_session_branch ON fix_session(branch_name)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_fix_session_tenant ON fix_session(tenant_id)");

        log.info("[Fix-SQLite] Schema initialization complete - 1 table ensured");
    }
}
