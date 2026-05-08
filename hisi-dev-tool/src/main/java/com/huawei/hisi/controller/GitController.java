package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/git")
public class GitController {

    @Value("${app.codeHubUser:}")
    private String gitUser;

    @Value("${app.codeHubPassword:}")
    private String gitPassword;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus(@RequestParam String path) {
        try (Git git = Git.open(new File(path))) {
            Status status = git.status().call();

            Map<String, Object> result = new HashMap<>();
            result.put("branch", getCurrentBranch(git));
            result.put("clean", status.isClean());
            result.put("modified", new ArrayList<>(status.getModified()));
            result.put("untracked", new ArrayList<>(status.getUntracked()));
            result.put("added", new ArrayList<>(status.getAdded()));
            result.put("removed", new ArrayList<>(status.getRemoved()));

            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get git status: " + e.getMessage());
        }
    }

    @PostMapping("/checkout")
    public ApiResponse<String> checkout(@RequestBody CheckoutRequest request) {
        try (Git git = Git.open(new File(request.getPath()))) {
            git.checkout().setName(request.getBranch()).call();
            return ApiResponse.success("Switched to branch: " + request.getBranch());
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to checkout: " + e.getMessage());
        }
    }

    @PostMapping("/pull")
    public ApiResponse<Map<String, Object>> pull(@RequestBody PullRequest request) {
        try (Git git = Git.open(new File(request.getPath()))) {
            Repository repository = git.getRepository();
            String branch = getCurrentBranch(git);

            // 先检查本地状态
            Status status = git.status().call();
            if (!status.isClean()) {
                Map<String, Object> response = new HashMap<>();
                response.put("successful", false);
                response.put("branch", branch);
                response.put("message", "本地有未提交的修改，无法执行 pull。请先提交或暂存本地修改。");
                response.put("modified", new ArrayList<>(status.getModified()));
                response.put("untracked", new ArrayList<>(status.getUntracked()));
                return ApiResponse.success(response);
            }

            // 先执行 fetch 确保获取远程最新数据
            FetchResult fetchResult = git.fetch()
                .setCredentialsProvider(getCredentialsProvider())
                .call();

            log.info("Fetch result for {}: {}", request.getPath(),
                fetchResult.getTrackingRefUpdates().isEmpty() ? "无更新" : "有更新");

            // 获取远程分支引用
            String remoteBranchRef = "refs/remotes/origin/" + branch;
            Ref remoteRef = repository.exactRef(remoteBranchRef);

            if (remoteRef == null) {
                // 尝试获取默认远程分支
                remoteRef = repository.exactRef("refs/remotes/origin/HEAD");
                if (remoteRef != null && remoteRef.isSymbolic()) {
                    remoteBranchRef = remoteRef.getTarget().getName();
                    remoteRef = repository.exactRef(remoteBranchRef);
                }
            }

            // 执行 pull
            PullResult pullResult = git.pull()
                .setCredentialsProvider(getCredentialsProvider())
                .call();

            Map<String, Object> response = new HashMap<>();
            response.put("successful", pullResult.isSuccessful());
            response.put("branch", branch);

            // 详细检查 merge 结果
            MergeResult mergeResult = pullResult.getMergeResult();
            if (mergeResult != null) {
                MergeResult.MergeStatus mergeStatus = mergeResult.getMergeStatus();
                response.put("mergeStatus", mergeStatus.name());
                response.put("message", getMergeStatusMessage(mergeStatus));

                if (mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
                    response.put("conflicts", mergeResult.getConflicts());
                    response.put("successful", false);
                } else if (mergeStatus == MergeResult.MergeStatus.FAST_FORWARD ||
                           mergeStatus == MergeResult.MergeStatus.MERGED) {
                    response.put("successful", true);
                    response.put("newCommits", mergeResult.getMergedCommits());
                } else if (mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                    response.put("message", "本地已是最新，无需合并");
                }
            } else {
                // 没有 merge 结果，可能是只 fetch 没有 merge
                response.put("message", "Fetch 完成，但未执行 Merge。请检查分支追踪配置。");
                response.put("fetchUpdates", fetchResult.getTrackingRefUpdates().size());
            }

            // 比较本地和远程提交
            if (remoteRef != null) {
                ObjectId localHead = repository.resolve("HEAD");
                ObjectId remoteHead = remoteRef.getObjectId();
                response.put("localHead", localHead != null ? localHead.getName().substring(0, 8) : "unknown");
                response.put("remoteHead", remoteHead != null ? remoteHead.getName().substring(0, 8) : "unknown");
                response.put("synced", localHead != null && remoteHead != null && localHead.equals(remoteHead));
            }

            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("Pull failed for {}: {}", request.getPath(), e.getMessage());
            return ApiResponse.error(500, "Failed to pull: " + e.getMessage());
        }
    }

