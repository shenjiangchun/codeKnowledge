package com.huawei.hisi.knowledgegraph.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitStatusService 单元测试
 * 测试 Git 状态查询功能
 */
class GitStatusServiceTest {

    private GitStatusService gitStatusService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        gitStatusService = new GitStatusService();
    }

    @Test
    @DisplayName("检测工作目录是否干净 - 非Git目录")
    void testIsWorkingDirectoryClean_NotGitDirectory() throws IOException {
        // Given - 一个非 Git 目录
        Path nonGitDir = tempDir.resolve("non-git-dir");
        Files.createDirectories(nonGitDir);

        // When
        boolean isClean = gitStatusService.isWorkingDirectoryClean(nonGitDir.toString());

        // Then
        assertFalse(isClean, "非 Git 目录应该返回 false");
    }

    @Test
    @DisplayName("获取当前 commit hash - 非Git目录")
    void testGetCurrentCommitHash_NotGitDirectory() throws IOException {
        // Given - 一个非 Git 目录
        Path nonGitDir = tempDir.resolve("non-git-dir");
        Files.createDirectories(nonGitDir);

        // When
        String commitHash = gitStatusService.getCurrentCommitHash(nonGitDir.toString());

        // Then
        assertNull(commitHash, "非 Git 目录应该返回 null");
    }

    @Test
    @DisplayName("获取当前分支名 - 非Git目录")
    void testGetCurrentBranch_NotGitDirectory() throws IOException {
        // Given - 一个非 Git 目录
        Path nonGitDir = tempDir.resolve("non-git-dir");
        Files.createDirectories(nonGitDir);

        // When
        String branch = gitStatusService.getCurrentBranch(nonGitDir.toString());

        // Then
        assertNull(branch, "非 Git 目录应该返回 null");
    }

    @Test
    @DisplayName("获取变更文件列表 - 无效的 commit hash")
    void testGetChangedFiles_InvalidCommitHash() throws IOException {
        // Given
        Path nonGitDir = tempDir.resolve("non-git-dir");
        Files.createDirectories(nonGitDir);

        // When
        List<String> changedFiles = gitStatusService.getChangedFiles(nonGitDir.toString(), "invalid", "hash");

        // Then
        assertTrue(changedFiles.isEmpty(), "无效的 commit hash 应该返回空列表");
    }

    @Test
    @DisplayName("检测工作目录是否干净 - 目录不存在")
    void testIsWorkingDirectoryClean_DirectoryNotExist() {
        // Given
        String nonExistDir = "/path/to/non/exist/directory";

        // When
        boolean isClean = gitStatusService.isWorkingDirectoryClean(nonExistDir);

        // Then
        assertFalse(isClean, "不存在的目录应该返回 false");
    }

    @Test
    @DisplayName("获取完整 Git 状态 - 非Git目录")
    void testGetGitStatus_NotGitDirectory() throws IOException {
        // Given
        Path nonGitDir = tempDir.resolve("non-git-dir");
        Files.createDirectories(nonGitDir);

        // When
        var status = gitStatusService.getGitStatus(nonGitDir.toString());

        // Then
        assertNotNull(status);
        assertFalse(status.isClean());
        assertNull(status.getCommitHash());
        assertNull(status.getBranch());
    }
}
