package com.huawei.hisi.project.remote.repository;

import com.huawei.hisi.project.remote.model.RemoteProject;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RemoteProjectRepository {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrateSchema() {
        try {
            jdbcTemplate.queryForRowSet("SELECT auth_type FROM remote_project LIMIT 1");
        } catch (Exception e) {
            jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN auth_type VARCHAR(20) DEFAULT 'PASSWORD'");
            jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN ssh_key_path VARCHAR(255)");
            jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN encrypted_token VARCHAR(500)");
            log.info("[Migration] Added auth_type, ssh_key_path, encrypted_token columns to remote_project");
        }
        // Add group_id column for project grouping
        try {
            jdbcTemplate.queryForRowSet("SELECT group_id FROM remote_project LIMIT 1");
        } catch (Exception e) {
            jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN group_id INTEGER");
            log.info("[Migration] Added group_id column to remote_project");
        }
    }

    private static final RowMapper<RemoteProject> ROW_MAPPER = (rs, rowNum) ->
        RemoteProject.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .gitUrl(rs.getString("git_url"))
            .username(rs.getString("username"))
            .encryptedPassword(rs.getString("encrypted_password"))
            .branch(rs.getString("branch"))
            .localPath(rs.getString("local_path"))
            .cloneStatus(rs.getString("clone_status"))
            .cloneError(rs.getString("clone_error"))
            .lastSyncAt(rs.getObject("last_sync_at") != null ? rs.getLong("last_sync_at") : null)
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
            .authType(rs.getString("auth_type") != null ? rs.getString("auth_type") : "PASSWORD")
            .sshKeyPath(rs.getString("ssh_key_path"))
            .encryptedToken(rs.getString("encrypted_token"))
            .groupId(rs.getObject("group_id") != null ? rs.getLong("group_id") : null)
            .build();

    public List<RemoteProject> findAll() {
        return jdbcTemplate.query("SELECT * FROM remote_project ORDER BY id", ROW_MAPPER);
    }

    public Optional<RemoteProject> findById(long id) {
        List<RemoteProject> results = jdbcTemplate.query(
            "SELECT * FROM remote_project WHERE id = ?", ROW_MAPPER, id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public long insert(RemoteProject p) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO remote_project (name, git_url, username, encrypted_password, branch, local_path, clone_status, auth_type, ssh_key_path, encrypted_token, group_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, p.getName());
            ps.setString(2, p.getGitUrl());
            ps.setString(3, p.getUsername());
            ps.setString(4, p.getEncryptedPassword());
            ps.setString(5, p.getBranch());
            ps.setString(6, p.getLocalPath());
            ps.setString(7, p.getCloneStatus());
            ps.setString(8, p.getAuthType());
            ps.setString(9, p.getSshKeyPath());
            ps.setString(10, p.getEncryptedToken());
            ps.setObject(11, p.getGroupId());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1;
    }

    public int update(RemoteProject p) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET name = ?, git_url = ?, username = ?, encrypted_password = ?, " +
            "branch = ?, local_path = ?, clone_status = ?, auth_type = ?, ssh_key_path = ?, encrypted_token = ?, group_id = ? WHERE id = ?",
            p.getName(), p.getGitUrl(), p.getUsername(), p.getEncryptedPassword(),
            p.getBranch(), p.getLocalPath(), p.getCloneStatus(),
            p.getAuthType(), p.getSshKeyPath(), p.getEncryptedToken(), p.getGroupId(), p.getId()
        );
    }

    public int deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM remote_project WHERE id = ?", id);
    }

    public int updateCloneStatus(long id, String status) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET clone_status = ?, clone_error = NULL WHERE id = ?", status, id
        );
    }

    public int updateCloneError(long id, String error) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET clone_status = 'FAILED', clone_error = ? WHERE id = ?", error, id
        );
    }

    public int updateLastSyncAt(long id, long epochSeconds) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET last_sync_at = ? WHERE id = ?", epochSeconds, id
        );
    }
}