    private String getMergeStatusMessage(MergeResult.MergeStatus status) {
        if (status == MergeResult.MergeStatus.FAST_FORWARD) {
            return "Fast-forward 合并成功";
        } else if (status == MergeResult.MergeStatus.MERGED) {
            return "合并成功";
        } else if (status == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
            return "本地已是最新";
        } else if (status == MergeResult.MergeStatus.CONFLICTING) {
            return "合并冲突，需要手动解决";
        } else if (status == MergeResult.MergeStatus.FAILED) {
            return "合并失败";
        } else if (status == MergeResult.MergeStatus.ABORTED) {
            return "合并中止";
        } else {
            return status.name();
        }
    }

    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> getLogs(
            @RequestParam String path,
            @RequestParam(defaultValue = "10") int limit) {
        try (Git git = Git.open(new File(path))) {
            Iterable<RevCommit> logs = git.log().setMaxCount(limit).call();

            List<Map<String, Object>> result = new ArrayList<>();
            for (RevCommit commit : logs) {
                Map<String, Object> logEntry = new HashMap<>();
                logEntry.put("commitId", commit.getName().substring(0, 8));
                logEntry.put("fullCommitId", commit.getName());
                logEntry.put("message", commit.getFullMessage());
                logEntry.put("author", commit.getAuthorIdent().getName());
                logEntry.put("date", commit.getAuthorIdent().getWhen());
                result.add(logEntry);
            }

            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to get logs: " + e.getMessage());
        }
    }

