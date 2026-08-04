package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test using the real test project at test-projects/java-spring-test.
 * Requires the test project to have multiple branches (master, feature-a, feature-b).
 */
class DiffExtractServiceRealProjectTest {

    // Adjust path to match your actual test project location
    private static final String TEST_PROJECT_PATH = System.getProperty("testProjectPath",
            Paths.get(System.getProperty("user.dir"))
                    .resolve("../test-projects/java-spring-test")
                    .normalize()
                    .toString());

    private static DiffExtractService service;
    private static boolean projectExists;

    @BeforeAll
    static void setUp() {
        service = new DiffExtractService();
        Path projectPath = Paths.get(TEST_PROJECT_PATH);
        projectExists = projectPath.toFile().exists() && projectPath.toFile().isDirectory();
        if (!projectExists) {
            System.err.println("Test project not found at: " + TEST_PROJECT_PATH + " - tests will be skipped");
        }
    }

    @Test
    @DisplayName("List branches from real test project")
    void listBranches_realProject() {
        if (!projectExists) return; // Skip if project doesn't exist

        List<String> branches = service.listBranches(TEST_PROJECT_PATH);

        assertThat(branches).isNotEmpty();
        assertThat(branches).contains("master");
        System.out.println("Available branches: " + branches);
    }

    @Test
    @DisplayName("Different feature branches produce different diffs against master")
    void differentBranches_differentDiffs() {
        if (!projectExists) return; // Skip if project doesn't exist

        // First check if branches exist
        List<String> branches = service.listBranches(TEST_PROJECT_PATH);

        // Skip if feature branches don't exist
        if (!branches.contains("feature-a") || !branches.contains("feature-b")) {
            System.err.println("feature-a or feature-b not found, skipping test");
            return;
        }

        // Extract diff for feature-a vs master
        DiffResult resultA = service.extractDiff(TEST_PROJECT_PATH, "feature-a", "master");
        System.out.println("feature-a vs master: files=" + resultA.getTotalFiles() +
                ", additions=" + resultA.getTotalAdditions());

        // Extract diff for feature-b vs master
        DiffResult resultB = service.extractDiff(TEST_PROJECT_PATH, "feature-b", "master");
        System.out.println("feature-b vs master: files=" + resultB.getTotalFiles() +
                ", additions=" + resultB.getTotalAdditions());

        // Verify: The diffs should be different
        List<String> filesA = resultA.getFiles().stream()
                .map(DiffResult.FileDiff::getFilePath).toList();
        List<String> filesB = resultB.getFiles().stream()
                .map(DiffResult.FileDiff::getFilePath).toList();

        System.out.println("Files changed in feature-a: " + filesA);
        System.out.println("Files changed in feature-b: " + filesB);

        // feature-a should have changed UserController.java
        assertThat(filesA).contains("src/main/java/com/example/controller/UserController.java");

        // feature-b should have changed UserService.java
        assertThat(filesB).contains("src/main/java/com/example/service/UserService.java");

        // The file lists should NOT be identical
        assertThat(filesA).isNotEqualTo(filesB);
    }

    @Test
    @DisplayName("Same branch pair returns consistent result")
    void sameBranchPair_consistentResult() {
        if (!projectExists) return;

        List<String> branches = service.listBranches(TEST_PROJECT_PATH);
        if (!branches.contains("feature-a")) {
            System.err.println("feature-a not found, skipping test");
            return;
        }

        // Call extractDiff twice with same parameters
        DiffResult result1 = service.extractDiff(TEST_PROJECT_PATH, "feature-a", "master");
        DiffResult result2 = service.extractDiff(TEST_PROJECT_PATH, "feature-a", "master");

        // Should return identical results
        assertThat(result1.getTotalFiles()).isEqualTo(result2.getTotalFiles());
        assertThat(result1.getTotalAdditions()).isEqualTo(result2.getTotalAdditions());
    }
}