package com.huawei.hisi.scheduler.repository;

import com.huawei.hisi.scheduler.model.KgSchedule;
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

@Repository
@RequiredArgsConstructor
public class KgScheduleRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<KgSchedule> ROW_MAPPER = (rs, rowNum) ->
        KgSchedule.builder()
            .id(rs.getLong("id"))
            .projectPath(rs.getString("project_path"))
            .cronExpression(rs.getString("cron_expression"))
            .buildMode(rs.getString("build_mode"))
            .enabled(rs.getInt("enabled") == 1)
            .gitPullEnabled(rs.getInt("git_pull_enabled") == 1)
            .branch(rs.getString("branch"))
            .refreshDescription(rs.getInt("refresh_description") == 1)
            .refreshArchitecture(rs.getInt("refresh_architecture") == 1)
            .lastRunAt(rs.getObject("last_run_at") != null ? rs.getLong("last_run_at") : null)
            .nextRunAt(rs.getObject("next_run_at") != null ? rs.getLong("next_run_at") : null)
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
            .build();

    public List<KgSchedule> findAll() {
        return jdbcTemplate.query(
            "SELECT * FROM kg_schedule ORDER BY created_at DESC", ROW_MAPPER
        );
    }

    public Optional<KgSchedule> findById(Long id) {
        List<KgSchedule> results = jdbcTemplate.query(
            "SELECT * FROM kg_schedule WHERE id = ?", ROW_MAPPER, id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<KgSchedule> findEnabled() {
        return jdbcTemplate.query(
            "SELECT * FROM kg_schedule WHERE enabled = 1 ORDER BY created_at DESC", ROW_MAPPER
        );
    }

    public KgSchedule insert(KgSchedule schedule) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO kg_schedule (project_path, cron_expression, build_mode, enabled, git_pull_enabled, branch, refresh_description, refresh_architecture) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, schedule.getProjectPath());
            ps.setString(2, schedule.getCronExpression());
            ps.setString(3, schedule.getBuildMode());
            ps.setInt(4, schedule.isEnabled() ? 1 : 0);
            ps.setInt(5, schedule.isGitPullEnabled() ? 1 : 0);
            ps.setString(6, schedule.getBranch());
            ps.setInt(7, schedule.isRefreshDescription() ? 1 : 0);
            ps.setInt(8, schedule.isRefreshArchitecture() ? 1 : 0);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            schedule.setId(key.longValue());
        }
        return schedule;
    }

    public int update(KgSchedule schedule) {
        return jdbcTemplate.update(
            "UPDATE kg_schedule SET project_path = ?, cron_expression = ?, build_mode = ?, enabled = ?, " +
            "git_pull_enabled = ?, branch = ?, refresh_description = ?, refresh_architecture = ? WHERE id = ?",
            schedule.getProjectPath(), schedule.getCronExpression(),
            schedule.getBuildMode(), schedule.isEnabled() ? 1 : 0,
            schedule.isGitPullEnabled() ? 1 : 0, schedule.getBranch(),
            schedule.isRefreshDescription() ? 1 : 0, schedule.isRefreshArchitecture() ? 1 : 0,
            schedule.getId()
        );
    }

    public int updateLastRunAt(long id, long epochSeconds) {
        return jdbcTemplate.update(
            "UPDATE kg_schedule SET last_run_at = ? WHERE id = ?", epochSeconds, id
        );
    }

    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM kg_schedule WHERE id = ?", id);
    }
}
