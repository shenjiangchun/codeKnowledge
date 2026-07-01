package com.huawei.hisi.fixengine.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Git operations via {@link ProcessBuilder}.
 */
@Slf4j
@Component
public class GitExecutor {

    private static final long DEFAULT_TIMEOUT_MINUTES = 5;

    /**
     * Create a git worktree at {@code worktreePath} on {@code branchName},
     * branching from {@code targetBranch}.
     */
    public void createWorktree(String repoPath, String worktreePath,
                                String branchName, String targetBranch) {
        log.info("[GitExecutor] creating worktree branch={} from {} at {}",
                branchName, targetBranch, worktreePath);

        // delete stale branch if exists
        runQuietly(repoPath, "git", "branch", "-D", branchName);

        exec(repoPath,
                "git", "worktree", "add", "-b", branchName, worktreePath, targetBranch);

        log.info("[GitExecutor] worktree created at {}", worktreePath);
    }

    /**
     * Stage all changes and commit with the given message.
     */
    public void commitAll(String worktreePath, String message) {
        log.info("[GitExecutor] committing in {}", worktreePath);
        exec(worktreePath, "git", "add", "-A");
        exec(worktreePath, "git", "commit", "-m", message, "--allow-empty");
        log.info("[GitExecutor] commit done");
    }

    /**
     * @return the current HEAD SHA, or null on error
     */
    public String revParseHead(String worktreePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(new File(worktreePath));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String sha;
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                sha = reader.readLine();
            }
            proc.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            return (proc.exitValue() == 0 && sha != null) ? sha.trim() : null;
        } catch (Exception e) {
            log.error("[GitExecutor] rev-parse failed: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------

    private void exec(String cwd, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = proc.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                throw new RuntimeException("Git command timed out: " + String.join(" ", cmd));
            }
            if (proc.exitValue() != 0) {
                log.error("[GitExecutor] cmd failed exit={} output={}", proc.exitValue(), output);
                throw new RuntimeException("Git command failed (exit " + proc.exitValue() + "): " + output);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Git command error: " + e.getMessage(), e);
        }
    }

    private void runQuietly(String cwd, String... cmd) {
        try {
            exec(cwd, cmd);
        } catch (Exception e) {
            log.debug("[GitExecutor] ignored error from {}: {}", String.join(" ", cmd), e.getMessage());
        }
    }
}
