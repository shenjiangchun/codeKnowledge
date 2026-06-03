package com.huawei.hisi.service;

import com.huawei.hisi.model.GitRepositoryInfo;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;

import static com.huawei.hisi.config.DataSourceConfig.PROJECT_DIR;

/**
 * 项目管理服务实现
 * 项目列表从 Neo4j 查询（旧 method_call_graph5 SQLite 表已废弃）
 */
@Service
public class ProjectServiceImpl implements ProjectService {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ProjectServiceImpl.class);

    @Autowired
    private AppConfigService appConfigService;

    @Autowired
    private Neo4jMethodNodeRepository neo4jMethodNodeRepository;

    @Value("${app.codeHubUser:}")
    private String codeHubUser;

    @Value("${app.codeHubPassword:}")
    private String codeHubPassword;

    private static final Map<String, ProjectStatus> ANALYSIS_STATUS = new ConcurrentHashMap<>();

    /**
     * 项目状态枚举
     */
    public enum ProjectStatus {
        PENDING, CLONING, ANALYZING, COMPLETED, FAILED
    }

    @Override
    public List<String> listProjects() {
        try {
            return neo4jMethodNodeRepository.findDistinctProjectPaths();
        } catch (Exception e) {
            LOG.error("Failed to list projects from Neo4j", e);
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> cloneProject(String repository, String branch) {
        Map<String, Object> result = new HashMap<>();

        try {
            String projectName = extractProjectName(repository);
            Path projectDir = Paths.get(PROJECT_DIR, projectName);

            if (Files.exists(projectDir)) {
                Git git = Git.open(projectDir.toFile());
                UsernamePasswordCredentialsProvider credentials =
                    new UsernamePasswordCredentialsProvider(codeHubUser, codeHubPassword);
                PullResult pullResult = git.pull()
                    .setCredentialsProvider(credentials)
                    .call();
                git.checkout().setName(branch).call();
                result.put("success", true);
                result.put("message", "Project updated successfully");
                result.put("project", projectName);
            } else {
                Files.createDirectories(projectDir);
                UsernamePasswordCredentialsProvider credentials =
                    new UsernamePasswordCredentialsProvider(codeHubUser, codeHubPassword);

                LOG.info("[Clone] Starting clone: url={}, branch={}, target={}", repository, branch, projectDir);
                try (Git git = Git.cloneRepository()
                    .setURI(repository)
                    .setDirectory(projectDir.toFile())
                    .setBranch(branch)
                    .setCredentialsProvider(credentials)
                    .call()) {
                    LOG.info("[Clone] Success: url={}, target={}", repository, projectDir);
                    result.put("success", true);
                    result.put("message", "Project cloned successfully");
                    result.put("project", projectName);
                    result.put("path", projectDir.toString());
                }
            }
        } catch (GitAPIException | IOException e) {
            LOG.error("[Clone] Failed: url={}, error={}", repository, e.getMessage(), e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return result;
    }

    @Override
    public Map<String, Object> getStatus(String project) {
        Map<String, Object> status = new HashMap<>();

        Path projectDir = Paths.get(PROJECT_DIR, project);
        status.put("exists", Files.exists(projectDir));
        status.put("project", project);

        if (Files.exists(projectDir)) {
            status.put("path", projectDir.toString());
            status.put("status", getAnalysisStatus(project));
            status.put("uriCount", getUriCount(project));
        } else {
            status.put("status", "NOT_CLONED");
            status.put("uriCount", 0);
        }

        return status;
    }

    private String getAnalysisStatus(String project) {
        ProjectStatus status = ANALYSIS_STATUS.get(project);
        if (status != null) {
            return status.name();
        }
        try {
            long count = neo4jMethodNodeRepository.countByProjectPath(project);
            return count > 0 ? "COMPLETED" : "UNKNOWN";
        } catch (Exception e) {
            LOG.warn("Failed to check analysis status for: {}", project, e);
            return "UNKNOWN";
        }
    }

    private int getUriCount(String project) {
        try {
            return (int) neo4jMethodNodeRepository.countByProjectPath(project);
        } catch (Exception e) {
            LOG.warn("Failed to get URI count for project: {}", project, e);
            return 0;
        }
    }

    private String extractProjectName(String repository) {
        String name = repository.substring(repository.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    @Override
    public List<GitRepositoryInfo> scanGitRepositories() {
        List<GitRepositoryInfo> repositories = new ArrayList<>();

        String projectDir = appConfigService.getProjectDir();
        if (projectDir == null || projectDir.trim().isEmpty()) {
            LOG.warn("Project directory not configured");
            return repositories;
        }

        File baseDir = new File(projectDir);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            LOG.warn("Project directory does not exist: {}", projectDir);
            return repositories;
        }

        // 先扫描子目录下的 git 仓库
        boolean hasChildGitRepos = false;
        File[] subDirs = baseDir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                File gitDir = new File(subDir, ".git");
                if (gitDir.exists() && gitDir.isDirectory()) {
                    try {
                        GitRepositoryInfo repoInfo = extractGitInfo(subDir);
                        if (repoInfo != null) {
                            repositories.add(repoInfo);
                            hasChildGitRepos = true;
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to read git info for: {}", subDir.getName(), e);
                    }
                }
            }
        }

        // 仅当父目录是 git 仓库且 **没有** 子 git 仓库时，才把父目录作为项目暴露给前端。
        // 否则会出现：用户勾选父目录后，KG 数据其实存在子项目下，导致查询错位。
        // 父目录下含子项目的场景，让用户直接勾选所有子项目即可，无需再选父目录。
        File baseGitDir = new File(baseDir, ".git");
        if (baseGitDir.exists() && baseGitDir.isDirectory() && !hasChildGitRepos) {
            try {
                GitRepositoryInfo repoInfo = extractGitInfo(baseDir);
                if (repoInfo != null) {
                    repositories.add(repoInfo);
                }
            } catch (Exception e) {
                LOG.warn("Failed to read git info for base directory: {}", baseDir.getName(), e);
            }
        } else if (baseGitDir.exists() && hasChildGitRepos) {
            LOG.info("Skipped parent git repo '{}' because it has {} child git repos; users should pick the children",
                    baseDir.getName(), repositories.size());
        }

        LOG.info("Scanned {} git repositories in {}", repositories.size(), projectDir);
        return repositories;
    }

    private GitRepositoryInfo extractGitInfo(File repoDir) {
        try (Git git = Git.open(repoDir)) {
            String branch = getCurrentBranch(git);
            String remoteUrl = getRemoteUrl(git);
            boolean clean = git.status().call().isClean();

            // Get last commit info
            String lastCommitMessage = null;
            String lastCommitDate = null;
            try {
                Iterable<RevCommit> logs = git.log().setMaxCount(1).call();
                for (RevCommit commit : logs) {
                    lastCommitMessage = commit.getShortMessage();
                    lastCommitDate = commit.getAuthorIdent().getWhen().toString();
                    break;
                }
            } catch (Exception e) {
                LOG.debug("Could not get last commit for {}", repoDir.getName());
            }

            return GitRepositoryInfo.builder()
                    .name(repoDir.getName())
                    .path(repoDir.getAbsolutePath())
                    .branch(branch)
                    .remoteUrl(remoteUrl)
                    .clean(clean)
                    .source("scanned")
                    .lastCommitMessage(lastCommitMessage)
                    .lastCommitDate(lastCommitDate)
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to read git repository info for: {}", repoDir.getAbsolutePath(), e);
            return null;
        }
    }

    private String getCurrentBranch(Git git) throws Exception {
        Ref head = git.getRepository().exactRef("HEAD");
        if (head != null && head.isSymbolic()) {
            return head.getTarget().getName().replace("refs/heads/", "");
        }
        return "detached";
    }

    private String getRemoteUrl(Git git) {
        try {
            RemoteConfig remote = new RemoteConfig(git.getRepository().getConfig(), "origin");
            URIish uri = remote.getURIs().stream().findFirst().orElse(null);
            return uri != null ? uri.toString() : null;
        } catch (Exception e) {
            LOG.debug("Could not get remote URL: {}", e.getMessage());
            return null;
        }
    }
}