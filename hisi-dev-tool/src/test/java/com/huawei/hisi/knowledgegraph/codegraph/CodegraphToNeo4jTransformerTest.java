package com.huawei.hisi.knowledgegraph.codegraph;

import com.huawei.hisi.knowledgegraph.service.storage.Neo4jStorageService;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("CodegraphToNeo4jTransformer")
class CodegraphToNeo4jTransformerTest {

    @Mock
    private Neo4jStorageService neo4jStorageService;

    @Mock
    private Neo4jMethodNodeRepository methodNodeRepository;

    private CodegraphToNeo4jTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new CodegraphToNeo4jTransformer(neo4jStorageService, methodNodeRepository);
    }

    @Test
    @DisplayName("transform throws when db is null")
    void transform_nullDb_throws() {
        assertThatThrownBy(() -> transformer.transform(null, "/proj", "svc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("transform throws when projectPath is blank")
    void transform_blankProjectPath_throws() {
        var db = new CodegraphSqliteReader.CodegraphDb(List.of(), List.of(), List.of());
        assertThatThrownBy(() -> transformer.transform(db, "", "svc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> transformer.transform(db, (String) null, "svc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("transform handles empty db without errors")
    void transform_emptyDb_returnsZeroCounts() {
        var db = new CodegraphSqliteReader.CodegraphDb(List.of(), List.of(), List.of());
        var result = transformer.transform(db, "/proj", "svc");
        assertThat(result.methodsSaved()).isEqualTo(0);
        assertThat(result.entryPointsSaved()).isEqualTo(0);
        assertThat(result.callsRelations()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(0);
    }

    @Test
    @DisplayName("transform converts function nodes to MethodNodes")
    void transform_functionNode_savesMethodNode() {
        var node = new CodegraphSqliteReader.CodegraphNode(
            "n1", "function", "myFunc", "pkg.MyClass.myFunc", "/path/file.ts",
            "typescript", 10, 20, 1, 5, "does stuff", "myFunc(): void", "public",
            true, true, false, false, null, null, "void", 1000L
        );
        var db = new CodegraphSqliteReader.CodegraphDb(List.of(node), List.of(), List.of());
        var result = transformer.transform(db, "/proj", "svc");
        assertThat(result.methodsSaved()).isEqualTo(1);
        verify(neo4jStorageService).saveMethodNodes(any());
    }

    @Test
    @DisplayName("transform converts route nodes to EntryPoints")
    void transform_routeNode_savesEntryPoint() {
        var node = new CodegraphSqliteReader.CodegraphNode(
            "r1", "route", "GET /api/users", null, "/path/routes.ts",
            "typescript", 5, 10, 0, 0, null, null, "public",
            true, false, false, false, null, null, null, 1000L
        );
        var db = new CodegraphSqliteReader.CodegraphDb(List.of(node), List.of(), List.of());
        var result = transformer.transform(db, "/proj", "svc");
        assertThat(result.entryPointsSaved()).isEqualTo(1);
        verify(neo4jStorageService).saveEntryPoints(any());
    }

    @Test
    @DisplayName("transform converts calls edges to CALLS relations")
    void transform_callsEdge_createsCallRelation() {
        var caller = new CodegraphSqliteReader.CodegraphNode(
            "n1", "function", "funcA", null, "/a.ts",
            "typescript", 1, 2, 0, 0, null, null, "public",
            true, false, false, false, null, null, "void", 1L
        );
        var callee = new CodegraphSqliteReader.CodegraphNode(
            "n2", "method", "funcB", null, "/b.ts",
            "typescript", 3, 4, 0, 0, null, null, "public",
            true, false, false, false, null, null, "void", 1L
        );
        var edge = new CodegraphSqliteReader.CodegraphEdge(
            1L, "n1", "n2", "calls", null, 10, 5, null
        );
        var db = new CodegraphSqliteReader.CodegraphDb(List.of(caller, callee), List.of(edge), List.of());
        var result = transformer.transform(db, "/proj", "svc");
        assertThat(result.callsRelations()).isEqualTo(1);
        verify(methodNodeRepository).createCallRelations(any());
    }

    @Test
    @DisplayName("transform converts contains/imports/references edges")
    void transform_containsImportsReferencesEdges() {
        var parent = new CodegraphSqliteReader.CodegraphNode(
            "n1", "function", "parentFunc", null, "/a.ts",
            "typescript", 1, 2, 0, 0, null, null, "public",
            true, false, false, false, null, null, "void", 1L
        );
        var child = new CodegraphSqliteReader.CodegraphNode(
            "n2", "function", "childFunc", null, "/a.ts",
            "typescript", 3, 4, 0, 0, null, null, "public",
            true, false, false, false, null, null, "void", 1L
        );
        var importTarget = new CodegraphSqliteReader.CodegraphNode(
            "n3", "function", "importedFunc", null, "/b.ts",
            "typescript", 5, 6, 0, 0, null, null, "public",
            true, false, false, false, null, null, "void", 1L
        );
        var refTarget = new CodegraphSqliteReader.CodegraphNode(
            "n4", "function", "refFunc", null, "/c.ts",
            "typescript", 7, 8, 0, 0, null, null, "public",
            true, false, false, false, null, null, "void", 1L
        );
        var containsEdge = new CodegraphSqliteReader.CodegraphEdge(1L, "n1", "n2", "contains", null, null, null, null);
        var importsEdge = new CodegraphSqliteReader.CodegraphEdge(2L, "n1", "n3", "imports", null, null, null, null);
        var refsEdge = new CodegraphSqliteReader.CodegraphEdge(3L, "n1", "n4", "references", null, null, null, null);
        var db = new CodegraphSqliteReader.CodegraphDb(
            List.of(parent, child, importTarget, refTarget),
            List.of(containsEdge, importsEdge, refsEdge),
            List.of()
        );
        var result = transformer.transform(db, "/proj", "svc");
        assertThat(result.containsRelations()).isEqualTo(1);
        assertThat(result.importsRelations()).isEqualTo(1);
        assertThat(result.referencesRelations()).isEqualTo(1);
        verify(methodNodeRepository).createContainsRelations(any());
        verify(methodNodeRepository).createImportsRelations(any());
        verify(methodNodeRepository).createReferencesRelations(any());
    }

    @Test
    @DisplayName("transform skips unknown node kinds and counts them")
    void transform_unknownKinds_skipped() {
        var unknownNode = new CodegraphSqliteReader.CodegraphNode(
            "u1", "unknown_kind", "x", null, "/x.ts",
            "typescript", 1, 2, 0, 0, null, null, "public",
            true, false, false, false, null, null, "void", 1L
        );
        var db = new CodegraphSqliteReader.CodegraphDb(List.of(unknownNode), List.of(), List.of());
        var result = transformer.transform(db, "/proj", "svc");
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("TransformResult.empty returns all zeros")
    void transformResult_empty() {
        var empty = CodegraphToNeo4jTransformer.TransformResult.empty();
        assertThat(empty.methodsSaved()).isEqualTo(0);
        assertThat(empty.entryPointsSaved()).isEqualTo(0);
        assertThat(empty.skipped()).isEqualTo(0);
    }
}
