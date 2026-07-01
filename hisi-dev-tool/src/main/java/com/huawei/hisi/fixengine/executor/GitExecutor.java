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
     * Stage specific paths and commit with the given message.
     * 使用选择性 add 而非 git add -A，避免提交无关文件。
     */
    public void commitAll(String worktreePath, String message, String... paths) {
        log.info("[GitExecutor] committing in {}", worktreePath);
        if (paths != null && paths.length > 0) {
            for (String path : paths) {
                exec(worktreePath, "git", "add", path);
            }
        } else {
            // 默认只 add 变更的跟踪文件 + 新增的 src/ 下文件
            exec(worktreePath, "git", "add", "-u");
            exec(worktreePath, "git", "add", "src/");
        }
        exec(worktreePath, "git", "commit", "-m", message, "--allow-empty");
        log.info("[GitExecutor] commit done");
    }

    /**
     * Backward-compatible overload: stage tracked changes + src/ directory.
     */
    public void commitAll(String worktreePath, String message) {
        commitAll(worktreePath, message, (String[]) null);
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
