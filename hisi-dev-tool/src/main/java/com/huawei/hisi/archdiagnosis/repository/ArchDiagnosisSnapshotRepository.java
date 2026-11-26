package com.huawei.hisi.archdiagnosis.repository;

import com.huawei.hisi.archdiagnosis.model.ArchDiagnosisSnapshot;
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
 * 架构诊断快照持久化（JdbcTemplate，仿 {@code ProjectBindingRepository}）。
 */
@Repository
@RequiredArgsConstructor
public class ArchDiagnosisSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ArchDiagnosisSnapshot> ROW_MAPPER = (rs, rowNum) ->
        ArchDiagnosisSnapshot.builder()
            .id(rs.getLong("id"))
            .scope(rs.getString("scope"))
            .healthScore(rs.getObject("health_score") != null ? rs.getInt("health_score") : null)
            .grade(rs.getString("grade"))
            .highCount(rs.getObject("high_count") != null ? rs.getInt("high_count") : null)
            .mediumCount(rs.getObject("medium_count") != null ? rs.getInt("medium_count") : null)
            .lowCount(rs.getObject("low_count") != null ? rs.getInt("low_count") : null)
            .reportJson(rs.getString("report_json"))
            .createdAt(rs.getString("created_at"))
            .build();

    /** 落一条快照；reportJson 存全量报告体 JSON，供历史导出还原。 */
    public ArchDiagnosisSnapshot save(ArchDiagnosisSnapshot snapshot) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO architecture_diagnosis_snapshot " +
                "(scope, health_score, grade, high_count, medium_count, low_count, report_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, snapshot.getScope());
            ps.setObject(2, snapshot.getHealthScore());
            ps.setString(3, snapshot.getGrade());
            ps.setObject(4, snapshot.getHighCount());
            ps.setObject(5, snapshot.getMediumCount());
            ps.setObject(6, snapshot.getLowCount());
            ps.setString(7, snapshot.getReportJson());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            snapshot.setId(key.longValue());
        }
        return snapshot;
    }

    /** 按单项目 scope 查询历史趋势（不含 reportJson，只回总分/严重度计数/时间）。 */
    public List<ArchDiagnosisSnapshot> listTrendByScope(String scope, int limit) {
        return jdbcTemplate.query(
            "SELECT id, scope, health_score, grade, high_count, medium_count, low_count, " +
            "NULL AS report_json, created_at " +
            "FROM architecture_diagnosis_snapshot WHERE scope = ? " +
            "ORDER BY id DESC LIMIT ?",
            ROW_MAPPER, scope, limit
        );
    }

    /** 历史导出：按 id 取全量报告体。 */
    public ArchDiagnosisSnapshot findById(long id) {
        List<ArchDiagnosisSnapshot> rows = jdbcTemplate.query(
            "SELECT * FROM architecture_diagnosis_snapshot WHERE id = ?",
            ROW_MAPPER, id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 取某项目最新一条快照（含全量报告体），供工作台聚合成待办。 */
    public ArchDiagnosisSnapshot findLatestByScope(String scope) {
        List<ArchDiagnosisSnapshot> rows = jdbcTemplate.query(
            "SELECT * FROM architecture_diagnosis_snapshot WHERE scope = ? ORDER BY id DESC LIMIT 1",
            ROW_MAPPER, scope
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 快照覆盖过的所有项目路径（scope 即项目路径）。 */
    public List<String> listScopes() {
        return jdbcTemplate.queryForList(
            "SELECT DISTINCT scope FROM architecture_diagnosis_snapshot ORDER BY scope", String.class);
    }
}