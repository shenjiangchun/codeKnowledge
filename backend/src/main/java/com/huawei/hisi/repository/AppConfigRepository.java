package com.huawei.hisi.repository;

import com.huawei.hisi.model.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 应用配置数据访问层 (SQLite)
 * Table created by SQLiteSchemaInitializer.
 */
@Repository
public class AppConfigRepository {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public AppConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<AppConfig> rowMapper = (rs, rowNum) -> {
        AppConfig config = new AppConfig();
        config.setKey(rs.getString("key"));
        config.setValue(rs.getString("value"));
        config.setUpdatedAt(rs.getTimestamp("updated_at") != null ?
            rs.getTimestamp("updated_at").toLocalDateTime() : null);
        return config;
    };

    public Optional<AppConfig> findByKey(String key) {
        String sql = "SELECT key, value, updated_at FROM app_config WHERE key = ?";
        try {
            AppConfig config = jdbcTemplate.queryForObject(sql, rowMapper, key);
            return Optional.ofNullable(config);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public int upsert(String key, String value) {
        String sql = """
            INSERT INTO app_config (key, value, updated_at)
            VALUES (?, ?, strftime('%s','now'))
            ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = strftime('%s','now')
            """;
        return jdbcTemplate.update(sql, key, value);
    }

    public int update(String key, String value) {
        String sql = "UPDATE app_config SET value = ?, updated_at = strftime('%s','now') WHERE key = ?";
        return jdbcTemplate.update(sql, value, key);
    }
}
