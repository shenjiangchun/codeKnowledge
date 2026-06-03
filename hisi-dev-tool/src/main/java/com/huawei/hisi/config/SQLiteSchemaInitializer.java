package com.huawei.hisi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * SQLite schema auto-initialization.
 * Creates all required tables on startup using CREATE TABLE IF NOT EXISTS.
 */
@Component
public class SQLiteSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SQLiteSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public SQLiteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        log.info("[SQLite] Initializing schema...");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS app_config (
                key        TEXT PRIMARY KEY,
                value      TEXT,
                updated_at INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS claude_session (
                id                   TEXT PRIMARY KEY,
                title                TEXT,
                scene                TEXT,
                status               TEXT DEFAULT 'active',
                metadata             TEXT,
                working_directory    TEXT,
                claude_session_code  TEXT,
                created_at INTEGER DEFAULT (strftime('%s','now')),
                updated_at INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS claude_message (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL REFERENCES claude_session(id),
                role       TEXT NOT NULL,
                content    TEXT NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS workspace_session (
                id             TEXT PRIMARY KEY,
                project_path   TEXT,
                initial_prompt TEXT,
                status         TEXT DEFAULT 'active',
                created_at     INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS prompt_template (
                template_key TEXT PRIMARY KEY,
                name         TEXT NOT NULL,
                content      TEXT NOT NULL,
                variables    TEXT,
                description  TEXT,
                updated_at   INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS generation_task (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                task_type     TEXT NOT NULL,
                project_path  TEXT NOT NULL,
                status        TEXT DEFAULT 'PENDING',
                progress      INTEGER DEFAULT 0,
                total_count   INTEGER DEFAULT 0,
                success_count INTEGER DEFAULT 0,
                fail_count    INTEGER DEFAULT 0,
                error_message TEXT,
                started_at    INTEGER,
                finished_at   INTEGER,
                created_at    INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS log_analysis_report (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                report_id           INTEGER UNIQUE,
                report_no           TEXT UNIQUE,
                user_id             TEXT,
                query_params        TEXT,
                log_message         TEXT,
                log_stack_trace     TEXT,
                filtered_stack_trace TEXT,
                error_type          TEXT,
                trace_id            TEXT,
                service_name        TEXT,
                log_summary         TEXT,
                error_summary       TEXT,
                root_cause          TEXT,
                fix_suggestions     TEXT,
                code_snippets       TEXT,
                status              TEXT DEFAULT 'pending',
                created_at          INTEGER DEFAULT (strftime('%s','now')),
                updated_at          INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS apm_session (
                id              TEXT PRIMARY KEY,
                project_path    TEXT NOT NULL,
                service_name    TEXT,
                target_port     INTEGER,
                status          TEXT DEFAULT 'CREATED',
                created_at      INTEGER DEFAULT (strftime('%s','now')),
                finished_at     INTEGER
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS apm_span (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id      TEXT NOT NULL,
                trace_id        TEXT NOT NULL,
                span_id         TEXT NOT NULL,
                parent_span_id  TEXT,
                service_name    TEXT,
                operation_name  TEXT NOT NULL,
                span_kind       TEXT,
                start_time_ns   INTEGER NOT NULL,
                end_time_ns     INTEGER NOT NULL,
                status_code     TEXT,
                status_message  TEXT,
                attributes      TEXT,
                resource_attrs  TEXT,
                kg_node_id      TEXT,
                kg_match_level  INTEGER DEFAULT 3,
                created_at      INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_apm_span_trace ON apm_span(trace_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_apm_span_session ON apm_span(session_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_apm_span_created ON apm_span(created_at)");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS apm_test_case (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                name         VARCHAR(200) NOT NULL,
                project_path VARCHAR(500) NOT NULL,
                entry_node_id VARCHAR(200),
                method       VARCHAR(10),
                url          VARCHAR(500),
                headers      TEXT,
                params       TEXT,
                body         TEXT,
                created_at   INTEGER DEFAULT (strftime('%s','now')),
                updated_at   INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_apm_test_case_project ON apm_test_case(project_path)");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS glossary_term (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                project_path VARCHAR(500) NOT NULL,
                term         VARCHAR(100) NOT NULL,
                synonym      VARCHAR(100) NOT NULL,
                context      VARCHAR(200),
                created_at   INTEGER DEFAULT (strftime('%s','now')),
                updated_at   INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        // 迁移：旧表使用 wrong_term/correct_term，重命名为 term/synonym
        migrateGlossaryColumns(jdbcTemplate);

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_glossary_project ON glossary_term(project_path)");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS remote_project (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                name                TEXT    NOT NULL,
                git_url             TEXT    NOT NULL,
                username            TEXT,
                encrypted_password  TEXT,
                branch              TEXT    DEFAULT 'main',
                local_path          TEXT,
                clone_status        TEXT    DEFAULT 'PENDING',
                last_sync_at        INTEGER,
                created_at          INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        // Add clone_error column if upgrading from older schema
        try {
            jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN clone_error TEXT");
            log.info("[SQLite] Added clone_error column to remote_project");
        } catch (Exception ignored) {
            // Column already exists
        }

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS kg_schedule (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                project_path    TEXT    NOT NULL,
                cron_expression TEXT    NOT NULL,
                task_type       TEXT    NOT NULL,
                enabled         INTEGER DEFAULT 1,
                last_run_at     INTEGER,
                next_run_at     INTEGER,
                created_at      INTEGER DEFAULT (strftime('%s','now'))
            )
            """);

        log.info("[SQLite] Schema initialization complete - 13 tables ensured");
    }

    private void migrateGlossaryColumns(JdbcTemplate jdbcTemplate) {
        try {
            // 检查是否存在旧列名 wrong_term，如有则重命名
            jdbcTemplate.execute("ALTER TABLE glossary_term RENAME COLUMN wrong_term TO term");
            log.info("[SQLite] Migrated glossary_term: wrong_term → term");
        } catch (Exception ignored) {
            // 列不存在或已迁移，忽略
        }
        try {
            jdbcTemplate.execute("ALTER TABLE glossary_term RENAME COLUMN correct_term TO synonym");
            log.info("[SQLite] Migrated glossary_term: correct_term → synonym");
        } catch (Exception ignored) {
            // 列不存在或已迁移，忽略
        }
    }
}
