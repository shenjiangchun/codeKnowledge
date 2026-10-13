package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.SearchResultItem;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository.ApiClientWithScore;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository.ComponentWithScore;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository.RouteWithScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * FrontendNodeSearchService 单测：前端三类节点检索命中 + nodeType 标识。
 */
@ExtendWith(MockitoExtension.class)
class FrontendNodeSearchServiceTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private Neo4jComponentNodeRepository componentNodeRepository;
    @Mock
    private Neo4jFrontendRouteNodeRepository frontendRouteNodeRepository;
    @Mock
    private Neo4jApiClientNodeRepository apiClientNodeRepository;

    private FrontendNodeSearchService service;

    @BeforeEach
    void setUp() {
        service = new FrontendNodeSearchService(embeddingService,
                componentNodeRepository, frontendRouteNodeRepository, apiClientNodeRepository);
    }

    @Test
    @DisplayName("三类前端节点均命中且 nodeType 标识正确")
    void search_allThreeTypes_nodeTypeCorrect() {
        when(embeddingService.generateEmbedding(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(componentNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                anyList(), anyList(), anyDouble(), anyInt()))
            .thenReturn(List.of(new ComponentWithScore("c1", "Home", "/f/Home.vue", "/fe", "首页", 0.9)));
        when(frontendRouteNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                anyList(), anyList(), anyDouble(), anyInt()))
            .thenReturn(List.of(new RouteWithScore("r1", "/home", "Home", "/fe", 0.85)));
        when(apiClientNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                anyList(), anyList(), anyDouble(), anyInt()))
            .thenReturn(List.of(new ApiClientWithScore("a1", "GET", "/api/x", "x.ts", "Home", "/fe", 0.8)));

        List<SearchResultItem> items = service.search("首页", List.of("/fe", "/be"), 10);

        assertThat(items).hasSize(3);
        assertThat(items).extracting(SearchResultItem::getNodeType)
            .containsExactlyInAnyOrder("Component", "FrontendRoute", "ApiClient");
    }

    @Test
    @DisplayName("查询为空或范围为空时返回空")
    void search_blankQueryOrEmptyScope_returnsEmpty() {
        assertThat(service.search("", List.of("/fe"), 10)).isEmpty();
        assertThat(service.search("x", List.of(), 10)).isEmpty();
    }

    @Test
    @DisplayName("某类节点检索抛异常不影响其余类别")
    void search_oneTypeThrows_othersSurvive() {
        when(embeddingService.generateEmbedding(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(componentNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                anyList(), anyList(), anyDouble(), anyInt()))
            .thenThrow(new RuntimeException("idx missing"));
        when(frontendRouteNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                anyList(), anyList(), anyDouble(), anyInt()))
            .thenReturn(List.of(new RouteWithScore("r1", "/home", "Home", "/fe", 0.85)));
        when(apiClientNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                anyList(), anyList(), anyDouble(), anyInt()))
            .thenReturn(List.of());

        List<SearchResultItem> items = service.search("首页", List.of("/fe"), 10);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getNodeType()).isEqualTo("FrontendRoute");
    }
}
