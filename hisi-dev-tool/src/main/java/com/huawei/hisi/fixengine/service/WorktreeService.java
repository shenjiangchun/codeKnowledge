package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.executor.GitExecutor;
import lombok.extern.slf4j.Slf4j;
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

    public WorktreeService(GitExecutor gitExecutor) {
        this.gitExecutor = gitExecutor;
    }

    /**
     * Create a new git worktree for the fix branch.
     *
     * @param branchName   name of the new branch
     * @param repoPath     absolute path to the main repository
     * @param targetBranch base branch (e.g. "master")
     * @return absolute path to the new worktree directory
     */
    public String createWorktree(String branchName, String repoPath, String targetBranch) {
        String worktreePath = repoPath + "/.worktrees/" + branchName;
        log.info("[WorktreeService] creating worktree {} -> {}", branchName, worktreePath);
        gitExecutor.createWorktree(repoPath, worktreePath, branchName, targetBranch);
        return worktreePath;
    }

    /**
     * Write a test source file into the worktree.
     *
     * @param worktreePath root of the worktree
     * @param packageName  Java package (e.g. "com.example.service")
     * @param className    simple class name (e.g. "FooTest")
     * @param testCode     full Java source
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
     *
     * @param worktreePath root of the worktree
     * @param filePath     path relative to worktree root (e.g. "src/main/java/com/foo/Bar.java")
     * @param fixedSource  new file content
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
