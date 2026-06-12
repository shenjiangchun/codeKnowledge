package com.huawei.hisi.repository;

import com.huawei.hisi.model.AppLogConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 应用日志配置数据访问层 (SQLite)
 *
 * Task 6: Repository for app_log_config table
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AppLogConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 查找所有启用的配置
     */
    public List<AppLogConfig> findAllActive() {
        String sql = "SELECT * FROM app_log_config WHERE enabled = 1";
        return jdbcTemplate.query(sql, new AppLogConfigRowMapper());
    }

    /**
     * 根据appId查找配置
     */
    public AppLogConfig findByAppId(String appId) {
        String sql = "SELECT * FROM app_log_config WHERE app_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new AppLogConfigRowMapper(), appId);
        } catch (Exception e) {
            log.debug("未找到配置 (appId={}): {}", appId, e.getMessage());
            return null;
        }
    }

    /**
     * 保存配置
     */
    public void save(AppLogConfig config) {
        String sql = """
            INSERT INTO app_log_config (app_id, project_path, dsl_query, pull_interval_minutes, enabled)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(app_id) DO UPDATE SET
                project_path = excluded.project_path,
                dsl_query = excluded.dsl_query,
                pull_interval_minutes = excluded.pull_interval_minutes,
                enabled = excluded.enabled,
                updated_at = strftime('%s','now')
            """;
        jdbcTemplate.update(sql,
            config.getAppId(),
            config.getProjectPath(),
            config.getDslQuery(),
            config.getPullIntervalMinutes(),
            config.getEnabled() != null && config.getEnabled() ? 1 : 0
        );
    }

    /**
     * 更新上次拉取时间
     */
    public void updateLastPullAt(String appId) {
        String sql = "UPDATE app_log_config SET last_pull_at = strftime('%s','now') WHERE app_id = ?";
        jdbcTemplate.update(sql, appId);
    }

    /**
     * 行映射器
     */
    private static class AppLogConfigRowMapper implements RowMapper<AppLogConfig> {
        @Override
        public AppLogConfig mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return AppLogConfig.builder()
                .id(rs.getLong("id"))
                .appId(rs.getString("app_id"))
                .projectPath(rs.getString("project_path"))
                .dslQuery(rs.getString("dsl_query"))
                .pullIntervalMinutes(rs.getInt("pull_interval_minutes"))
                .enabled(rs.getInt("enabled") == 1)
                .lastPullAt(rs.getObject("last_pull_at") != null ? rs.getLong("last_pull_at") : null)
                .build();
        }
    }
}