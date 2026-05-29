package com.huawei.hisi.knowledgegraph.migration;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrates legacy KG task commit info from GenerationTask (SQLite)
 * into GenerationCheckpoint nodes (Neo4j).
 *
 * <p>Idempotent — uses MERGE (upsert) via the checkpoint repository
 * and skips projects that already have a checkpoint.
 * Called manually or on startup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckpointMigrationService {

    private static final Pattern FULL_HASH_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHORT_HASH_PATTERN = Pattern.compile("[0-9a-f]{7,40}");

    private final GenerationTaskRepository taskRepository;
    private final Neo4jGenerationCheckpointRepository checkpointRepository;

    /**
     * Migrate legacy KG task commit info to GenerationCheckpoint nodes.
     *
     * @return number of checkpoints created
     */
    public int migrate() {
        List<GenerationTask> kgTasks = taskRepository.findByTaskType("KG");
        if (kgTasks == null || kgTasks.isEmpty()) {
            log.info("No legacy KG tasks found for checkpoint migration");
            return 0;
        }

        int migrated = 0;
        for (GenerationTask task : kgTasks) {
            String commitHash = extractCommitHash(task.getErrorMessage());
            if (commitHash == null || commitHash.isBlank()) {
                continue;
            }

            String projectPath = task.getProjectPath();
            if (projectPath == null || projectPath.isBlank()) {
                continue;
            }

            // Skip if checkpoint already exists for this project
            if (checkpointRepository.findByProjectPath(projectPath).isPresent()) {
                continue;
            }

            checkpointRepository.upsertCheckpoint(projectPath, commitHash, "unknown");
            migrated++;
            log.info("Migrated checkpoint: project={}, commit={}", projectPath, commitHash);
        }

        log.info("Checkpoint migration complete: {} checkpoints created", migrated);
        return migrated;
    }

    /**
     * Extract commit hash from errorMessage. Handles formats:
     * <ul>
     *   <li>"commit:abc123def..." — prefix format</li>
     *   <li>raw 40-char hex string</li>
     *   <li>embedded hash within a longer message</li>
     *   <li>null/empty — returns null</li>
     * </ul>
     */
    static String extractCommitHash(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }

        // Try "commit:" prefix
        if (errorMessage.toLowerCase().startsWith("commit:")) {
            String hash = errorMessage.substring(7).trim();
            if (isValidCommitHash(hash)) {
                return hash;
            }
        }

        // Try raw hash (entire string is a hash)
        String trimmed = errorMessage.trim();
        if (isValidCommitHash(trimmed)) {
            return trimmed;
        }

        // Try to find a full 40-char hash embedded in the message
        Matcher fullMatcher = FULL_HASH_PATTERN.matcher(errorMessage);
        if (fullMatcher.find()) {
            return fullMatcher.group();
        }

        // Also accept short hashes (7+ hex chars)
        Matcher shortMatcher = SHORT_HASH_PATTERN.matcher(errorMessage);
        if (shortMatcher.find()) {
            return shortMatcher.group();
        }

        return null;
    }

    private static boolean isValidCommitHash(String s) {
        return s != null && s.length() >= 7 && s.length() <= 40 && s.matches("[0-9a-f]+");
    }
}
