package com.huawei.hisi.project.remote.service;

import com.huawei.hisi.project.remote.model.RemoteProject;
import com.huawei.hisi.project.remote.repository.RemoteProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteProjectService {

    private final RemoteProjectRepository repository;
    private final GitCredentialService gitCredentialService;

    public List<RemoteProject> list() {
        return repository.findAll();
    }

    public RemoteProject getById(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new RuntimeException("Remote project not found: id=" + id));
    }

    public long create(String name, String gitUrl, String username, String password, String branch) {
        String encrypted = gitCredentialService.encrypt(password);
        String localPath = sanitizeName(name);

        RemoteProject project = RemoteProject.builder()
            .name(name)
            .gitUrl(gitUrl)
            .username(username)
            .encryptedPassword(encrypted)
            .branch(branch != null ? branch : "main")
            .localPath(localPath)
            .cloneStatus("PENDING")
            .build();

        return repository.insert(project);
    }

    public void update(long id, String name, String gitUrl, String username, String password, String branch) {
        RemoteProject existing = getById(id);

        String encrypted = (password != null && !password.isEmpty())
            ? gitCredentialService.encrypt(password)
            : existing.getEncryptedPassword();

        existing.setName(name);
        existing.setGitUrl(gitUrl);
        existing.setUsername(username);
        existing.setEncryptedPassword(encrypted);
        existing.setBranch(branch != null ? branch : existing.getBranch());

        repository.update(existing);
    }

    public void delete(long id) {
        RemoteProject project = getById(id);

        Path localDir = resolveCloneDir(project.getLocalPath());
        deleteDirectory(localDir);

        repository.deleteById(id);
    }

    public void cloneProject(long id) {
        RemoteProject project = getById(id);
        repository.updateCloneStatus(id, "CLONING");

        // 修复旧数据中 localPath 为纯短横线（如 "----"）的情况
        String localPath = project.getLocalPath();
        String cleaned = localPath.replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (cleaned.isBlank()) {
            String oldPath = localPath;
            localPath = sanitizeName(project.getName());
            project.setLocalPath(localPath);
            repository.update(project);
            log.info("[Clone] Fixed invalid localPath for project '{}': {} -> {}",
                project.getName(), oldPath, localPath);
        }

        Path targetDir = resolveCloneDir(localPath);
        try {
            // 如果目标目录已存在且有内容，先清理（可能是上次失败的残留）
            if (Files.exists(targetDir) && Files.list(targetDir).findAny().isPresent()) {
                log.warn("[Clone] Target directory not empty, cleaning: {}", targetDir);
                deleteDirectory(targetDir);
            }
            Files.createDirectories(targetDir);
            String password = gitCredentialService.decrypt(project.getEncryptedPassword());
            UsernamePasswordCredentialsProvider credentials =
                new UsernamePasswordCredentialsProvider(project.getUsername(), password);

            log.info("[Clone] Starting clone: url={}, branch={}, target={}", project.getGitUrl(), project.getBranch(), targetDir);
            try (Git git = Git.cloneRepository()
                .setURI(project.getGitUrl())
                .setDirectory(targetDir.toFile())
                .setBranch(project.getBranch())
                .setCredentialsProvider(credentials)
                .call()) {
                log.info("[Clone] Success: url={}, target={}", project.getGitUrl(), targetDir);
            }

            repository.updateCloneStatus(id, "CLONED");
            repository.updateLastSyncAt(id, Instant.now().getEpochSecond());
        } catch (Exception e) {
            String errorDetail = extractRootCause(e);
            log.error("[Clone] Failed: project={}, url={}, error={}", project.getName(), project.getGitUrl(), errorDetail, e);
            repository.updateCloneError(id, errorDetail);
        }
    }

    public void pullProject(long id) {
        RemoteProject project = getById(id);
        if (!"CLONED".equals(project.getCloneStatus())) {
            throw new RuntimeException("Project is not cloned, current status: " + project.getCloneStatus());
        }

        Path repoDir = resolveCloneDir(project.getLocalPath());
        try {
            String password = gitCredentialService.decrypt(project.getEncryptedPassword());
            UsernamePasswordCredentialsProvider credentials =
                new UsernamePasswordCredentialsProvider(project.getUsername(), password);

            try (Git git = Git.open(repoDir.toFile())) {
                PullResult result = git.pull()
                    .setCredentialsProvider(credentials)
                    .call();
                log.info("Pulled {}: {}", project.getName(), result.isSuccessful());
            }

            repository.updateLastSyncAt(id, Instant.now().getEpochSecond());
        } catch (Exception e) {
            log.error("Failed to pull project {}: {}", project.getName(), e.getMessage(), e);
            throw new RuntimeException("Pull failed: " + e.getMessage(), e);
        }
    }

    private Path resolveCloneDir(String localPath) {
        return Paths.get(System.getProperty("user.dir"), "remote-repos", localPath);
    }

    public String getFullLocalPath(String localPath) {
        return resolveCloneDir(localPath).toString();
    }

    private String sanitizeName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9._-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (!sanitized.isBlank()) {
            return sanitized.toLowerCase();
        }
        // 中文/特殊字符名全部被替换后为空，从 gitUrl 提取仓库名
        return "project-" + System.currentTimeMillis();
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    file.toFile().setWritable(true);
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    d.toFile().setWritable(true);
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to delete directory: {}", dir, e);
        }
    }

    private String extractRootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.getClass().getSimpleName();
        }
        // Truncate to 500 chars to fit DB column comfortably
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
