package com.huawei.hisi.workbench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 工作台的 SQLite 建表（照 {@code FixSchemaInitializer} 的模块化写法）。
 *
 * <p>三张表：
 * <ul>
 *   <li>{@code workbench_item}：跨项目聚合的待办（日志 issue + 架构 issue），按键 upsert；</li>
 *   <li>{@code workbench_item_day}：每项每日累计出现次数，供「近 7 日」趋势；</li>
 *   <li>{@code workbench_fix_run}：一次 SDD 自动修复运行，与待办一对一（同时只允许一个）。</li>
 * </ul>
 */
@Component
public class WorkbenchSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public WorkbenchSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        log.info("[Workbench-SQLite] Initializing schema...");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS workbench_item (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                item_key         TEXT UNIQUE NOT NULL,
                source           TEXT NOT NULL,
                project_path     TEXT NOT NULL,
                title            TEXT NOT NULL,
                location         TEXT,
                severity         TEXT DEFAULT 'P2',
                status           TEXT DEFAULT 'NEW',
                confidence       TEXT,
                chain_status     TEXT,
                why              TEXT,
                suggestion       TEXT,
                evidence_json    TEXT,
                source_ref_json  TEXT,
                occurrence_count INTEGER DEFAULT 1,
                first_seen_at    INTEGER,
                last_seen_at     INTEGER,
                current_run_id   INTEGER,
                created_at       INTEGER DEFAULT (strftime('%s','now')),
                updated_at       INTEGER DEFAULT (strftime('%s','now'))
            )
            """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_workbench_item_status ON workbench_item(status)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_workbench_item_project ON workbench_item(project_path)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_workbench_item_source ON workbench_item(source)");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS workbench_item_day (
                item_key TEXT NOT NULL,
                day      TEXT NOT NULL,
                count    INTEGER DEFAULT 0,
                PRIMARY KEY (item_key, day)
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS workbench_fix_run (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                item_key         TEXT NOT NULL,
                session_uuid     TEXT UNIQUE NOT NULL,
                session_id       INTEGER,
                project_path     TEXT NOT NULL,
                status           TEXT DEFAULT 'RUNNING',
                current_stage    TEXT,
                stage_index      INTEGER DEFAULT 0,
                iteration        INTEGER DEFAULT 1,
                chosen_option    TEXT,
                push_requested   INTEGER DEFAULT 0,
                worktree_path    TEXT,
                branch_name      TEXT,
                commit_hash      TEXT,
                pushed           INTEGER DEFAULT 0,
                error_message    TEXT,
                created_at       INTEGER DEFAULT (strftime('%s','now')),
                updated_at       INTEGER DEFAULT (strftime('%s','now'))
            )
            """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_workbench_fix_run_item ON workbench_fix_run(item_key)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_workbench_fix_run_status ON workbench_fix_run(status)");

        log.info("[Workbench-SQLite] Schema initialization complete - 3 tables ensured");
    }
}
