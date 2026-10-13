package com.huawei.hisi.knowledgegraph.vector;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 前端节点向量化单测：描述生成 + 向量写回 + 失败不阻断。
 */
@ExtendWith(MockitoExtension.class)
class FrontendNodeVectorizerTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private Neo4jComponentNodeRepository componentNodeRepository;
    @Mock
    private Neo4jFrontendRouteNodeRepository frontendRouteNodeRepository;
    @Mock
    private Neo4jApiClientNodeRepository apiClientNodeRepository;

    private FrontendNodeVectorizer vectorizer;

    @BeforeEach
    void setUp() {
        vectorizer = new FrontendNodeVectorizer(embeddingService,
                componentNodeRepository, frontendRouteNodeRepository, apiClientNodeRepository);
    }

    @Test
    @DisplayName("三类节点均生成向量并写回")
    void vectorize_allThreeTypes_writesEmbeddings() {
        ComponentNode comp = ComponentNode.builder().componentId("c1").componentName("Home").description("首页").build();
        FrontendRouteNode route = FrontendRouteNode.builder().frontendRouteId("r1").path("/home").componentName("Home").build();
        ApiClientNode client = ApiClientNode.builder().apiClientId("a1").method("GET").url("/api/x").sourceFile("x.ts").build();

        when(componentNodeRepository.findWithoutEmbeddingByProjectPath("/fe")).thenReturn(List.of(comp));
        when(frontendRouteNodeRepository.findWithoutEmbeddingByProjectPath("/fe")).thenReturn(List.of(route));
        when(apiClientNodeRepository.findWithoutEmbeddingByProjectPath("/fe")).thenReturn(List.of(client));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        Map<String, Object> result = vectorizer.vectorize("/fe");

        assertThat(result.get("componentEmbeddings")).isEqualTo(1);
        assertThat(result.get("routeEmbeddings")).isEqualTo(1);
        assertThat(result.get("apiClientEmbeddings")).isEqualTo(1);
        assertThat(result.get("totalEmbeddings")).isEqualTo(3);
        verify(componentNodeRepository).updateDescriptionEmbedding(anyString(), any());
        verify(frontendRouteNodeRepository).updateDescriptionEmbedding(anyString(), any());
        verify(apiClientNodeRepository).updateDescriptionEmbedding(anyString(), any());
    }

    @Test
    @DisplayName("向量生成失败不阻断其余节点（保留节点，计数成功）")
    void vectorize_failureDoesNotAbortOthers() {
        ComponentNode comp = ComponentNode.builder().componentId("c1").componentName("Home").build();
        when(componentNodeRepository.findWithoutEmbeddingByProjectPath("/fe")).thenReturn(List.of(comp));
        when(frontendRouteNodeRepository.findWithoutEmbeddingByProjectPath("/fe")).thenReturn(List.of());
        when(apiClientNodeRepository.findWithoutEmbeddingByProjectPath("/fe")).thenReturn(List.of());
        when(embeddingService.generateEmbedding(anyString())).thenThrow(new RuntimeException("timeout"));

        Map<String, Object> result = vectorizer.vectorize("/fe");

        // 失败不抛异常，仅计数为 0
        assertThat(result.get("componentEmbeddings")).isEqualTo(0);
        assertThat(result.get("totalEmbeddings")).isEqualTo(0);
        verify(componentNodeRepository, never()).updateDescriptionEmbedding(anyString(), any());
    }
}