    /**
     * 获取 Git 提交列表（带详细信息）
     * GET /api/git/commits
     * 包含远程分支的最新提交
     */
    @GetMapping("/commits")
    public ApiResponse<List<Map<String, Object>>> getCommits(
            @RequestParam String path,
            @RequestParam(defaultValue = "50") int limit) {
        try (Git git = Git.open(new File(path))) {
            Repository repository = git.getRepository();
            String branch = getCurrentBranch(git);

            // 先执行 fetch 确保远程数据是最新的（静默执行，不阻塞）
            try {
                git.fetch()
                    .setCredentialsProvider(getCredentialsProvider())
                    .call();
                log.debug("Fetched latest remote data for {}", path);
            } catch (Exception e) {
                log.warn("Fetch failed for {}, using cached data: {}", path, e.getMessage());
            }

            // 获取本地 HEAD 提交历史
            Iterable<RevCommit> localLogs = git.log().setMaxCount(limit).call();

            // 尝试获取远程分支的提交
            String remoteBranchRef = "refs/remotes/origin/" + branch;
            Ref remoteRef = repository.exactRef(remoteBranchRef);

            // 合并本地和远程提交（去重）
            Set<String> seenCommitIds = new HashSet<>();
            List<Map<String, Object>> result = new ArrayList<>();

            // 先添加本地提交
            for (RevCommit commit : localLogs) {
                if (!seenCommitIds.contains(commit.getName())) {
                    result.add(buildCommitEntry(commit, "local"));
                    seenCommitIds.add(commit.getName());
                }
            }

            // 如果远程分支存在且与本地不同，添加远程提交
            if (remoteRef != null) {
                try {
                    ObjectId remoteHead = remoteRef.getObjectId();
                    ObjectId localHead = repository.resolve("HEAD");

                    // 只有当远程有新提交时才添加
                    if (remoteHead != null && !remoteHead.equals(localHead)) {
                        Iterable<RevCommit> remoteLogs = git.log()
                            .add(remoteHead)
                            .setMaxCount(limit)
                            .call();

                        for (RevCommit commit : remoteLogs) {
                            if (!seenCommitIds.contains(commit.getName())) {
                                result.add(buildCommitEntry(commit, "remote"));
                                seenCommitIds.add(commit.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to get remote commits: {}", e.getMessage());
                }
            }

            // 按时间排序（最新的在前）
            result.sort((a, b) -> {
                java.util.Date dateA = (java.util.Date) a.get("dateRaw");
                java.util.Date dateB = (java.util.Date) b.get("dateRaw");
                if (dateA == null || dateB == null) return 0;
                return dateB.compareTo(dateA);
            });

            // 截取到指定数量
            if (result.size() > limit) {
                result = result.subList(0, limit);
            }

            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to get commits: {}", e.getMessage());
            return ApiResponse.error(500, "Failed to get commits: " + e.getMessage());
        }
    }

    private Map<String, Object> buildCommitEntry(RevCommit commit, String source) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("commitId", commit.getName().substring(0, 8));
        logEntry.put("fullCommitId", commit.getName());
        logEntry.put("shortMessage", commit.getShortMessage());
        logEntry.put("fullMessage", commit.getFullMessage());
        logEntry.put("author", commit.getAuthorIdent().getName());
        logEntry.put("authorEmail", commit.getAuthorIdent().getEmailAddress());
        logEntry.put("date", LocalDateTime.ofInstant(
            commit.getAuthorIdent().getWhen().toInstant(),
            ZoneId.systemDefault()
        ));
        logEntry.put("dateRaw", commit.getAuthorIdent().getWhen());
        logEntry.put("source", source);  // 标记是本地还是远程的提交
        return logEntry;
    }

    /**
     * 获取提交的详细变更内容
     * GET /api/git/commit-diff
     */
    @GetMapping("/commit-diff")
    public ApiResponse<Map<String, Object>> getCommitDiff(
            @RequestParam String path,
            @RequestParam String commitId) {
        try (Git git = Git.open(new File(path))) {
            Repository repository = git.getRepository();

            // 获取提交对象
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(repository.resolve(commitId));

                if (commit == null) {
                    return ApiResponse.error(404, "Commit not found: " + commitId);
                }

                Map<String, Object> result = new HashMap<>();
                result.put("commitId", commit.getName().substring(0, 8));
                result.put("fullCommitId", commit.getName());
                result.put("message", commit.getFullMessage());
                result.put("author", commit.getAuthorIdent().getName());
                result.put("date", commit.getAuthorIdent().getWhen());

                // 获取变更文件列表
                if (commit.getParentCount() > 0) {
                    RevCommit parent = commit.getParent(0);
                    org.eclipse.jgit.diff.DiffFormatter diffFormatter =
                        new org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE);
                    diffFormatter.setRepository(repository);
                    diffFormatter.setDetectRenames(true);

                    List<org.eclipse.jgit.diff.DiffEntry> diffs = diffFormatter.scan(parent, commit);

                    List<Map<String, Object>> files = new ArrayList<>();
                    for (org.eclipse.jgit.diff.DiffEntry entry : diffs) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("changeType", entry.getChangeType().name());
                        fileInfo.put("oldPath", entry.getOldPath());
                        fileInfo.put("newPath", entry.getNewPath());
                        files.add(fileInfo);
                    }
                    result.put("files", files);
                }

                return ApiResponse.success(result);
            }
        } catch (Exception e) {
            log.error("Failed to get commit diff: {}", e.getMessage());
            return ApiResponse.error(500, "Failed to get commit diff: " + e.getMessage());
        }
    }

