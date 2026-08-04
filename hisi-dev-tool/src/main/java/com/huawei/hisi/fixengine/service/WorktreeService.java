package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.executor.GitExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Encapsulates git worktree and file I/O operations for the fix flow.
 */
@Slf4j
@Service
public class WorktreeService {

    private final GitExecutor gitExecutor;
    private final String worktreeBaseDir;

    public WorktreeService(GitExecutor gitExecutor,
                           @Value("${hisi.fix.worktree-base-dir:${user.home}/.hisi-devtool/worktrees}") String worktreeBaseDir) {
        this.gitExecutor = gitExecutor;
        this.worktreeBaseDir = worktreeBaseDir;
    }

    /**
     * Create a new git worktree for the fix branch.
     * Worktree path uses external base-dir（不在主仓库内部）。
     *
     * @param branchName   name of the new branch
     * @param repoPath     absolute path to the main repository
     * @param targetBranch base branch (e.g. "master")
     * @return absolute path to the new worktree directory
     */
    public String createWorktree(String branchName, String repoPath, String targetBranch) {
        // 使用外部基础目录，不在主仓库内部创建 worktree
        String worktreePath = worktreeBaseDir + "/" + branchName;
        log.info("[WorktreeService] creating worktree {} -> {}", branchName, worktreePath);
        gitExecutor.createWorktree(repoPath, worktreePath, branchName, targetBranch);
        return worktreePath;
    }

    /**
     * 探测仓库当前分支名，供 worktree base branch 使用。
     *
     * @return 当前分支名；探测失败或 detached HEAD 时返回 null
     */
    public String currentBranch(String repoPath) {
        return gitExecutor.currentBranch(repoPath);
    }

    /**
     * Write a test source file into the worktree.
     */
    public void writeTestFile(String worktreePath, String packageName,
                              String className, String testCode) {
        String relativeDir = packageName.replace('.', '/');
        String testDir = worktreePath + "/src/test/java/" + relativeDir;
        try {
            Files.createDirectories(Path.of(testDir));
            Path file = Path.of(testDir, className + ".java");
            Files.writeString(file, testCode);
            log.info("[WorktreeService] wrote test file {}", file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write test file: " + e.getMessage(), e);
        }
    }

    /**
     * Replace the source of a file in the worktree (apply AI-generated fix).
     */
    public void applyFix(String worktreePath, String filePath, String fixedSource) {
        Path target = Path.of(worktreePath, filePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, fixedSource);
            log.info("[WorktreeService] applied fix to {}", target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to apply fix: " + e.getMessage(), e);
        }
    }

    /**
     * Commit all changes in the worktree.
     *
     * @return the commit SHA, or null on failure
     */
    public String commit(String branchName, String worktreePath, String message) {
        log.info("[WorktreeService] committing on branch={}", branchName);
        gitExecutor.commitAll(worktreePath, message);
        return gitExecutor.revParseHead(worktreePath);
    }
}
