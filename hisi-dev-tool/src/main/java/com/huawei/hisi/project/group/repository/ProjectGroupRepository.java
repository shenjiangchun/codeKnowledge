package com.huawei.hisi.project.group.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.project.group.model.ProjectGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 项目分组 Repository
 */
@Slf4j
@Repository
public class ProjectGroupRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectGroupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private RowMapper<ProjectGroup> createRowMapper() {
        return (rs, rowNum) -> {
            ProjectGroup group = new ProjectGroup();
            group.setId(rs.getLong("id"));
            group.setAppId(rs.getString("app_id"));
            group.setAppName(rs.getString("app_name"));
            group.setDescription(rs.getString("description"));

            // Parse project paths JSON
            try {
                String pathsJson = rs.getString("project_paths");
                if (pathsJson != null && !pathsJson.isEmpty()) {
                    group.setProjectPaths(objectMapper.readValue(pathsJson, List.class));
                }
            } catch (Exception e) {
                log.warn("Failed to parse project_paths: {}", e.getMessage());
            }

            // Parse timestamps
            long createdEpoch = rs.getLong("created_at");
            group.setCreatedAt(createdEpoch > 0 ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(createdEpoch), ZoneId.systemDefault()) : null);
            long updatedEpoch = rs.getLong("updated_at");
            group.setUpdatedAt(updatedEpoch > 0 ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(updatedEpoch), ZoneId.systemDefault()) : null);

            return group;
        };
    }

    /**
     * 查询所有分组
     */
    public List<ProjectGroup> findAll() {
        String sql = "SELECT * FROM project_group ORDER BY app_name";
        return jdbcTemplate.query(sql, createRowMapper());
    }

    /**
     * 按 appId 查询分组
     */
    public ProjectGroup findByAppId(String appId) {
        String sql = "SELECT * FROM project_group WHERE app_id = ?";
        List<ProjectGroup> results = jdbcTemplate.query(sql, createRowMapper(), appId);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 按 ID 查询分组
     */
    public ProjectGroup findById(Long id) {
        String sql = "SELECT * FROM project_group WHERE id = ?";
        List<ProjectGroup> results = jdbcTemplate.query(sql, createRowMapper(), id);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 保存分组（upsert by app_id）
     */
    public void save(ProjectGroup group) {
        String sql = """
            INSERT INTO project_group (app_id, app_name, project_paths, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, strftime('%s','now'), strftime('%s','now'))
            ON CONFLICT(app_id) DO UPDATE SET
                app_name = excluded.app_name,
                project_paths = excluded.project_paths,
                description = excluded.description,
                updated_at = strftime('%s','now')
            """;

        try {
            String pathsJson = group.getProjectPaths() != null ?
                objectMapper.writeValueAsString(group.getProjectPaths()) : "[]";

            jdbcTemplate.update(sql,
                group.getAppId(),
                group.getAppName(),
                pathsJson,
                group.getDescription()
            );
            log.info("Project group saved: appId={}", group.getAppId());
        } catch (Exception e) {
            log.error("Failed to save project group: {}", e.getMessage());
            throw new RuntimeException("保存分组失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除分组
     */
    public void deleteByAppId(String appId) {
        String sql = "DELETE FROM project_group WHERE app_id = ?";
        jdbcTemplate.update(sql, appId);
        log.info("Project group deleted: appId={}", appId);
    }

    /**
     * 检查 appId 是否存在
     */
    public boolean existsByAppId(String appId) {
        String sql = "SELECT COUNT(*) FROM project_group WHERE app_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, appId);
        return count != null && count > 0;
    }

    /**
     * 检查项目路径是否属于某个分组
     */
    public ProjectGroup findContainingPath(String projectPath) {
        String sql = "SELECT * FROM project_group WHERE project_paths LIKE ?";
        String pattern = "%" + projectPath + "%";
        List<ProjectGroup> results = jdbcTemplate.query(sql, createRowMapper(), pattern);
        return results.isEmpty() ? null : results.get(0);
    }
}