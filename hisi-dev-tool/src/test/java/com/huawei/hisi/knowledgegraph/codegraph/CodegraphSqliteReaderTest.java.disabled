package com.huawei.hisi.knowledgegraph.codegraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CodegraphSqliteReader")
class CodegraphSqliteReaderTest {

    private final CodegraphSqliteReader reader = new CodegraphSqliteReader();

    @Test
    @DisplayName("readAll throws IllegalArgumentException for blank dbPath")
    void readAll_blankDbPath_throws() {
        assertThatThrownBy(() -> reader.readAll(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> reader.readAll((String) null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("readAll throws IOException for non-existent file")
    void readAll_nonExistentFile_throws(@TempDir Path tmpDir) {
        Path nonExistent = tmpDir.resolve("no-such-db.db");
        assertThatThrownBy(() -> reader.readAll(nonExistent.toString()))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("readAll throws IOException for directory instead of file")
    void readAll_directoryInsteadOfFile_throws(@TempDir Path tmpDir) {
        assertThatThrownBy(() -> reader.readAll(tmpDir.toString()))
            .isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("CodegraphNode record has expected fields")
    void codegraphNode_recordFields() {
        var node = new CodegraphSqliteReader.CodegraphNode(
            "id1", "function", "myFunc", "pkg.myFunc", "/path/file.ts",
            "typescript", 10, 20, 5, 15, "doc", "sig", "public",
            true, false, true, false, "[]", "<T>", "void", 1234567890L
        );
        assertThat(node.id()).isEqualTo("id1");
        assertThat(node.kind()).isEqualTo("function");
        assertThat(node.language()).isEqualTo("typescript");
        assertThat(node.isExported()).isTrue();
        assertThat(node.isStatic()).isTrue();
        assertThat(node.startLine()).isEqualTo(10);
    }

    @Test
    @DisplayName("CodegraphEdge record handles null line/col")
    void codegraphEdge_nullLineCol() {
        var edge = new CodegraphSqliteReader.CodegraphEdge(
            1L, "src1", "tgt1", "calls", "{}", null, null, "source"
        );
        assertThat(edge.line()).isNull();
        assertThat(edge.col()).isNull();
        assertThat(edge.kind()).isEqualTo("calls");
    }

    @Test
    @DisplayName("CodegraphFile record handles null nodeCount/errors")
    void codegraphFile_nullFields() {
        var file = new CodegraphSqliteReader.CodegraphFile(
            "/path/file.ts", "abc123", "typescript", 1024, 1000L, 2000L, null, null
        );
        assertThat(file.nodeCount()).isNull();
        assertThat(file.errors()).isNull();
        assertThat(file.language()).isEqualTo("typescript");
    }

    @Test
    @DisplayName("CodegraphDb aggregates all three tables")
    void codegraphDb_aggregates() {
        var nodes = java.util.List.of(
            new CodegraphSqliteReader.CodegraphNode("n1", "function", "f", "p.f", "/f.ts",
                "typescript", 1, 2, 0, 0, null, null, "public",
                true, false, false, false, null, null, "void", 1L)
        );
        var edges = java.util.List.of(
            new CodegraphSqliteReader.CodegraphEdge(1L, "a", "b", "calls", null, 10, 5, null)
        );
        var files = java.util.List.<CodegraphSqliteReader.CodegraphFile>of();
        var db = new CodegraphSqliteReader.CodegraphDb(nodes, edges, files);
        assertThat(db.nodes()).hasSize(1);
        assertThat(db.edges()).hasSize(1);
        assertThat(db.files()).isEmpty();
    }
}
