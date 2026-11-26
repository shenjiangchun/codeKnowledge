package com.huawei.hisi.workbench.repository;

import com.huawei.hisi.workbench.model.WorkbenchItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工作台的待办与运行状态持久化（JdbcTemplate，仿 {@code ArchDiagnosisSnapshotRepository}）。
 */
@Repository
@RequiredArgsConstructor
public class WorkbenchRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<WorkbenchItem> ROW_MAPPER = (rs, rowNum) ->
        WorkbenchItem.builder()
            .id(rs.getLong("id"))
            .itemKey(rs.getString("item_key"))
            .source(rs.getString("source"))
            .projectPath(rs.getString("project_path"))
            .title(rs.getString("title"))
            .location(rs.getString("location"))
            .severity(rs.getString("severity"))
            .status(rs.getString("status"))
            .confidence(rs.getString("confidence"))
            .chainStatus(rs.getString("chain_status"))
            .why(rs.getString("why"))
            .suggestion(rs.getString("suggestion"))
            .evidenceJson(rs.getString("evidence_json"))
            .sourceRefJson(rs.getString("source_ref_json"))
            .occurrenceCount(rs.getObject("occurrence_count") != null ? rs.getInt("occurrence_count") : null)
            .firstSeenAt(rs.getObject("first_seen_at") != null ? rs.getLong("first_seen_at") : null)
            .lastSeenAt(rs.getObject("last_seen_at") != null ? rs.getLong("last_seen_at") : null)
            .currentRunId(rs.getObject("current_run_id") != null ? rs.getLong("current_run_id") : null)
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
            .updatedAt(rs.getObject("updated_at") != null ? rs.getLong("updated_at") : null)
            .build();

    /**
     * 按键 upsert，供聚合同步使用：只刷新内容字段，不动 status / current_run_id
     * （IGNORED / DONE / RUNNING / WAITING 这些用户与修复流程决定的状态保持不变）。
     * 改状态请用 {@link #updateStatus}。
     */
    public void upsertItem(WorkbenchItem item) {
        jdbcTemplate.update("""
            INSERT INTO workbench_item (item_key, source, project_path, title, location, severity, status,
                confidence, chain_status, why, suggestion, evidence_json, source_ref_json,
                occurrence_count, first_seen_at, last_seen_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%s','now'))
            ON CONFLICT(item_key) DO UPDATE SET
                title = excluded.title,
                location = excluded.location,
                severity = excluded.severity,
                confidence = excluded.confidence,
                chain_status = excluded.chain_status,
                why = excluded.why,
                suggestion = excluded.suggestion,
                evidence_json = excluded.evidence_json,
                source_ref_json = excluded.source_ref_json,
                occurrence_count = excluded.occurrence_count,
                first_seen_at = CASE
                    WHEN workbench_item.first_seen_at IS NULL OR workbench_item.first_seen_at > excluded.first_seen_at
                    THEN excluded.first_seen_at ELSE workbench_item.first_seen_at END,
                last_seen_at = CASE
                    WHEN excluded.last_seen_at > workbench_item.last_seen_at
                    THEN excluded.last_seen_at ELSE workbench_item.last_seen_at END,
                updated_at = strftime('%s','now')
            """,
            item.getItemKey(), item.getSource(), item.getProjectPath(), item.getTitle(), item.getLocation(),
            item.getSeverity(), item.getStatus(), item.getConfidence(), item.getChainStatus(), item.getWhy(),
            item.getSuggestion(), item.getEvidenceJson(), item.getSourceRefJson(), item.getOccurrenceCount(),
            item.getFirstSeenAt(), item.getLastSeenAt());
    }

    /**
     * 更新待办的状态与当前运行。修复流程的每一步（发起、等待确认、结束）和「忽略 / 恢复」都走这里：
     * {@link #upsertItem} 的冲突分支只刷新内容字段、不动 status——以前状态改动走的也是它，结果一处都没落库，
     * 待办永远显示「待处理」。
     */
    public void updateStatus(long id, String status, Long currentRunId) {
        jdbcTemplate.update("""
            UPDATE workbench_item
            SET status = ?, current_run_id = ?, updated_at = strftime('%s','now')
            WHERE id = ?
            """, status, currentRunId, id);
    }

    public WorkbenchItem findByKey(String itemKey) {
        List<WorkbenchItem> rows = jdbcTemplate.query(
            "SELECT * FROM workbench_item WHERE item_key = ?", ROW_MAPPER, itemKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public WorkbenchItem findById(long id) {
        List<WorkbenchItem> rows = jdbcTemplate.query(
            "SELECT * FROM workbench_item WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WorkbenchItem> listItems() {
        return jdbcTemplate.query("SELECT * FROM workbench_item ORDER BY id", ROW_MAPPER);
    }

    /** 每项每日出现次数：同步时把当天计为 occurrence_count（与来源报告一致）。 */
    public void upsertDay(String itemKey, String day, int count) {
        jdbcTemplate.update("""
            INSERT INTO workbench_item_day (item_key, day, count) VALUES (?, ?, ?)
            ON CONFLICT(item_key, day) DO UPDATE SET count = excluded.count
            """, itemKey, day, count);
    }

    /** 指定若干天内每项的出现次数（天 → 次数；没有记录的天不出现）。 */
    public java.util.Map<String, Integer> listDayCounts(String itemKey, List<String> days) {
        StringBuilder sql = new StringBuilder(
            "SELECT day, count FROM workbench_item_day WHERE item_key = ? AND day IN (");
        sql.append(String.join(",", java.util.Collections.nCopies(days.size(), "?")));
        sql.append(")");
        Object[] params = new Object[days.size() + 1];
        params[0] = itemKey;
        for (int i = 0; i < days.size(); i++) {
            params[i + 1] = days.get(i);
        }
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            out.put(rs.getString("day"), rs.getInt("count"));
        }, params);
        return out;
    }
}
