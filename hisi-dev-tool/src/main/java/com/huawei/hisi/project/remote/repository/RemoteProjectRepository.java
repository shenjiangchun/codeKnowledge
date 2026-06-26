package com.huawei.hisi.project.remote.repository;

import com.huawei.hisi.config.DataSourceConfig;
import com.huawei.hisi.project.remote.model.RemoteProject;
import com.huawei.hisi.utils.PathUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.nio.file.Paths;
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
        // Add full_path column for immutable clone path
        try {
            jdbcTemplate.queryForRowSet("SELECT full_path FROM remote_project LIMIT 1");
        } catch (Exception e) {
            jdbcTemplate.execute("ALTER TABLE remote_project ADD COLUMN full_path VARCHAR(500)");
            log.info("[Migration] Added full_path column to remote_project");
        }
        // Backfill fullPath for existing CLONED projects
        // Use actual physical path existence check instead of assuming current PROJECT_DIR
        try {
            List<RemoteProject> clonedWithoutFullPath = jdbcTemplate.query(
                "SELECT * FROM remote_project WHERE clone_status = 'CLONED' AND (full_path IS NULL OR full_path = '')",
                ROW_MAPPER
            );
            if (!clonedWithoutFullPath.isEmpty()) {
                for (RemoteProject p : clonedWithoutFullPath) {
                    // Try multiple possible base directories to find actual clone location
                    String foundPath = findActualClonePath(p.getLocalPath());
                    if (foundPath != null) {
                        updateFullPath(p.getId(), foundPath);
                        log.info("[Migration] Backfilled fullPath for project {}: {}", p.getName(), foundPath);
                    } else {
                        log.warn("[Migration] Could not find actual clone path for project {}. " +
                            "Use KG path diagnosis tool to fix.", p.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Migration] Failed to backfill fullPath: {}", e.getMessage());
        }
    }

    /**
     * Find actual clone path by checking physical directory existence.
     * Try: current PROJECT_DIR, then user home, then common historical paths.
     */
    private String findActualClonePath(String localPath) {
        java.util.List<String> possibleBaseDirs = new java.util.ArrayList<>();

        // 1. Current PROJECT_DIR (highest priority)
        String currentProjectDir = DataSourceConfig.PROJECT_DIR;
        if (currentProjectDir != null && !currentProjectDir.isBlank()) {
            possibleBaseDirs.add(currentProjectDir);
        }

        // 2. User home fallback
        possibleBaseDirs.add(Paths.get(System.getProperty("user.home"), ".hisi-devtool").toString());

        // 3. Common historical paths (if user changed PROJECT_DIR before)
        // Try common Windows/Linux paths
        String userHome = System.getProperty("user.home");
        possibleBaseDirs.add(userHome + "/codeknowledge");
        possibleBaseDirs.add(userHome + "/hisi-code-analyser");
        possibleBaseDirs.add("D:/hisi-code-analyser/codeKnowledge1");
        possibleBaseDirs.add("D:/codeknowledge");

        for (String baseDir : possibleBaseDirs) {
            java.nio.file.Path candidate = Paths.get(baseDir, "remote-repos", localPath);
            if (java.nio.file.Files.exists(candidate) && java.nio.file.Files.isDirectory(candidate)) {
                // Check if it looks like a git repo (has .git directory or is non-empty)
                if (java.nio.file.Files.exists(candidate.resolve(".git")) ||
                    hasContent(candidate)) {
                    return PathUtils.normalize(candidate.toString());
                }
            }
        }
        return null;
    }

    private boolean hasContent(java.nio.file.Path dir) {
        try {
            return java.nio.file.Files.list(dir).findAny().isPresent();
        } catch (java.io.IOException e) {
            return false;
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
            .fullPath(rs.getString("full_path"))
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
                "INSERT INTO remote_project (name, git_url, username, encrypted_password, branch, local_path, clone_status, auth_type, ssh_key_path, encrypted_token, group_id, full_path) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            ps.setString(12, p.getFullPath());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1;
    }

    public int update(RemoteProject p) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET name = ?, git_url = ?, username = ?, encrypted_password = ?, " +
            "branch = ?, local_path = ?, clone_status = ?, auth_type = ?, ssh_key_path = ?, encrypted_token = ?, group_id = ?, full_path = ? WHERE id = ?",
            p.getName(), p.getGitUrl(), p.getUsername(), p.getEncryptedPassword(),
            p.getBranch(), p.getLocalPath(), p.getCloneStatus(),
            p.getAuthType(), p.getSshKeyPath(), p.getEncryptedToken(), p.getGroupId(), p.getFullPath(), p.getId()
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

    public int updateFullPath(long id, String fullPath) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET full_path = ? WHERE id = ?", fullPath, id
        );
    }

    /**
     * 根据项目路径设置 group_id 和 groupName
     */
    public int setGroupIdByPath(String normalizedPath, String groupId, String groupName) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET group_id = ? WHERE full_path = ? OR local_path = ?",
            groupId, normalizedPath, normalizedPath
        );
    }

    /**
     * 根据项目路径清除 group_id
     */
    public int clearGroupIdByPath(String normalizedPath) {
        return jdbcTemplate.update(
            "UPDATE remote_project SET group_id = NULL WHERE full_path = ? OR local_path = ?",
            normalizedPath, normalizedPath
        );
    }
}
