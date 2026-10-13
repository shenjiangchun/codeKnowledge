package com.huawei.hisi.knowledgegraph.binding.repository;

import com.huawei.hisi.knowledgegraph.binding.model.ProjectBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 前后端绑定关系持久化（JdbcTemplate，仿 {@code GlossaryTermRepository}）。
 */
@Repository
@RequiredArgsConstructor
public class ProjectBindingRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ProjectBinding> ROW_MAPPER = (rs, rowNum) ->
        ProjectBinding.builder()
            .id(rs.getLong("id"))
            .backendPath(rs.getString("backend_path"))
            .frontendPath(rs.getString("frontend_path"))
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
            .updatedAt(rs.getObject("updated_at") != null ? rs.getLong("updated_at") : null)
            .build();

    public ProjectBinding insert(ProjectBinding binding) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO kg_project_binding (backend_path, frontend_path) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, binding.getBackendPath());
            ps.setString(2, binding.getFrontendPath());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            binding.setId(key.longValue());
        }
        return binding;
    }

    public List<ProjectBinding> findAll() {
        return jdbcTemplate.query(
            "SELECT * FROM kg_project_binding ORDER BY id", ROW_MAPPER
        );
    }

    public List<ProjectBinding> findByBackendPath(String backendPath) {
        return jdbcTemplate.query(
            "SELECT * FROM kg_project_binding WHERE backend_path = ? ORDER BY id",
            ROW_MAPPER, backendPath
        );
    }

    public List<ProjectBinding> findByFrontendPath(String frontendPath) {
        return jdbcTemplate.query(
            "SELECT * FROM kg_project_binding WHERE frontend_path = ? ORDER BY id",
            ROW_MAPPER, frontendPath
        );
    }

    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM kg_project_binding WHERE id = ?", id);
    }
}