    /**
     * 一键更新项目目录下所有 Git 仓库
     * POST /api/git/update-all
     */
    @PostMapping("/update-all")
    public ApiResponse<Map<String, Object>> updateAll(@RequestBody UpdateAllRequest request) {
        String projectDir = request.getProjectDir();
        if (projectDir == null || projectDir.isEmpty()) {
            return ApiResponse.error(400, "项目目录不能为空");
        }

        File projectDirFile = new File(projectDir);
        if (!projectDirFile.exists() || !projectDirFile.isDirectory()) {
            return ApiResponse.error(400, "项目目录不存在");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        try {
            // 查找所有 Git 仓库
            List<File> gitRepos = findGitRepositories(projectDirFile);
            log.info("在 {} 下找到 {} 个 Git 仓库", projectDir, gitRepos.size());

            for (File repoDir : gitRepos) {
                Map<String, Object> repoResult = new HashMap<>();
                String relativePath = projectDirFile.toPath().relativize(repoDir.toPath()).toString();
                repoResult.put("path", relativePath);
                repoResult.put("absolutePath", repoDir.getAbsolutePath());

                try (Git git = Git.open(repoDir)) {
                    Repository repository = git.getRepository();
                    String branch = getCurrentBranch(git);
                    repoResult.put("branch", branch);

                    // 检查本地状态
                    Status status = git.status().call();
                    if (!status.isClean()) {
                        repoResult.put("success", false);
                        repoResult.put("message", "本地有未提交修改，跳过更新");
                        repoResult.put("modifiedCount", status.getModified().size());
                        failCount++;
                        results.add(repoResult);
                        continue;
                    }

                    // 执行 fetch
                    FetchResult fetchResult = git.fetch()
                        .setCredentialsProvider(getCredentialsProvider())
                        .call();

                    // 执行 pull
                    PullResult pullResult = git.pull()
                        .setCredentialsProvider(getCredentialsProvider())
                        .call();

                    MergeResult mergeResult = pullResult.getMergeResult();
                    boolean merged = false;
                    String message = "";

                    if (mergeResult != null) {
                        MergeResult.MergeStatus mergeStatus = mergeResult.getMergeStatus();
                        message = getMergeStatusMessage(mergeStatus);
                        merged = mergeStatus == MergeResult.MergeStatus.FAST_FORWARD ||
                                 mergeStatus == MergeResult.MergeStatus.MERGED ||
                                 mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE;
                    } else {
                        message = pullResult.isSuccessful() ? "Fetch 成功" : "更新失败";
                        merged = pullResult.isSuccessful();
                    }

                    // 检查是否与远程同步
                    String remoteBranchRef = "refs/remotes/origin/" + branch;
                    Ref remoteRef = repository.exactRef(remoteBranchRef);
                    ObjectId localHead = repository.resolve("HEAD");
                    boolean synced = false;
                    if (remoteRef != null && localHead != null) {
                        synced = localHead.equals(remoteRef.getObjectId());
                    }

                    repoResult.put("success", merged);
                    repoResult.put("synced", synced);
                    repoResult.put("message", message);

                    if (mergeResult != null && mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                        repoResult.put("conflicts", mergeResult.getConflicts().keySet());
                        repoResult.put("success", false);
                    }

                    if (merged) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    repoResult.put("success", false);
                    repoResult.put("message", "更新失败: " + e.getMessage());
                    failCount++;
                    log.warn("更新仓库 {} 失败: {}", repoDir.getAbsolutePath(), e.getMessage());
                }

                results.add(repoResult);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("totalRepos", gitRepos.size());
            response.put("successCount", successCount);
            response.put("failCount", failCount);
            response.put("results", results);

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("一键更新失败: {}", e.getMessage());
            return ApiResponse.error(500, "一键更新失败: " + e.getMessage());
        }
    }

    /**
     * 递归查找 Git 仓库
     */
    private List<File> findGitRepositories(File directory) throws IOException {
        List<File> repos = new ArrayList<>();

        // 检查当前目录是否是 Git 仓库
        File gitDir = new File(directory, ".git");
        if (gitDir.exists() && gitDir.isDirectory()) {
            repos.add(directory);
            return repos; // 如果是 Git 仓库，不再递归子目录
        }

        // 递归查找子目录
        File[] children = directory.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                // 跳过隐藏目录和常见的非项目目录
                if (child.getName().startsWith(".") ||
                    child.getName().equals("node_modules") ||
                    child.getName().equals("target") ||
                    child.getName().equals("build")) {
                    continue;
                }
                repos.addAll(findGitRepositories(child));
            }
        }

        return repos;
    }

    private String getCurrentBranch(Git git) throws Exception {
        Ref head = git.getRepository().exactRef("HEAD");
        if (head != null && head.isSymbolic()) {
            return head.getTarget().getName().replace("refs/heads/", "");
        }
        return "detached";
    }

    private UsernamePasswordCredentialsProvider getCredentialsProvider() {
        if (gitUser != null && !gitUser.isEmpty() && gitPassword != null && !gitPassword.isEmpty()) {
            return new UsernamePasswordCredentialsProvider(gitUser, gitPassword);
        }
        return null;
    }

    @Data
    public static class CheckoutRequest {
        private String path;
        private String branch;
    }

    @Data
    public static class PullRequest {
        private String path;
    }

    @Data
    public static class UpdateAllRequest {
        private String projectDir;
    }
}