package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.SearchResultItem;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository.ApiClientWithScore;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository.ComponentWithScore;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository.RouteWithScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 前端节点语义检索服务。
 *
 * <p>在给定范围（绑定展开后的前后端路径集合）内，对 Component / FrontendRoute / ApiClient
 * 三类前端节点做描述向量检索，映射为统一的 {@link SearchResultItem}（带 nodeType），
 * 使语义检索能够命中并区分前端节点。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FrontendNodeSearchService {

    private static final double SIMILARITY_THRESHOLD = 0.3;
    private static final int TOPK_MULTIPLIER = 20;

    private final EmbeddingService embeddingService;
    private final Neo4jComponentNodeRepository componentNodeRepository;
    private final Neo4jFrontendRouteNodeRepository frontendRouteNodeRepository;
    private final Neo4jApiClientNodeRepository apiClientNodeRepository;

    /**
     * 前端节点语义检索。
     *
     * @param query 查询文本
     * @param projectPaths 检索范围（已做绑定展开）
     * @param limit 每类节点返回上限
     * @return 前端节点命中项列表（nodeType=Component/FrontendRoute/ApiClient）
     */
    public List<SearchResultItem> search(String query, List<String> projectPaths, int limit) {
        if (query == null || query.isBlank() || projectPaths == null || projectPaths.isEmpty()) {
            return List.of();
        }

        List<Double> embedding = toEmbeddingList(embeddingService.generateEmbedding(query));
        int topK = limit * TOPK_MULTIPLIER;

        List<SearchResultItem> items = new ArrayList<>();
        items.addAll(searchComponents(projectPaths, embedding, topK));
        items.addAll(searchRoutes(projectPaths, embedding, topK));
        items.addAll(searchApiClients(projectPaths, embedding, topK));
        return items;
    }

    private List<SearchResultItem> searchComponents(List<String> projectPaths, List<Double> embedding, int topK) {
        List<SearchResultItem> items = new ArrayList<>();
        try {
            for (ComponentWithScore hit : componentNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                    projectPaths, embedding, SIMILARITY_THRESHOLD, topK)) {
                items.add(SearchResultItem.builder()
                        .nodeId(hit.componentId())
                        .nodeType("Component")
                        .componentName(hit.componentName())
                        .filePath(hit.filePath())
                        .description(hit.description())
                        .similarityScore(hit.score())
                        .build());
            }
        } catch (Exception e) {
            log.debug("[FrontendNodeSearch] Component 检索失败: {}", e.getMessage());
        }
        return items;
    }

    private List<SearchResultItem> searchRoutes(List<String> projectPaths, List<Double> embedding, int topK) {
        List<SearchResultItem> items = new ArrayList<>();
        try {
            for (RouteWithScore hit : frontendRouteNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                    projectPaths, embedding, SIMILARITY_THRESHOLD, topK)) {
                items.add(SearchResultItem.builder()
                        .nodeId(hit.frontendRouteId())
                        .nodeType("FrontendRoute")
                        .routePath(hit.path())
                        .componentName(hit.componentName())
                        .similarityScore(hit.score())
                        .build());
            }
        } catch (Exception e) {
            log.debug("[FrontendNodeSearch] FrontendRoute 检索失败: {}", e.getMessage());
        }
        return items;
    }

    private List<SearchResultItem> searchApiClients(List<String> projectPaths, List<Double> embedding, int topK) {
        List<SearchResultItem> items = new ArrayList<>();
        try {
            for (ApiClientWithScore hit : apiClientNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                    projectPaths, embedding, SIMILARITY_THRESHOLD, topK)) {
                items.add(SearchResultItem.builder()
                        .nodeId(hit.apiClientId())
                        .nodeType("ApiClient")
                        .url(hit.url())
                        .componentName(hit.componentName())
                        .filePath(hit.sourceFile())
                        .description(hit.method() + " " + hit.url())
                        .similarityScore(hit.score())
                        .build());
            }
        } catch (Exception e) {
            log.debug("[FrontendNodeSearch] ApiClient 检索失败: {}", e.getMessage());
        }
        return items;
    }

    private List<Double> toEmbeddingList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add((double) v);
        }
        return list;
    }
}
