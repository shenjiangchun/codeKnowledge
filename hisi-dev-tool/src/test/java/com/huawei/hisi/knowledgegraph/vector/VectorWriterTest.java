package com.huawei.hisi.knowledgegraph.vector;

import com.huawei.hisi.knowledgegraph.service.LLMDescriptionService;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorWriterTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private LLMDescriptionService llmDescriptionService;
    @Mock private Neo4jMethodNodeRepository methodNodeRepository;
    @Mock private Neo4jSqlNodeRepository sqlNodeRepository;

    private VectorWriter vectorWriter;

    @BeforeEach
    void setUp() {
        vectorWriter = new VectorWriter(embeddingService, llmDescriptionService, methodNodeRepository, sqlNodeRepository);
    }

    @Test
    @DisplayName("upsertMethod generates description and embeddings and persists")
    void upsertMethod_generatesDescriptionAndEmbeddingsAndPersists() {
        MethodNode method = MethodNode.builder()
                .nodeId("node-1")
                .className("com.example.Foo")
                .methodName("bar")
                .signature("String, int")
                .methodBody("return x + y;")
                .build();

        when(llmDescriptionService.generateDescriptionWithBody(method)).thenReturn("Adds x and y");
        float[] descEmb = {0.1f, 0.2f};
        float[] codeEmb = {0.3f, 0.4f};
        when(embeddingService.generateEmbedding("Adds x and y")).thenReturn(descEmb);
        // Code text starts with className.methodName(signature) - stub via prefix not anyString to avoid clash
        String expectedCodeText = "com.example.Foo.bar(String, int)\nreturn x + y;";
        when(embeddingService.generateEmbedding(expectedCodeText)).thenReturn(codeEmb);

        vectorWriter.upsertMethod(method);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Double>> descEmbCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Double>> codeEmbCaptor = ArgumentCaptor.forClass(List.class);

        verify(methodNodeRepository).updateDescriptionAndCodeEmbedding(
                eq("node-1"), descCaptor.capture(), descEmbCaptor.capture(), codeEmbCaptor.capture());

        assertThat(descCaptor.getValue()).isEqualTo("Adds x and y");
        assertThat(descEmbCaptor.getValue()).hasSize(2);
        assertThat(descEmbCaptor.getValue().get(0)).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(descEmbCaptor.getValue().get(1)).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(codeEmbCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("upsertMethod with blank description falls back to className.methodName - signature")
    void upsertMethod_blankDescription_fallsBackToClassName() {
        MethodNode method = MethodNode.builder()
                .nodeId("node-2")
                .className("com.example.Baz")
                .methodName("qux")
                .signature("long")
                .build();

        when(llmDescriptionService.generateDescriptionWithBody(method)).thenReturn("   ");
        when(embeddingService.generateEmbedding(anyString())).thenReturn(new float[]{0.5f});

        vectorWriter.upsertMethod(method);

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(methodNodeRepository).updateDescriptionAndCodeEmbedding(
                eq("node-2"), descCaptor.capture(), any(), any());

        assertThat(descCaptor.getValue()).isEqualTo("com.example.Baz.qux - long");
    }

    @Test
    @DisplayName("upsertSql generates embedding and persists")
    void upsertSql_generatesEmbeddingAndPersists() {
        SqlNode sqlNode = SqlNode.builder()
                .nodeId("sql-1")
                .sqlStatement("SELECT * FROM users")
                .build();

        float[] sqlEmb = {0.7f, 0.8f};
        when(embeddingService.generateEmbedding("SELECT * FROM users")).thenReturn(sqlEmb);

        vectorWriter.upsertSql(sqlNode);

        verify(sqlNodeRepository).updateSqlEmbedding("sql-1", sqlEmb);
    }

    @Test
    @DisplayName("deleteByFilePath calls detachDeleteByFilePathAndScope with correct args")
    void deleteByFilePath_callsDetachDelete() {
        vectorWriter.deleteByFilePath("/src/main/Foo.java", "/workspace/root");

        verify(methodNodeRepository).detachDeleteByFilePathAndProjectPath("/src/main/Foo.java", "/workspace/root");
    }
}
