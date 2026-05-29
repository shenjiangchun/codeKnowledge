package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.exception.WorkingDirDirtyException;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitStatusServiceJgitTest {

    private final GitStatusService service = new GitStatusService();

    @Test
    @DisplayName("assertClean succeeds on a clean repo")
    void assertClean_cleanRepo_noException(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            File file = tempDir.resolve("hello.txt").toFile();
            Files.writeString(file.toPath(), "hello");
            git.add().addFilepattern("hello.txt").call();
            git.commit().setMessage("init").call();

            service.assertClean(tempDir.toString());
        }
    }

    @Test
    @DisplayName("assertClean throws on dirty repo")
    void assertClean_dirtyRepo_throwsWorkingDirDirtyException(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            File file = tempDir.resolve("hello.txt").toFile();
            Files.writeString(file.toPath(), "hello");
            git.add().addFilepattern("hello.txt").call();
            // deliberately not committing

            assertThatThrownBy(() -> service.assertClean(tempDir.toString()))
                    .isInstanceOf(WorkingDirDirtyException.class)
                    .hasMessageContaining(tempDir.toString());
        }
    }

    @Test
    @DisplayName("getChangedFilesJgit returns changed paths between two commits")
    void getChangedFilesJgit_returnsChangedPaths(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // First commit: create fileA
            File fileA = tempDir.resolve("fileA.txt").toFile();
            Files.writeString(fileA.toPath(), "content A");
            git.add().addFilepattern("fileA.txt").call();
            var commit1 = git.commit().setMessage("add fileA").call();

            // Second commit: modify fileA + add fileB
            Files.writeString(fileA.toPath(), "content A modified");
            File fileB = tempDir.resolve("fileB.txt").toFile();
            Files.writeString(fileB.toPath(), "content B");
            git.add().addFilepattern("fileA.txt").call();
            git.add().addFilepattern("fileB.txt").call();
            var commit2 = git.commit().setMessage("modify fileA, add fileB").call();

            List<String> changed = service.getChangedFilesJgit(
                    tempDir.toString(),
                    commit1.getName(),
                    commit2.getName());

            assertThat(changed).containsExactlyInAnyOrder("fileA.txt", "fileB.txt");
        }
    }

    @Test
    @DisplayName("getChangedFilesJgit returns empty list when same commit")
    void getChangedFilesJgit_noChanges_returnsEmpty(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            File file = tempDir.resolve("hello.txt").toFile();
            Files.writeString(file.toPath(), "hello");
            git.add().addFilepattern("hello.txt").call();
            var commit = git.commit().setMessage("init").call();

            List<String> changed = service.getChangedFilesJgit(
                    tempDir.toString(),
                    commit.getName(),
                    commit.getName());

            assertThat(changed).isEmpty();
        }
    }
}
