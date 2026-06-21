package com.huawei.hisi.project.namegroup.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.project.namegroup.model.ProjectNameGroup;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目名称分组 Repository
 * Task 74: 按项目名称前缀/模式分组
 */
@Slf4j
@Repository
public class ProjectNameGroupRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectNameGroupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private RowMapper<ProjectNameGroup> createRowMapper() {
        return (rs, rowNum) -> {
            ProjectNameGroup group = new ProjectNameGroup();
            group.setId(rs.getLong("id"));
            group.setGroupName(rs.getString("group_name"));
            group.setGroupPattern(rs.getString("group_pattern"));
            group.setDescription(rs.getString("description"));

            // Parse project names JSON
            try {
                String namesJson = rs.getString("project_names");
                if (namesJson != null && !namesJson.isEmpty()) {
                    group.setProjectNames(objectMapper.readValue(namesJson, List.class));
                }
            } catch (Exception e) {
                log.warn("Failed to parse project_names: {}", e.getMessage());
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
    public List<ProjectNameGroup> findAll() {
        String sql = "SELECT * FROM project_name_group ORDER BY group_name";
        return jdbcTemplate.query(sql, createRowMapper());
    }

    /**
     * 按分组名称查询
     */
    public ProjectNameGroup findByGroupName(String groupName) {
        String sql = "SELECT * FROM project_name_group WHERE group_name = ?";
        List<ProjectNameGroup> results = jdbcTemplate.query(sql, createRowMapper(), groupName);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 按 ID 查询分组
     */
    public ProjectNameGroup findById(Long id) {
        String sql = "SELECT * FROM project_name_group WHERE id = ?";
        List<ProjectNameGroup> results = jdbcTemplate.query(sql, createRowMapper(), id);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 保存分组（upsert by group_name）
     */
    public void save(ProjectNameGroup group) {
        String sql = """
            INSERT INTO project_name_group (group_name, group_pattern, project_names, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, strftime('%s','now'), strftime('%s','now'))
            ON CONFLICT(group_name) DO UPDATE SET
                group_pattern = excluded.group_pattern,
                project_names = excluded.project_names,
                description = excluded.description,
                updated_at = strftime('%s','now')
            """;

        try {
            String namesJson = group.getProjectNames() != null ?
                objectMapper.writeValueAsString(group.getProjectNames()) : "[]";

            jdbcTemplate.update(sql,
                group.getGroupName(),
                group.getGroupPattern(),
                namesJson,
                group.getDescription()
            );
            log.info("Project name group saved: groupName={}", group.getGroupName());
        } catch (Exception e) {
            log.error("Failed to save project name group: {}", e.getMessage());
            throw new RuntimeException("保存分组失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除分组
     */
    public void deleteByGroupName(String groupName) {
        String sql = "DELETE FROM project_name_group WHERE group_name = ?";
        jdbcTemplate.update(sql, groupName);
        log.info("Project name group deleted: groupName={}", groupName);
    }

    /**
     * 检查分组名称是否存在
     */
    public boolean existsByGroupName(String groupName) {
        String sql = "SELECT COUNT(*) FROM project_name_group WHERE group_name = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, groupName);
        return count != null && count > 0;
    }

    /**
     * 查找匹配项目名称的分组（按 pattern 匹配）
     */
    public ProjectNameGroup findMatchingGroup(String projectName) {
        // 使用 LIKE 模式匹配
        String sql = "SELECT * FROM project_name_group WHERE ? LIKE group_pattern ESCAPE '\\' LIMIT 1";
        // 将 pattern 中的 * 转换为 SQL LIKE 的 %
        List<ProjectNameGroup> results = jdbcTemplate.query(sql, createRowMapper(), projectName);
        return results.isEmpty() ? null : results.get(0);
    }
}