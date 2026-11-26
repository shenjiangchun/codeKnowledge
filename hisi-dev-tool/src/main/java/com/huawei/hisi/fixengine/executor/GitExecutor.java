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

        // 清掉同名旧分支（上次同名运行留下的）。第一次运行时本来就没有这个分支，不算错误
        if (branchExists(repoPath, branchName)) {
            exec(repoPath, "git", "branch", "-D", branchName);
        }

        exec(repoPath,
                "git", "worktree", "add", "-b", branchName, worktreePath, targetBranch);

        log.info("[GitExecutor] worktree created at {}", worktreePath);
    }

    private boolean branchExists(String repoPath, String branchName) {
        try {
            Process proc = new ProcessBuilder("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + branchName)
                    .directory(new File(repoPath))
                    .redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor(1, TimeUnit.MINUTES) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
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

    /**
     * 探测仓库当前分支名（HEAD 所指的 local branch），用于 worktree 的 base branch。
     * 兼容 master / main / develop 等任意默认分支，避免硬编码 "master"。
     *
     * @return 当前分支名（如 "master" / "develop"）；失败或 detached HEAD 时返回 null
     */
    public String currentBranch(String repoPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String branch;
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                branch = reader.readLine();
            }
            proc.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (proc.exitValue() != 0 || branch == null || branch.isBlank() || "HEAD".equals(branch.trim())) {
                return null;
            }
            return branch.trim();
        } catch (Exception e) {
            log.error("[GitExecutor] current branch probe failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把分支推到 origin（同名分支，不合并）。凭据沿用本机 git 配置。
     *
     * @return 是否推送成功；失败只记日志并返回 false，不回滚本地提交
     */
    public boolean push(String worktreePath, String branchName) {
        log.info("[GitExecutor] pushing branch={}", branchName);
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "push", "-u", "origin", branchName);
            pb.directory(new File(worktreePath));
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
                log.error("[GitExecutor] push timed out");
                return false;
            }
            if (proc.exitValue() != 0) {
                log.warn("[GitExecutor] push failed exit={}: {}", proc.exitValue(), output);
                return false;
            }
            log.info("[GitExecutor] pushed {} to origin", branchName);
            return true;
        } catch (Exception e) {
            log.warn("[GitExecutor] push error: {}", e.getMessage());
            return false;
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
