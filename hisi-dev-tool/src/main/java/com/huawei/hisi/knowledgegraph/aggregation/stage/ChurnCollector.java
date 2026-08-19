package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.knowledgegraph.aggregation.AggregationCheckpointManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChurnCollector {

    private final Driver neo4jDriver;
    private final AggregationCheckpointManager checkpointManager;

    public void collect(String projectPath, List<String> changedFiles) {
        boolean isFull = (changedFiles == null || changedFiles.isEmpty());
        log.info("[Aggregation][Churn] collect 开始, projectPath={}, isFull={}, changedFiles={}",
            projectPath, isFull, changedFiles != null ? changedFiles.size() : 0);

        File repoDir = new File(projectPath);
        if (!new File(repoDir, ".git").exists()) {
            log.warn("[Aggregation] Stage=Churn 跳过: 非 Git 仓库, projectPath={}", projectPath);
            checkpointManager.markSuccess(projectPath, "Churn", "no-git-repo");
            return;
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git")).build();
             Git git = new Git(repo)) {

            Instant ninetyDaysAgo = Instant.now().minusSeconds(90L * 24 * 60 * 60);
            Set<String> filesToProcess = isFull
                ? collectAllTrackedFiles(projectPath)
                : new HashSet<>(changedFiles);

            for (String filePath : filesToProcess) {
                try {
                    // JGIT 的 addPath 需要相对仓库根的路径，而 MethodNode.filePath 是绝对路径
                    String relPath = toRepoRelativePath(repoDir, filePath);
                    var logIter = git.log().addPath(relPath).call();
                    int commitCount = 0;
                    int linesAdded = 0;
                    int linesDeleted = 0;
                    Set<String> authors = new HashSet<>();
                    String lastCommitAt = null;

                    for (RevCommit commit : logIter) {
                        if (commit.getCommitTime() < ninetyDaysAgo.getEpochSecond() && commitCount > 0) break;
                        if (commit.getCommitTime() >= ninetyDaysAgo.getEpochSecond()) {
                            commitCount++;
                            authors.add(commit.getAuthorIdent().getName());
                            if (lastCommitAt == null) {
                                lastCommitAt = LocalDateTime.ofInstant(
                                    Instant.ofEpochSecond(commit.getCommitTime()), ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                            }
                            // 累加变更行数（对父提交做 path-filtered diff）
                            if (commit.getParentCount() > 0) {
                                int[] lines = countDiffLines(repo, commit.getParent(0), commit, relPath);
                                linesAdded += lines[0];
                                linesDeleted += lines[1];
                            }
                        }
                    }

                    // 直接通过 Driver MERGE，避免 Spring Data Repository 的 transactionManager 不可用
                    try (Session s = neo4jDriver.session()) {
                        s.run(
                            "MERGE (c:ChurnNode {nodeId: $id})\n" +
                            "SET c.filePath = $fp,\n" +
                            "    c.commitCount90d = $cc,\n" +
                            "    c.linesChanged90d = $lines,\n" +
                            "    c.lastCommitAt = $last,\n" +
                            "    c.authorCount90d = $authors,\n" +
                            "    c.projectPath = $path",
                            Map.of("id", projectPath + ":" + filePath,
                                "fp", filePath, "cc", commitCount,
                                "lines", linesAdded + linesDeleted,
                                "last", lastCommitAt != null ? lastCommitAt : "",
                                "authors", authors.size(), "path", projectPath));
                    }
                } catch (Exception e) {
                    log.debug("[Aggregation] Churn 跳过文件 {}: {}", filePath, e.getMessage());
                }
            }

            checkpointManager.markSuccess(projectPath, "Churn", String.valueOf(filesToProcess.size()));
            log.info("[Aggregation] Stage=Churn 完成, 处理文件: {}", filesToProcess.size());

        } catch (IOException e) {
            log.error("[Aggregation] Stage=Churn 失败: 无法打开 Git 仓库", e);
            checkpointManager.markFailed(projectPath, "Churn", e.getMessage());
        }
    }

    /** 把绝对路径转为相对仓库根的路径（JGIT addPath 要求相对路径） */
    private String toRepoRelativePath(File repoDir, String filePath) {
        if (filePath == null) return filePath;
        String root = repoDir.getAbsolutePath().replace('\\', '/');
        String fp = filePath.replace('\\', '/');
        if (fp.startsWith(root + "/")) {
            return fp.substring(root.length() + 1);
        }
        // 已经相对或无法归一化时原样返回
        return fp;
    }

    /** 计算单次提交对指定文件的新增/删除行数（path-filtered diff） */
    private int[] countDiffLines(Repository repo, RevCommit parent, RevCommit commit, String filePath) {        int added = 0, deleted = 0;
        try (DiffFormatter df = new DiffFormatter(OutputStream.nullOutputStream())) {
            df.setRepository(repo);
            df.setPathFilter(PathFilter.create(filePath));
            for (DiffEntry entry : df.scan(parent.getTree(), commit.getTree())) {
                for (Edit edit : df.toFileHeader(entry).toEditList()) {
                    added += edit.getEndB() - edit.getBeginB();
                    deleted += edit.getEndA() - edit.getBeginA();
                }
            }
        } catch (IOException e) {
            log.debug("[Aggregation] 计算变更行数失败 {}: {}", filePath, e.getMessage());
        }
        return new int[]{added, deleted};
    }

    private Set<String> collectAllTrackedFiles(String projectPath) {
        Set<String> files = new HashSet<>();
        try (Session session = neo4jDriver.session()) {
            var records = session.run(
                "MATCH (m:Method {projectPath: $path})\n" +
                "WHERE m.filePath IS NOT NULL\n" +
                "RETURN DISTINCT m.filePath AS fp",
                Map.of("path", projectPath));
            while (records.hasNext()) {
                files.add(records.next().get("fp").asString());
            }
            log.info("[Aggregation][Churn] 收集到 {} 个文件路径", files.size());
        } catch (Exception e) {
            log.warn("[Aggregation] 收集文件列表失败: {}", e.getMessage());
        }
        return files;
    }
}
