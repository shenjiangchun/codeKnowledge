package com.huawei.hisi.knowledgegraph.codegraph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CodegraphSidecarService")
class CodegraphSidecarServiceTest {

    private CodegraphSidecarService service;

    @BeforeEach
    void setUp() {
        // Use a non-existent dist path so resolveCodegraphEntry will fail fast
        // rather than actually spawning node. Timeout is set short for tests.
        service = new CodegraphSidecarService("non-existent-dist", 1L);
    }

    @Test
    @DisplayName("run throws IllegalArgumentException for blank projectPath")
    void run_blankProjectPath_throws() {
        assertThatThrownBy(() -> service.run(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.run((String) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("run throws IllegalArgumentException for non-existent directory")
    void run_nonExistentDirectory_throws(@TempDir Path tmpDir) {
        Path nonExistent = tmpDir.resolve("does-not-exist");
        assertThatThrownBy(() -> service.run(nonExistent.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("run throws IOException when codegraph CLI entry is missing")
    void run_missingCliEntry_throws(@TempDir Path tmpDir) throws Exception {
        Files.createDirectories(tmpDir);
        assertThatThrownBy(() -> service.run(tmpDir.toString()))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("codegraph");
    }

    @Test
    @DisplayName("CodegraphRunResult record holds all fields")
    void runResult_recordFields() {
        var result = new CodegraphSidecarService.CodegraphRunResult(0, "output", "/tmp/db");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("output");
        assertThat(result.outputDbPath()).isEqualTo("/tmp/db");
    }
}
