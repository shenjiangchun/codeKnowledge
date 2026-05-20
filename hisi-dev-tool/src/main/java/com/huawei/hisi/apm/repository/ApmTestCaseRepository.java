package com.huawei.hisi.apm.repository;

import com.huawei.hisi.apm.model.ApmTestCase;
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
 * JdbcTemplate-based repository for APM test cases stored in SQLite.
 */
@Repository
@RequiredArgsConstructor
public class ApmTestCaseRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ApmTestCase> ROW_MAPPER = (rs, rowNum) ->
        ApmTestCase.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .projectPath(rs.getString("project_path"))
            .entryNodeId(rs.getString("entry_node_id"))
            .method(rs.getString("method"))
            .url(rs.getString("url"))
            .headers(rs.getString("headers"))
            .params(rs.getString("params"))
            .body(rs.getString("body"))
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
            .updatedAt(rs.getObject("updated_at") != null ? rs.getLong("updated_at") : null)
            .build();

    /**
     * Insert a new test case and return it with the generated ID.
     */
    public ApmTestCase insert(ApmTestCase testCase) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO apm_test_case (name, project_path, entry_node_id, method, url, headers, params, body) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, testCase.getName());
            ps.setString(2, testCase.getProjectPath());
            ps.setString(3, testCase.getEntryNodeId());
            ps.setString(4, testCase.getMethod());
            ps.setString(5, testCase.getUrl());
            ps.setString(6, testCase.getHeaders());
            ps.setString(7, testCase.getParams());
            ps.setString(8, testCase.getBody());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            testCase.setId(key.longValue());
        }
        return testCase;
    }

    /**
     * Update an existing test case by ID.
     */
    public int update(ApmTestCase testCase) {
        return jdbcTemplate.update(
            "UPDATE apm_test_case SET name = ?, entry_node_id = ?, method = ?, url = ?, " +
            "headers = ?, params = ?, body = ?, updated_at = strftime('%s','now') WHERE id = ?",
            testCase.getName(), testCase.getEntryNodeId(), testCase.getMethod(), testCase.getUrl(),
            testCase.getHeaders(), testCase.getParams(), testCase.getBody(), testCase.getId()
        );
    }

    /**
     * Find a test case by its ID.
     */
    public Optional<ApmTestCase> findById(Long id) {
        List<ApmTestCase> results = jdbcTemplate.query(
            "SELECT * FROM apm_test_case WHERE id = ?", ROW_MAPPER, id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find all test cases for a given project, ordered by most recently updated.
     */
    public List<ApmTestCase> findByProjectPath(String projectPath) {
        return jdbcTemplate.query(
            "SELECT * FROM apm_test_case WHERE project_path = ? ORDER BY updated_at DESC",
            ROW_MAPPER, projectPath
        );
    }

    /**
     * Delete a test case by ID. Returns the number of rows affected.
     */
    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM apm_test_case WHERE id = ?", id);
    }

    /**
     * Count test cases for a project.
     */
    public int countByProjectPath(String projectPath) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM apm_test_case WHERE project_path = ?",
            Integer.class, projectPath
        );
        return count != null ? count : 0;
    }
}
