package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffExtractServiceTest {

    @TempDir
    Path tempDir;

    private DiffExtractService service;
    private String repoPath;

    @BeforeEach
    void setUp() throws Exception {
        service = new DiffExtractService();
        repoPath = tempDir.toString();

        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Initial commit on main
            File helloFile = new File(repoPath, "hello.txt");
            Files.writeString(helloFile.toPath(), "hello");
            git.add().addFilepattern("hello.txt").call();
            git.commit().setMessage("initial commit").call();

            // Create feature branch and switch to it
            git.checkout().setCreateBranch(true).setName("feature").call();

            // Modify hello.txt
            Files.writeString(helloFile.toPath(), "hello world");
            git.add().addFilepattern("hello.txt").call();

            // Add new file
            File newFile = new File(repoPath, "new.txt");
            Files.writeString(newFile.toPath(), "new content");
            git.add().addFilepattern("new.txt").call();

            git.commit().setMessage("feature changes").call();
        }
    }

    @Test
    @DisplayName("extractDiff returns correct file diffs between feature and main")
    void extractDiff_featureVsMain_returnsCorrectDiffs() {
        DiffResult result = service.extractDiff(repoPath, "feature", "main");

        assertThat(result.getSourceBranch()).isEqualTo("feature");
        assertThat(result.getTargetBranch()).isEqualTo("main");
        assertThat(result.getTotalFiles()).isEqualTo(2);
        assertThat(result.getTotalAdditions()).isGreaterThan(0);

        List<String> filePaths = result.getFiles().stream()
                .map(DiffResult.FileDiff::getFilePath)
                .toList();
        assertThat(filePaths).contains("hello.txt", "new.txt");

        DiffResult.FileDiff helloFileDiff = result.getFiles().stream()
                .filter(f -> f.getFilePath().equals("hello.txt"))
                .findFirst()
                .orElseThrow();
        assertThat(helloFileDiff.getChangeType()).isEqualTo("MODIFY");

        DiffResult.FileDiff newFileDiff = result.getFiles().stream()
                .filter(f -> f.getFilePath().equals("new.txt"))
                .findFirst()
                .orElseThrow();
        assertThat(newFileDiff.getChangeType()).isEqualTo("ADD");
        assertThat(newFileDiff.getAdditions()).isGreaterThan(0);
    }

    @Test
    @DisplayName("listBranches returns both main and feature")
    void listBranches_returnsAllBranches() {
        List<String> branches = service.listBranches(repoPath);

        assertThat(branches).contains("main", "feature");
    }
}
