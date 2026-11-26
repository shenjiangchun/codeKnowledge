package com.huawei.hisi.workbench.repository;

import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import lombok.RequiredArgsConstructor;
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
 * {@code workbench_fix_run} 的持久化（JdbcTemplate，仿 {@code JdbcFixSessionRepository}）。
 */
@Repository
@RequiredArgsConstructor
public class WorkbenchFixRunRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<WorkbenchFixRun> ROW_MAPPER = (rs, rowNum) ->
        WorkbenchFixRun.builder()
            .id(rs.getLong("id"))
            .itemKey(rs.getString("item_key"))
            .sessionUuid(rs.getString("session_uuid"))
            .sessionId(rs.getObject("session_id") != null ? rs.getLong("session_id") : null)
            .projectPath(rs.getString("project_path"))
            .status(rs.getString("status"))
            .currentStage(rs.getString("current_stage"))
            .stageIndex(rs.getInt("stage_index"))
            .iteration(rs.getInt("iteration"))
            .chosenOption(rs.getString("chosen_option"))
            .pushRequested(rs.getInt("push_requested") == 1)
            .worktreePath(rs.getString("worktree_path"))
            .branchName(rs.getString("branch_name"))
            .commitHash(rs.getString("commit_hash"))
            .pushed(rs.getInt("pushed") == 1)
            .errorMessage(rs.getString("error_message"))
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
            .updatedAt(rs.getObject("updated_at") != null ? rs.getLong("updated_at") : null)
            .build();

    public WorkbenchFixRun save(WorkbenchFixRun run) {
        if (run.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO workbench_fix_run (item_key, session_uuid, session_id, project_path, status,
                        current_stage, stage_index, iteration, chosen_option, push_requested,
                        worktree_path, branch_name, commit_hash, pushed, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, run.getItemKey());
                ps.setString(2, run.getSessionUuid());
                ps.setObject(3, run.getSessionId());
                ps.setString(4, run.getProjectPath());
                ps.setString(5, run.getStatus());
                ps.setString(6, run.getCurrentStage());
                ps.setInt(7, run.getStageIndex());
                ps.setInt(8, run.getIteration());
                ps.setString(9, run.getChosenOption());
                ps.setInt(10, run.isPushRequested() ? 1 : 0);
                ps.setString(11, run.getWorktreePath());
                ps.setString(12, run.getBranchName());
                ps.setString(13, run.getCommitHash());
                ps.setInt(14, run.isPushed() ? 1 : 0);
                ps.setString(15, run.getErrorMessage());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                run.setId(key.longValue());
            }
            return run;
        }
        jdbcTemplate.update("""
            UPDATE workbench_fix_run SET
                session_id = ?, project_path = ?, status = ?, current_stage = ?, stage_index = ?,
                iteration = ?, chosen_option = ?, push_requested = ?, worktree_path = ?, branch_name = ?,
                commit_hash = ?, pushed = ?, error_message = ?, updated_at = strftime('%s','now')
            WHERE id = ?
            """,
            run.getSessionId(), run.getProjectPath(), run.getStatus(), run.getCurrentStage(), run.getStageIndex(),
            run.getIteration(), run.getChosenOption(), run.isPushRequested() ? 1 : 0, run.getWorktreePath(),
            run.getBranchName(), run.getCommitHash(), run.isPushed() ? 1 : 0, run.getErrorMessage(), run.getId());
        return run;
    }

    public Optional<WorkbenchFixRun> findById(long id) {
        List<WorkbenchFixRun> rows = jdbcTemplate.query(
            "SELECT * FROM workbench_fix_run WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<WorkbenchFixRun> findByUuid(String sessionUuid) {
        List<WorkbenchFixRun> rows = jdbcTemplate.query(
            "SELECT * FROM workbench_fix_run WHERE session_uuid = ?", ROW_MAPPER, sessionUuid);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 待办当前活跃的运行（RUNNING 或 WAITING_CONFIRM），没有则为空。 */
    public Optional<WorkbenchFixRun> findActiveByItemKey(String itemKey) {
        List<WorkbenchFixRun> rows = jdbcTemplate.query(
            "SELECT * FROM workbench_fix_run WHERE item_key = ? AND status IN ('RUNNING','WAITING_CONFIRM') ORDER BY id DESC LIMIT 1",
            ROW_MAPPER, itemKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<WorkbenchFixRun> listAll() {
        return jdbcTemplate.query("SELECT * FROM workbench_fix_run ORDER BY id DESC", ROW_MAPPER);
    }

    public List<WorkbenchFixRun> listActive() {
        return jdbcTemplate.query(
            "SELECT * FROM workbench_fix_run WHERE status IN ('RUNNING','WAITING_CONFIRM') ORDER BY id DESC",
            ROW_MAPPER);
    }

    public long countActive() {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workbench_fix_run WHERE status IN ('RUNNING','WAITING_CONFIRM')", Long.class);
        return count == null ? 0L : count;
    }
}
