package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.exception.WorkingDirDirtyException;
import com.huawei.hisi.knowledgegraph.model.GitStatus;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 状态服务
 * 用于获取 Git 仓库的状态信息
 */
@Service
@Slf4j
public class GitStatusService {

    private static final String GIT_DIR = ".git";

    /**
     * 检测工作目录是否干净
     * 使用 git status --porcelain 命令检查
     *
     * @param workingDirectory 工作目录路径
     * @return 如果干净返回 true，否则返回 false
     */
    public boolean isWorkingDirectoryClean(String workingDirectory) {
        if (!isValidGitDirectory(workingDirectory)) {
            return false;
        }

        try {
            String result = executeGitCommand(workingDirectory, "git", "status", "--porcelain");
            return result == null || result.trim().isEmpty();
        } catch (Exception e) {
            log.error("检查工作目录状态失败: {}", workingDirectory, e);
            return false;
        }
    }

    /**
     * 获取当前 commit hash
     * 使用 git rev-parse HEAD 命令获取
     *
     * @param workingDirectory 工作目录路径
     * @return 当前 commit hash，如果失败返回 null
     */
    public String getCurrentCommitHash(String workingDirectory) {
        if (!isValidGitDirectory(workingDirectory)) {
            return null;
        }

        try {
            String result = executeGitCommand(workingDirectory, "git", "rev-parse", "HEAD");
            return result != null ? result.trim() : null;
        } catch (Exception e) {
            log.error("获取当前 commit hash 失败: {}", workingDirectory, e);
            return null;
        }
    }

    /**
     * 获取当前分支名
     * 使用 git rev-parse --abbrev-ref HEAD 命令获取
     *
     * @param workingDirectory 工作目录路径
     * @return 当前分支名，如果失败返回 null
     */
    public String getCurrentBranch(String workingDirectory) {
        if (!isValidGitDirectory(workingDirectory)) {
            return null;
        }

        try {
            String result = executeGitCommand(workingDirectory, "git", "rev-parse", "--abbrev-ref", "HEAD");
            return result != null ? result.trim() : null;
        } catch (Exception e) {
            log.error("获取当前分支名失败: {}", workingDirectory, e);
            return null;
        }
    }

