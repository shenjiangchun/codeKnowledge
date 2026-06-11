package com.huawei.hisi.repository;

import com.huawei.hisi.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<User> USER_ROW_MAPPER = (rs, rowNum) ->
            new User(rs.getLong("id"), rs.getString("username"),
                    rs.getString("role"), rs.getLong("created_at"));

    private static final RowMapper<UserWithPassword> USER_WITH_PW_MAPPER = (rs, rowNum) ->
            new UserWithPassword(rs.getLong("id"), rs.getString("username"),
                    rs.getString("password"), rs.getString("role"), rs.getLong("created_at"));

    public record UserWithPassword(Long id, String username, String password, String role, Long createdAt) {}

    public Optional<UserWithPassword> findByUsername(String username) {
        List<UserWithPassword> results = jdbc.query(
                "SELECT id, username, password, role, created_at FROM sys_user WHERE username = ?",
                USER_WITH_PW_MAPPER, username);
        return results.stream().findFirst();
    }

    public User save(String username, String encodedPassword, String role) {
        jdbc.update("INSERT INTO sys_user (username, password, role) VALUES (?, ?, ?)",
                username, encodedPassword, role);
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return new User(id, username, role, null);
    }

    public List<User> findAll() {
        return jdbc.query("SELECT id, username, role, created_at FROM sys_user ORDER BY id", USER_ROW_MAPPER);
    }

    public boolean updateRole(Long id, String role) {
        int rows = jdbc.update("UPDATE sys_user SET role = ?, updated_at = strftime('%s','now') WHERE id = ?",
                role, id);
        return rows > 0;
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ?",
                Integer.class, username);
        return count != null && count > 0;
    }
}
