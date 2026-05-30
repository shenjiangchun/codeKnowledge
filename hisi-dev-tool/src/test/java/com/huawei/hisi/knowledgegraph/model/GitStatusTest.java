package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitStatus 单元测试
 */
class GitStatusTest {

    @Test
    @DisplayName("测试构建器创建 GitStatus")
    void testBuilder() {
        // When
        GitStatus status = GitStatus.builder()
                .clean(true)
                .commitHash("abc123def456")
                .branch("main")
                .hasUncommittedChanges(false)
                .build();

        // Then
        assertTrue(status.isClean());
        assertEquals("abc123def456", status.getCommitHash());
        assertEquals("main", status.getBranch());
        assertFalse(status.isHasUncommittedChanges());
    }

    @Test
    @DisplayName("测试无参构造和Setter")
    void testNoArgsConstructor() {
        // Given
        GitStatus status = new GitStatus();

        // When
        status.setClean(false);
        status.setCommitHash("def789");
        status.setBranch("develop");
        status.setHasUncommittedChanges(true);

        // Then
        assertFalse(status.isClean());
        assertEquals("def789", status.getCommitHash());
        assertEquals("develop", status.getBranch());
        assertTrue(status.isHasUncommittedChanges());
    }

    @Test
    @DisplayName("测试全参构造")
    void testAllArgsConstructor() {
        // When
        GitStatus status = new GitStatus(false, "xyz999", "feature", true, true, 2);

        // Then
        assertFalse(status.isClean());
        assertEquals("xyz999", status.getCommitHash());
        assertEquals("feature", status.getBranch());
        assertTrue(status.isHasUncommittedChanges());
        assertTrue(status.isHasUnpushedCommits());
        assertEquals(2, status.getUnpushedCommitCount());
    }
}
