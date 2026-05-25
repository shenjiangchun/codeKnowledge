package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.model.SearchResultItem;
import com.huawei.hisi.ram.nodes.impact.QueryDecomposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MultiQueryHybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryHybridSearchService.class);
    private static final int RRF_K = 60;
    private static final int DEFAULT_LIMIT = 10;

    private final HybridSearchService hybridSearchService;
    private final QueryDecomposer queryDecomposer;

    public MultiQueryHybridSearchService(
            HybridSearchService hybridSearchService,
            @Autowired(required = false) QueryDecomposer queryDecomposer) {
        this.hybridSearchService = hybridSearchService;
        this.queryDecomposer = queryDecomposer;
    }

    /**
     * 多路召回 + RRF 融合搜索。
     * 将查询分词为多个子查询，分别搜索后通过 RRF 聚合排序。
     * 当 QueryDecomposer 不可用或只有1个子查询时，退化为单次搜索。
     */
    public SearchResult multiQuerySearch(String query, String projectPath,
                                          List<String> projectPaths, String language,
                                          Integer limit, Integer graphDepth) {
        int effectiveLimit = limit != null ? limit : DEFAULT_LIMIT;

        // 分词：如果 decomposer 不可用，直接单次搜索
        List<String> subQueries = decomposeQuery(query);
        if (subQueries.size() <= 1) {
            log.debug("Single query path (decomposer unavailable or single sub-query): {}", query);
            SearchResult result = hybridSearchService.hybridSearch(
                    query, projectPath, projectPaths, language, effectiveLimit, graphDepth);
            result.setSubQueries(List.of(query));
            return result;
        }

        log.info("Multi-query search: {} sub-queries from original '{}'", subQueries.size(), query);

        // 多路召回
        long totalStart = System.currentTimeMillis();
        List<SearchResult> allResults = new ArrayList<>();
        for (String subQuery : subQueries) {
            try {
                SearchResult sr = hybridSearchService.hybridSearch(
                        subQuery, projectPath, projectPaths, language, effectiveLimit, graphDepth);
                allResults.add(sr);
            } catch (Exception e) {
                log.warn("Sub-query '{}' failed: {}", subQuery, e.getMessage());
            }
        }

        if (allResults.isEmpty()) {
            log.warn("All sub-queries failed, falling back to single query");
            SearchResult result = hybridSearchService.hybridSearch(
                    query, projectPath, projectPaths, language, effectiveLimit, graphDepth);
            result.setSubQueries(subQueries);
            return result;
        }

        // RRF 融合
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, MethodNode> nodeMap = new LinkedHashMap<>();
        Map<String, SearchResultItem> itemMap = new LinkedHashMap<>();

        for (SearchResult sr : allResults) {
            // 融合 results (MethodNode)
            List<MethodNode> results = sr.getResults();
            if (results != null) {
                for (int rank = 0; rank < results.size(); rank++) {
                    MethodNode node = results.get(rank);
                    String nodeId = node.getNodeId();
                    if (nodeId == null) continue;
                    double contribution = 1.0 / (RRF_K + rank + 1);
                    rrfScores.merge(nodeId, contribution, Double::sum);
                    nodeMap.putIfAbsent(nodeId, node);
                }
            }

            // 融合 items (SearchResultItem)
            List<SearchResultItem> items = sr.getItems();
            if (items != null) {
                for (SearchResultItem item : items) {
                    if (item.getNodeId() != null) {
                        itemMap.putIfAbsent(item.getNodeId(), item);
                    }
                }
            }
        }

        // 按 RRF 得分降序排序，取 top-K
        List<Map.Entry<String, Double>> sorted = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(effectiveLimit)
                .collect(Collectors.toList());

        List<MethodNode> fusedResults = sorted.stream()
                .map(e -> nodeMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<SearchResultItem> fusedItems = sorted.stream()
                .map(e -> {
                    SearchResultItem item = itemMap.get(e.getKey());
                    if (item != null) {
                        // 用 RRF 分数替换原始 similarityScore
                        item.setSimilarityScore(e.getValue());
                    }
                    return item;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<String, Double> finalRrfScores = sorted.stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));

        long totalCostMs = System.currentTimeMillis() - totalStart;

        // 从第一个成功的结果中取 intent / queryType / searchTips / suggestions
        SearchResult first = allResults.get(0);

        return SearchResult.builder()
                .query(query)
                .intent(first.getIntent())
                .queryType(first.getQueryType())
                .results(fusedResults)
                .items(fusedItems)
                .totalCount(fusedResults.size())
                .costTimeMs(totalCostMs)
                .searchTips(first.getSearchTips())
                .suggestions(first.getSuggestions())
                .subQueries(subQueries)
                .rrfScores(finalRrfScores)
                .build();
    }

    private List<String> decomposeQuery(String query) {
        if (queryDecomposer == null) {
            return List.of(query);
        }
        try {
            List<String> subQueries = queryDecomposer.decompose(query);
            return subQueries.isEmpty() ? List.of(query) : subQueries;
        } catch (Exception e) {
            log.warn("Query decomposition failed, using original query: {}", e.getMessage());
            return List.of(query);
        }
    }
}
