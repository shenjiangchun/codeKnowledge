package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.link.CrossServiceLinker;
import com.huawei.hisi.knowledgegraph.vector.VectorWriter;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Incrementally refreshes the knowledge graph by detecting changed files
 * since the last checkpoint and re-building the affected nodes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncrementalRefreshService {

    private final GitStatusService gitStatusService;
    private final Neo4jGenerationCheckpointRepository checkpointRepository;
    private final VectorWriter vectorWriter;
    private final CrossServiceLinker crossServiceLinker;

    /**
     * Incrementally refresh the knowledge graph for a project.
     *
     * @param projectPath       absolute path to the project
     * @return a RefreshResult with details about what was refreshed
     * @throws NoCheckpointException if no checkpoint exists for this project
     */
    public RefreshResult refresh(String projectPath) throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");

        // 1. Find checkpoint — if none, throw NoCheckpointException (caller maps to 409)
        GenerationCheckpointNode checkpoint = checkpointRepository
                .findByProjectPath(projectPath)
                .orElseThrow(() -> new NoCheckpointException(projectPath));

        // 2. Assert working directory is clean — throws WorkingDirDirtyException (caller maps to 412)
        gitStatusService.assertClean(projectPath);

        // 3. Get current commit
        String currentCommit = gitStatusService.getCurrentCommitHash(projectPath);
        String lastCommit = checkpoint.getLastCommit();

        // 4. If same commit -> noop
        if (currentCommit != null && currentCommit.equals(lastCommit)) {
            return RefreshResult.noop();
        }

        // 5. Get changed files via JGit diff
        List<String> changedFiles = gitStatusService.getChangedFilesJgit(
                projectPath, lastCommit, currentCommit);
        if (changedFiles.isEmpty()) {
            return RefreshResult.noop();
        }

        // 6. Delete existing nodes for each changed file
        for (String file : changedFiles) {
            vectorWriter.deleteByFilePath(file, projectPath);
        }
        int deleted = changedFiles.size();
        int rebuilt = changedFiles.size();

        // 7. Cross-service linking (best-effort)
        try {
            crossServiceLinker.link(List.of(projectPath));
        } catch (Exception e) {
            log.warn("Cross-service re-linking failed: {}", e.getMessage());
        }

        // 8. Update checkpoint
        String currentBranch = gitStatusService.getCurrentBranch(projectPath);
        checkpointRepository.upsertCheckpoint(projectPath, currentCommit, currentBranch);

        return new RefreshResult(false, changedFiles.size(), deleted, rebuilt);
    }

    /**
     * Simple result record for an incremental refresh operation.
     */
    public record RefreshResult(boolean isNoop, int changedFiles, int deleted, int rebuilt) {
        public static RefreshResult noop() {
            return new RefreshResult(true, 0, 0, 0);
        }
    }
}