    /**
     * 获取两个 commit 之间的变更文件列表
     * 使用 git diff --name-only fromCommit..toCommit 命令获取
     *
     * @param workingDirectory 工作目录路径
     * @param fromCommit 起始 commit hash
     * @param toCommit 结束 commit hash
     * @return 变更文件路径列表
     */
    public List<String> getChangedFiles(String workingDirectory, String fromCommit, String toCommit) {
        List<String> changedFiles = new ArrayList<>();

        if (!isValidGitDirectory(workingDirectory) || fromCommit == null || toCommit == null) {
            return changedFiles;
        }

        try {
            String result = executeGitCommand(workingDirectory, "git", "diff", "--name-only",
                    fromCommit + ".." + toCommit);
            if (result != null && !result.trim().isEmpty()) {
                String[] files = result.trim().split("\n");
                for (String file : files) {
                    if (!file.isEmpty()) {
                        changedFiles.add(file.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取变更文件列表失败: {}..{}", fromCommit, toCommit, e);
        }

        return changedFiles;
    }

    /**
     * 获取完整的 Git 状态信息
     *
     * @param workingDirectory 工作目录路径
     * @return GitStatus 对象
     */
    public GitStatus getGitStatus(String workingDirectory) {
        if (!isValidGitDirectory(workingDirectory)) {
            return GitStatus.builder()
                    .clean(false)
                    .commitHash(null)
                    .branch(null)
                    .hasUncommittedChanges(true)
                    .hasUnpushedCommits(false)
                    .unpushedCommitCount(0)
                    .build();
        }

        boolean isClean = isWorkingDirectoryClean(workingDirectory);
        String commitHash = getCurrentCommitHash(workingDirectory);
        String branch = getCurrentBranch(workingDirectory);
        boolean hasUnpushed = hasUnpushedCommits(workingDirectory);
        int unpushedCount = getUnpushedCommitCount(workingDirectory);

        return GitStatus.builder()
                .clean(isClean)
                .commitHash(commitHash)
                .branch(branch)
                .hasUncommittedChanges(!isClean)
                .hasUnpushedCommits(hasUnpushed)
                .unpushedCommitCount(unpushedCount)
                .build();
    }

    /**
     * 检查本地是否有未推送的提交
     * 使用 git status --branch --porcelain 命令检查
     * 如果输出包含 "ahead" 则表示有未推送的提交
     *
     * @param workingDirectory 工作目录路径
     * @return 如果有未推送的提交返回 true，否则返回 false
     */
    public boolean hasUnpushedCommits(String workingDirectory) {
        if (!isValidGitDirectory(workingDirectory)) {
            return false;
        }

        try {
            String result = executeGitCommand(workingDirectory, "git", "status", "--branch", "--porcelain");
            if (result != null && result.contains("ahead")) {
                // 输出格式如: ## main...origin/main [ahead 2]
                // 表示本地分支领先远程 2 个提交
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("检查未推送提交失败: {}", workingDirectory, e);
            return false;
        }
    }

    /**
     * 获取未推送的提交数量
     *
     * @param workingDirectory 工作目录路径
     * @return 未推送的提交数量，如果无法确定返回 -1
     */
    public int getUnpushedCommitCount(String workingDirectory) {
        if (!isValidGitDirectory(workingDirectory)) {
            return -1;
        }

        try {
            String branch = getCurrentBranch(workingDirectory);
            if (branch == null) {
                return -1;
            }

            // 获取本地分支与远程分支的差异提交数
            String remoteBranch = "origin/" + branch;
            String result = executeGitCommand(workingDirectory, "git", "rev-list", "--count",
                    remoteBranch + "..HEAD");

            if (result != null && !result.trim().isEmpty()) {
                try {
                    return Integer.parseInt(result.trim());
                } catch (NumberFormatException e) {
                    log.warn("无法解析未推送提交数: {}", result);
                    return -1;
                }
            }
            return 0;
        } catch (Exception e) {
            log.error("获取未推送提交数量失败: {}", workingDirectory, e);
            return -1;
        }
    }

    /**
     * Assert that the working directory has no uncommitted changes (JGit).
     *
     * @param workingDirectory path to the git repository
     * @throws WorkingDirDirtyException if there are uncommitted changes
     * @throws IOException if the repository cannot be opened
     */
    public void assertClean(String workingDirectory) throws IOException {
        try (Git git = Git.open(new File(workingDirectory))) {
            org.eclipse.jgit.api.Status status = git.status().call();
            boolean dirty = !status.getUncommittedChanges().isEmpty()
                    || !status.getUntracked().isEmpty();
            if (dirty) {
                throw new WorkingDirDirtyException(workingDirectory);
            }
        } catch (WorkingDirDirtyException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to check git status for: " + workingDirectory, e);
        }
    }

    /**
     * Get changed file paths between two commits using JGit DiffCommand.
     *
     * @param workingDirectory path to the git repository
     * @param fromCommit       starting commit hash
     * @param toCommit         ending commit hash
     * @return list of changed file paths
     * @throws IOException if the repository cannot be opened or commits cannot be resolved
     */
    public List<String> getChangedFilesJgit(String workingDirectory, String fromCommit, String toCommit)
            throws IOException {
        List<String> changedFiles = new ArrayList<>();
        try (Git git = Git.open(new File(workingDirectory))) {
            Repository repository = git.getRepository();
            try (ObjectReader reader = repository.newObjectReader();
                 RevWalk revWalk = new RevWalk(repository)) {

                ObjectId oldId = repository.resolve(fromCommit);
                ObjectId newId = repository.resolve(toCommit);
                if (oldId == null || newId == null) {
                    throw new IOException("Cannot resolve commits: " + fromCommit + " / " + toCommit);
                }

                RevCommit oldCommit = revWalk.parseCommit(oldId);
                RevCommit newCommit = revWalk.parseCommit(newId);
                RevTree oldTree = oldCommit.getTree();
                RevTree newTree = newCommit.getTree();

                CanonicalTreeParser oldParser = new CanonicalTreeParser();
                oldParser.reset(reader, oldTree);
                CanonicalTreeParser newParser = new CanonicalTreeParser();
                newParser.reset(reader, newTree);

                List<DiffEntry> diffs = git.diff()
                        .setOldTree(oldParser)
                        .setNewTree(newParser)
                        .call();

                for (DiffEntry entry : diffs) {
                    String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath()
                            : entry.getNewPath();
                    changedFiles.add(path);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to diff commits in: " + workingDirectory, e);
        }
        return changedFiles;
    }

    /**
     * 检查目录是否是有效的 Git 仓库
     *
     * @param directory 目录路径
     * @return 如果是有效的 Git 仓库返回 true
     */
    public boolean isValidGitDirectory(String directory) {
        if (directory == null || directory.isEmpty()) {
            return false;
        }

        Path dirPath = Paths.get(directory);
        if (!Files.isDirectory(dirPath)) {
            return false;
        }

        Path gitDir = dirPath.resolve(GIT_DIR);
        return Files.isDirectory(gitDir);
    }

    /**
     * 执行 Git 命令并返回输出
     *
     * @param workingDirectory 工作目录
     * @param command 命令及参数
     * @return 命令输出
     * @throws IOException 如果执行失败
     * @throws InterruptedException 如果进程被中断
     */
    private String executeGitCommand(String workingDirectory, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(workingDirectory));
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("Git 命令执行失败，退出码: {}, 命令: {}", exitCode, String.join(" ", command));
            return null;
        }

        return output.toString();
    }
}
