package com.huawei.hisi.project.remote.repository;

import com.huawei.hisi.project.remote.model.RemoteProject;
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
            .lastSyncAt(rs.getObject("last_sync_at") != null ? rs.getLong("last_sync_at") : null)
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
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
                "INSERT INTO remote_project (name, git_url, username, encrypted_password, branch, local_path, clone_status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, p.getName());
            ps.setString(2, p.getGitUrl());
            ps.setString(3, p.getUsername());
            ps.setString(4, p.getEncryptedPassword());
            ps.setString(5, p.getBranch());
            ps.setString(6, p.getLocalPath());
            ps.setString(7, p.getCloneStatus());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1;
    }

    public int update(RemoteProject p) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET name = ?, git_url = ?, username = ?, encrypted_password = ?, " +
            "branch = ?, local_path = ?, clone_status = ? WHERE id = ?",
            p.getName(), p.getGitUrl(), p.getUsername(), p.getEncryptedPassword(),
            p.getBranch(), p.getLocalPath(), p.getCloneStatus(), p.getId()
        );
    }

    public int deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM remote_project WHERE id = ?", id);
    }

    public int updateCloneStatus(long id, String status) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET clone_status = ? WHERE id = ?", status, id
        );
    }

    public int updateLastSyncAt(long id, long epochSeconds) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET last_sync_at = ? WHERE id = ?", epochSeconds, id
        );
    }
}
