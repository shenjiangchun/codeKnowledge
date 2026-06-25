package com.huawei.hisi.project.remote.service;

import com.huawei.hisi.project.remote.model.AuthType;
import com.huawei.hisi.project.remote.model.RemoteProject;
import com.huawei.hisi.project.remote.repository.RemoteProjectRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;
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

    // Original create method (backward compatibility)
    public long create(String name, String gitUrl, String username, String password, String branch) {
        return create(name, gitUrl, username, password, "PASSWORD", null, null, branch, null);
    }

    // Enhanced create method with auth type support (backward compatibility)
    public long create(String name, String gitUrl, String username, String password,
                       String authType, String sshKeyPath, String token, String branch) {
        return create(name, gitUrl, username, password, authType, sshKeyPath, token, branch, null);
    }

    // Full create method with groupId support
    public long create(String name, String gitUrl, String username, String password,
                       String authType, String sshKeyPath, String token, String branch, Long groupId) {
        String encryptedPassword = null;
        String encryptedToken = null;

        if ("PASSWORD".equals(authType) && password != null && !password.isEmpty()) {
            encryptedPassword = gitCredentialService.encrypt(password);
        }
        if ("TOKEN".equals(authType) && token != null && !token.isEmpty()) {
            encryptedToken = gitCredentialService.encrypt(token);
        }

        String localPath = sanitizeName(name);

        RemoteProject project = RemoteProject.builder()
            .name(name)
            .gitUrl(gitUrl)
            .username(username)
            .encryptedPassword(encryptedPassword)
            .branch(branch != null ? branch : "main")
            .localPath(localPath)
            .cloneStatus("PENDING")
            .authType(authType != null ? authType : "PASSWORD")
            .sshKeyPath(sshKeyPath)
            .encryptedToken(encryptedToken)
            .groupId(groupId)
            .build();

        return repository.insert(project);
    }

    // Original update method (backward compatibility)
    public void update(long id, String name, String gitUrl, String username, String password, String branch) {
        update(id, name, gitUrl, username, password, null, null, null, branch, null);
    }

    // Enhanced update method with auth type support (backward compatibility)
    public void update(long id, String name, String gitUrl, String username, String password,
                       String authType, String sshKeyPath, String token, String branch) {
        update(id, name, gitUrl, username, password, authType, sshKeyPath, token, branch, null);
    }

    // Full update method with groupId support
    public void update(long id, String name, String gitUrl, String username, String password,
                       String authType, String sshKeyPath, String token, String branch, Long groupId) {
        RemoteProject existing = getById(id);

        String encryptedPassword = existing.getEncryptedPassword();
        String encryptedToken = existing.getEncryptedToken();

        if ("PASSWORD".equals(authType) && password != null && !password.isEmpty()) {
            encryptedPassword = gitCredentialService.encrypt(password);
        } else if (!"PASSWORD".equals(authType)) {
            encryptedPassword = null; // Clear password if switching auth type
        }

        if ("TOKEN".equals(authType) && token != null && !token.isEmpty()) {
            encryptedToken = gitCredentialService.encrypt(token);
        } else if (!"TOKEN".equals(authType)) {
            encryptedToken = null; // Clear token if switching auth type
        }

        existing.setName(name);
        existing.setGitUrl(gitUrl);
        existing.setUsername(username);
        existing.setEncryptedPassword(encryptedPassword);
        existing.setBranch(branch != null ? branch : existing.getBranch());
        existing.setAuthType(authType != null ? authType : existing.getAuthType());
        existing.setSshKeyPath("SSH_KEY".equals(authType) ? sshKeyPath : null);
        existing.setEncryptedToken(encryptedToken);
        existing.setGroupId(groupId);

        repository.update(existing);
    }

    private CredentialsProvider getCredentialsProvider(RemoteProject project) {
        AuthType authType = AuthType.valueOf(project.getAuthType());

        switch (authType) {
            case PASSWORD:
                String password = gitCredentialService.decrypt(project.getEncryptedPassword());
                return new UsernamePasswordCredentialsProvider(
                    project.getUsername() != null ? project.getUsername() : "",
                    password != null ? password : ""
                );

            case TOKEN:
                String token = gitCredentialService.decrypt(project.getEncryptedToken());
                return new UsernamePasswordCredentialsProvider("oauth2", token != null ? token : "");

            case SSH_KEY:
                // SSH auth uses SshSessionFactory, no CredentialsProvider needed
                return null;

            default:
                throw new IllegalArgumentException("Unsupported auth type: " + authType);
        }
    }

    private void configureSshSessionFactory(String sshKeyPath) {
        // Expand ~ to user home and make it final for inner class
        final String expandedPath;
        if (sshKeyPath != null && sshKeyPath.startsWith("~")) {
            expandedPath = Paths.get(System.getProperty("user.home"), sshKeyPath.substring(2)).toString();
        } else {
            expandedPath = sshKeyPath;
        }

        SshSessionFactory.setInstance(new JschConfigSessionFactory() {
            @Override
            protected void configure(Host hc, Session session) {
                session.setConfig("StrictHostKeyChecking", "no");
            }

            @Override
            protected JSch getJSch(Host hc, FS fs) throws JSchException {
                JSch jsch = super.getJSch(hc, fs);
                if (expandedPath != null && !expandedPath.isEmpty()) {
                    jsch.addIdentity(expandedPath);
                }
                return jsch;
            }
        });
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

        // Fix invalid localPath (e.g., "----")
        String localPath = project.getLocalPath();
        String cleaned = localPath.replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (cleaned.isBlank()) {
            String oldPath = localPath;
            localPath = sanitizeName(project.getName());
            project.setLocalPath(localPath);
            repository.update(project);
            log.info("[Clone] Fixed invalid localPath: {} -> {}", oldPath, localPath);
        }

        Path targetDir = resolveCloneDir(localPath);
        AuthType authType = AuthType.valueOf(project.getAuthType());

        try {
            // Clean target directory if exists and not empty
            if (Files.exists(targetDir) && Files.list(targetDir).findAny().isPresent()) {
                log.warn("[Clone] Target directory not empty, cleaning: {}", targetDir);
                deleteDirectory(targetDir);
            }
            Files.createDirectories(targetDir);

            if (authType == AuthType.SSH_KEY) {
                configureSshSessionFactory(project.getSshKeyPath());
            }

            try (Git git = Git.cloneRepository()
                .setURI(project.getGitUrl())
                .setDirectory(targetDir.toFile())
                .setBranch(project.getBranch())
                .setCredentialsProvider(authType == AuthType.SSH_KEY ? null : getCredentialsProvider(project))
                .call()) {
                log.info("[Clone] Success: url={}, target={}", project.getGitUrl(), targetDir);
            }

            repository.updateCloneStatus(id, "CLONED");
            repository.updateLastSyncAt(id, Instant.now().getEpochSecond());

        } catch (Exception e) {
            String errorDetail = extractRootCause(e);
            log.error("[Clone] Failed: project={}, error={}", project.getName(), errorDetail, e);
            repository.updateCloneError(id, errorDetail);
        } finally {
            // Reset SSH Factory to avoid affecting other clones
            if (authType == AuthType.SSH_KEY) {
                SshSessionFactory.setInstance(null);
            }
        }
    }

    public void pullProject(long id) {
        RemoteProject project = getById(id);
        if (!"CLONED".equals(project.getCloneStatus())) {
            throw new RuntimeException("Project is not cloned: " + project.getCloneStatus());
        }

        Path repoDir = resolveCloneDir(project.getLocalPath());
        AuthType authType = AuthType.valueOf(project.getAuthType());

        try {
            if (authType == AuthType.SSH_KEY) {
                configureSshSessionFactory(project.getSshKeyPath());
            }

            try (Git git = Git.open(repoDir.toFile())) {
                PullCommand pullCmd = git.pull();
                if (authType != AuthType.SSH_KEY) {
                    pullCmd.setCredentialsProvider(getCredentialsProvider(project));
                }
                PullResult result = pullCmd.call();
                log.info("[Pull] Success: {}, {}", project.getName(), result.isSuccessful());
            }

            repository.updateLastSyncAt(id, Instant.now().getEpochSecond());

        } catch (Exception e) {
            log.error("[Pull] Failed: {}, {}", project.getName(), e.getMessage(), e);
            throw new RuntimeException("Pull failed: " + e.getMessage(), e);
        } finally {
            if (authType == AuthType.SSH_KEY) {
                SshSessionFactory.setInstance(null);
            }
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
        // Fallback to timestamp if name is empty after sanitization
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
        // Truncate to 500 chars
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
