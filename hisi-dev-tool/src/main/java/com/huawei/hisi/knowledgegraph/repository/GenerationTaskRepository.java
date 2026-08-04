package com.huawei.hisi.knowledgegraph.repository;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Unified generation task repository (SQLite).
 * Replaces KnowledgeGraphTaskRepository, VectorGenerationTaskRepository,
 * and KgGenerationLogRepository.
 *
 * Table created by SQLiteSchemaInitializer as "generation_task".
 */
@Repository
public class GenerationTaskRepository {

    private static final Logger LOG = LoggerFactory.getLogger(GenerationTaskRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public GenerationTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<GenerationTask> ROW_MAPPER = (rs, rowNum) -> GenerationTask.builder()
        .id(rs.getLong("id"))
        .taskType(rs.getString("task_type"))
        .projectPath(rs.getString("project_path"))
        .status(rs.getString("status"))
        .progress(rs.getObject("progress") != null ? rs.getInt("progress") : 0)
        .totalCount(rs.getObject("total_count") != null ? rs.getInt("total_count") : 0)
        .successCount(rs.getObject("success_count") != null ? rs.getInt("success_count") : 0)
        .failCount(rs.getObject("fail_count") != null ? rs.getInt("fail_count") : 0)
        .errorMessage(rs.getString("error_message"))
        .startedAt(rs.getObject("started_at") != null ? rs.getLong("started_at") : null)
        .finishedAt(rs.getObject("finished_at") != null ? rs.getLong("finished_at") : null)
        .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
        .build();

    // ==================== Insert ====================

    /**
     * Insert a new task. Returns the task with its generated id.
     */
    public GenerationTask insert(GenerationTask task) {
        String sql = """
            INSERT INTO generation_task (task_type, project_path, status, progress, total_count,
                success_count, fail_count, error_message, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
            task.getTaskType(),
            task.getProjectPath(),
            task.getStatus() != null ? task.getStatus() : "PENDING",
            task.getProgress() != null ? task.getProgress() : 0,
            task.getTotalCount() != null ? task.getTotalCount() : 0,
            task.getSuccessCount() != null ? task.getSuccessCount() : 0,
            task.getFailCount() != null ? task.getFailCount() : 0,
            task.getErrorMessage(),
            task.getStartedAt(),
            task.getFinishedAt()
        );

        // Retrieve the generated task by querying the latest for this project and type
        Optional<GenerationTask> insertedTask = findLatestByProjectPathAndType(task.getProjectPath(), task.getTaskType());
        if (insertedTask.isPresent()) {
            return insertedTask.get();
        }
        return task;
    }

    // ==================== Update ====================

    /**
     * Update task status and timestamps.
     */
    public int updateStatus(Long id, String status) {
        LOG.info("[updateStatus] Called with id={}, status={}", id, status);
        String sql = "UPDATE generation_task SET status = ? WHERE id = ?";
        int rows = jdbcTemplate.update(sql, status, id);
        LOG.info("[updateStatus] Updated {} rows for id={}, new status={}", rows, id, status);
        return rows;
    }

    /**
     * Mark task as started.
     */
    public int updateStarted(Long id) {
        LOG.info("[updateStarted] Called with id={}", id);
        String sql = "UPDATE generation_task SET status = 'RUNNING', started_at = strftime('%s','now') WHERE id = ?";
        int rows = jdbcTemplate.update(sql, id);
        LOG.info("[updateStarted] Updated {} rows for id={}", rows, id);
        return rows;
    }

    /**
     * Mark task as completed.
     */
    public int updateCompleted(Long id, int progress, int totalCount, int successCount, int failCount) {
        LOG.info("[updateCompleted] Called with id={}, progress={}, totalCount={}, successCount={}, failCount={}",
            id, progress, totalCount, successCount, failCount);
        // 全部失败时标记为 FAILED，而非 COMPLETED
        String status = (totalCount > 0 && successCount == 0 && failCount > 0) ? "FAILED" : "COMPLETED";
        String sql = """
            UPDATE generation_task SET status = ?,
                progress = ?, total_count = ?, success_count = ?, fail_count = ?,
                finished_at = strftime('%s','now')
            WHERE id = ?
            """;
        int rows = jdbcTemplate.update(sql, status, progress, totalCount, successCount, failCount, id);
        LOG.info("[updateCompleted] Updated {} rows for id={}, final progress={}/{}, success={}, fail={}, status={}",
            rows, id, progress, totalCount, successCount, failCount, status);
        return rows;
    }

    /**
     * Mark task as failed.
     */
    public int updateFailed(Long id, String errorMessage) {
        LOG.info("[updateFailed] Called with id={}, errorMessage={}", id, errorMessage);
        String sql = """
            UPDATE generation_task SET status = 'FAILED',
                error_message = ?, finished_at = strftime('%s','now')
            WHERE id = ?
            """;
        int rows = jdbcTemplate.update(sql, errorMessage, id);
        LOG.info("[updateFailed] Updated {} rows for id={}", rows, id);
        return rows;
    }

    /**
     * Update progress counters (for in-progress tasks).
     */
    public int updateProgress(Long id, int progress, int successCount, int failCount) {
        LOG.info("[updateProgress] Called with id={}, progress={}, successCount={}, failCount={}",
            id, progress, successCount, failCount);
        String sql = "UPDATE generation_task SET progress = ?, success_count = ?, fail_count = ? WHERE id = ?";
        int rows = jdbcTemplate.update(sql, progress, successCount, failCount, id);
        LOG.info("[updateProgress] Updated {} rows for id={}, current progress={}/{}, success={}, fail={}",
            rows, id, progress, progress, successCount, failCount);
        return rows;
    }

    /**
     * Full update of a task.
     */
    public GenerationTask save(GenerationTask task) {
        LOG.info("[save] Called with task={}", task);
        if (task.getId() == null) {
            LOG.info("[save] Inserting new task");
            return insert(task);
        }
        String sql = """
            UPDATE generation_task SET task_type = ?, project_path = ?, status = ?,
                progress = ?, total_count = ?, success_count = ?, fail_count = ?,
                error_message = ?, started_at = ?, finished_at = ?
            WHERE id = ?
            """;
        int rows = jdbcTemplate.update(sql,
            task.getTaskType(),
            task.getProjectPath(),
            task.getStatus(),
            task.getProgress(),
            task.getTotalCount(),
            task.getSuccessCount(),
            task.getFailCount(),
            task.getErrorMessage(),
            task.getStartedAt(),
            task.getFinishedAt(),
            task.getId()
        );
        LOG.info("[save] Updated {} rows for task id={}", rows, task.getId());
        return findById(task.getId()).orElse(task);
    }

    // ==================== Find ====================

    /**
     * Find by ID.
     */
    public Optional<GenerationTask> findById(Long id) {
        String sql = "SELECT * FROM generation_task WHERE id = ?";
        List<GenerationTask> results = jdbcTemplate.query(sql, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find the latest task for a given project path and task type.
     */
    public Optional<GenerationTask> findLatestByProjectPathAndType(String projectPath, String taskType) {
        String sql = "SELECT * FROM generation_task WHERE project_path = ? AND task_type = ? ORDER BY id DESC LIMIT 1";
        List<GenerationTask> results = jdbcTemplate.query(sql, ROW_MAPPER, projectPath, taskType);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find the latest task for a given project path (any type).
     */
    public Optional<GenerationTask> findLatestByProjectPath(String projectPath) {
        String sql = "SELECT * FROM generation_task WHERE project_path = ? ORDER BY id DESC LIMIT 1";
        List<GenerationTask> results = jdbcTemplate.query(sql, ROW_MAPPER, projectPath);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find latest tasks for multiple project paths (one per path, for a given type).
     */
    public List<GenerationTask> findLatestByProjectPaths(List<String> projectPaths, String taskType) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", projectPaths.stream().map(s -> "?").toArray(String[]::new));
        String sql = """
            SELECT t.* FROM generation_task t
            INNER JOIN (
                SELECT project_path, MAX(id) as max_id FROM generation_task
                WHERE project_path IN (%s) AND task_type = ?
                GROUP BY project_path
            ) latest ON t.id = latest.max_id
            """.formatted(placeholders);

        Object[] params = new Object[projectPaths.size() + 1];
        for (int i = 0; i < projectPaths.size(); i++) {
            params[i] = projectPaths.get(i);
        }
        params[projectPaths.size()] = taskType;

        try {
            return jdbcTemplate.query(sql, ROW_MAPPER, params);
        } catch (Exception e) {
            LOG.warn("findLatestByProjectPaths failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Find all running or pending tasks (optionally filtered by type).
     */
    public List<GenerationTask> findRunningOrPending(String taskType) {
        if (taskType != null) {
            String sql = "SELECT * FROM generation_task WHERE status IN ('PENDING', 'RUNNING') AND task_type = ? ORDER BY created_at DESC";
            return jdbcTemplate.query(sql, ROW_MAPPER, taskType);
        }
        String sql = "SELECT * FROM generation_task WHERE status IN ('PENDING', 'RUNNING') ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    /**
     * Find tasks by project path and type, ordered by creation time descending.
     */
    public List<GenerationTask> findByProjectPathAndType(String projectPath, String taskType) {
        String sql = "SELECT * FROM generation_task WHERE project_path = ? AND task_type = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, projectPath, taskType);
    }

    /**
     * Find all tasks by task type.
     */
    public List<GenerationTask> findByTaskType(String taskType) {
        String sql = "SELECT * FROM generation_task WHERE task_type = ? ORDER BY id DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, taskType);
    }

    // ==================== Delete ====================

    /**
     * Delete all tasks for a given project path and type.
     */
    public void deleteByProjectPathAndType(String projectPath, String taskType) {
        String sql = "DELETE FROM generation_task WHERE project_path = ? AND task_type = ?";
        jdbcTemplate.update(sql, projectPath, taskType);
    }

    /**
     * Delete all tasks for a given project path (any type).
     */
    public void deleteByProjectPath(String projectPath) {
        String sql = "DELETE FROM generation_task WHERE project_path = ?";
        jdbcTemplate.update(sql, projectPath);
    }
}
