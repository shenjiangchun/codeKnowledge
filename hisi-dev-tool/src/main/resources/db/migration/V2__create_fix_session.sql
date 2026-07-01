-- V2__create_fix_session.sql
-- DDL for the fix_session table (SQLite syntax).
--
-- Actual table creation is handled by FixSchemaInitializer at application
-- startup (CREATE TABLE IF NOT EXISTS). This file serves as the canonical
-- schema reference and can be used if Flyway or another migration tool is
-- adopted later.
--
-- Design doc: docs/exception-auto-fix/03-multi-turn-dialog-history.md §3.1

CREATE TABLE IF NOT EXISTS fix_session (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id       INTEGER NOT NULL,
    chat_session_id INTEGER,
    session_type    TEXT    DEFAULT 'FIX',
    status          TEXT    DEFAULT 'RUNNING',
    worktree_path   TEXT,
    branch_name     TEXT,
    commit_hash     TEXT,
    throw_point_sig TEXT,
    error_msg       TEXT,
    created_at      INTEGER DEFAULT (strftime('%s','now')),
    updated_at      INTEGER DEFAULT (strftime('%s','now'))
);

CREATE INDEX IF NOT EXISTS idx_fix_session_report ON fix_session(report_id);
CREATE INDEX IF NOT EXISTS idx_fix_session_chat   ON fix_session(chat_session_id);
CREATE INDEX IF NOT EXISTS idx_fix_session_branch ON fix_session(branch_name);
