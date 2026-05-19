package com.huawei.hisi.apm.repository;

import com.huawei.hisi.apm.model.ApmSession;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ApmSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ApmSession> ROW_MAPPER = (rs, rowNum) ->
        ApmSession.builder()
            .id(rs.getString("id"))
            .projectPath(rs.getString("project_path"))
            .serviceName(rs.getString("service_name"))
            .targetPort(rs.getInt("target_port"))
            .status(rs.getString("status"))
            .createdAt(rs.getLong("created_at"))
            .finishedAt(rs.getObject("finished_at") != null ? rs.getLong("finished_at") : null)
            .build();

    public void insert(ApmSession session) {
        jdbcTemplate.update(
            "INSERT INTO apm_session (id, project_path, service_name, target_port, status) VALUES (?, ?, ?, ?, ?)",
            session.getId(), session.getProjectPath(), session.getServiceName(),
            session.getTargetPort(), session.getStatus()
        );
    }

    public void updateStatus(String id, String status) {
        jdbcTemplate.update("UPDATE apm_session SET status = ? WHERE id = ?", status, id);
    }

    public void updateTargetPort(String id, int port) {
        jdbcTemplate.update("UPDATE apm_session SET target_port = ? WHERE id = ?", port, id);
    }

    public void finish(String id, String status) {
        jdbcTemplate.update(
            "UPDATE apm_session SET status = ?, finished_at = strftime('%s','now') WHERE id = ?",
            status, id
        );
    }

    public Optional<ApmSession> findById(String id) {
        List<ApmSession> results = jdbcTemplate.query(
            "SELECT * FROM apm_session WHERE id = ?", ROW_MAPPER, id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<ApmSession> findRecent(int limit) {
        return jdbcTemplate.query(
            "SELECT * FROM apm_session ORDER BY created_at DESC LIMIT ?", ROW_MAPPER, limit
        );
    }

    public Optional<ApmSession> findActiveByProjectPath(String projectPath) {
        List<ApmSession> results = jdbcTemplate.query(
            "SELECT * FROM apm_session WHERE project_path = ? AND status NOT IN ('COMPLETED', 'ERROR') ORDER BY created_at DESC LIMIT 1",
            ROW_MAPPER, projectPath
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
