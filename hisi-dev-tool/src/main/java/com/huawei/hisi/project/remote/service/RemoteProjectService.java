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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
        if (Files.exists(localDir)) {
            try (Stream<Path> walk = Files.walk(localDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
            } catch (IOException e) {
                log.error("Failed to walk directory for deletion: {}", localDir, e);
            }
        }

        repository.deleteById(id);
    }

    public void cloneProject(long id) {
        RemoteProject project = getById(id);
        repository.updateCloneStatus(id, "CLONING");

        Path targetDir = resolveCloneDir(project.getLocalPath());
        try {
            Files.createDirectories(targetDir);
            String password = gitCredentialService.decrypt(project.getEncryptedPassword());
            UsernamePasswordCredentialsProvider credentials =
                new UsernamePasswordCredentialsProvider(project.getUsername(), password);

            try (Git git = Git.cloneRepository()
                .setURI(project.getGitUrl())
                .setDirectory(targetDir.toFile())
                .setBranch(project.getBranch())
                .setCredentialsProvider(credentials)
                .call()) {
                log.info("Cloned {} to {}", project.getGitUrl(), targetDir);
            }

            repository.updateCloneStatus(id, "CLONED");
            repository.updateLastSyncAt(id, Instant.now().getEpochSecond());
        } catch (Exception e) {
            log.error("Failed to clone project {}: {}", project.getName(), e.getMessage(), e);
            repository.updateCloneStatus(id, "FAILED");
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

    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase();
    }
}
