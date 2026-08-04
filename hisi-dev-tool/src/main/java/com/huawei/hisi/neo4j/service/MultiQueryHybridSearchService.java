package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.config.SearchIntentProperties;
import com.huawei.hisi.neo4j.model.*;
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
    private static final int DEFAULT_LIMIT = 10;

    private final HybridSearchService hybridSearchService;
    private final QueryDecomposer queryDecomposer;
    private final SearchIntentProperties intentProperties;

    public MultiQueryHybridSearchService(
            HybridSearchService hybridSearchService,
            @Autowired(required = false) QueryDecomposer queryDecomposer,
            SearchIntentProperties intentProperties) {
        this.hybridSearchService = hybridSearchService;
        this.queryDecomposer = queryDecomposer;
        this.intentProperties = intentProperties;
    }

    /**
     * 多路召回 + 加权意图感知 RRF 融合搜索。
     * 将查询分词为多个带意图类型的子查询，分别搜索后通过加权 RRF 聚合排序。
     * 当 QueryDecomposer 不可用或只有1个子查询时，退化为单次搜索。
     */
    public SearchResult multiQuerySearch(String query, String projectPath,
                                          List<String> projectPaths, String language,
                                          Integer limit, Integer graphDepth) {
        int effectiveLimit = limit != null ? limit : DEFAULT_LIMIT;

        // 分词：如果 decomposer 不可用，直接单次搜索
        List<SubQuery> subQueries = decomposeQuery(query);
        if (subQueries.size() <= 1) {
            log.debug("Single query path (decomposer unavailable or single sub-query): {}", query);
            SearchResult result = hybridSearchService.hybridSearch(
                    query, projectPath, projectPaths, language, effectiveLimit, graphDepth);
            result.setSubQueries(List.of(query));
            return result;
        }

        log.info("Multi-query intent-aware search: {} sub-queries from original '{}'", subQueries.size(), query);

        // 展开低置信度双通道冗余
        List<SubQuery> expandedQueries = expandDualChannels(subQueries);

        // 多路召回：每个子查询只做纯向量+关键词检索（graphDepth=0），不做图扩展
        long totalStart = System.currentTimeMillis();
        List<SearchResult> allResults = new ArrayList<>();
        List<SubQuery> effectiveSubQueries = new ArrayList<>();

        for (SubQuery sq : expandedQueries) {
            try {
                SearchResult sr = hybridSearchService.hybridSearch(
                        sq.query(), projectPath, projectPaths, language, effectiveLimit, 0);
                allResults.add(sr);
                effectiveSubQueries.add(sq);
            } catch (Exception e) {
                log.warn("Sub-query '{}' failed: {}", sq.query(), e.getMessage());
            }
        }

        if (allResults.isEmpty()) {
            log.warn("All sub-queries failed, falling back to single query");
            SearchResult result = hybridSearchService.hybridSearch(
                    query, projectPath, projectPaths, language, effectiveLimit, 0);
            result.setSubQueries(subQueries.stream().map(SubQuery::query).collect(Collectors.toList()));
            return result;
        }

        // 加权意图感知 RRF 融合
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, MethodNode> nodeMap = new LinkedHashMap<>();
        Map<String, SearchResultItem> itemMap = new LinkedHashMap<>();
        Map<String, List<String>> matchedQueriesMap = new LinkedHashMap<>();
        Map<String, List<IntentType>> intentTypesMap = new LinkedHashMap<>();
        // Track max effective weight per node for normalization
        Map<String, Double> maxEffWeightMap = new LinkedHashMap<>();

        int rrfK = intentProperties.getRrfK();

        for (int qi = 0; qi < allResults.size(); qi++) {
            SubQuery sq = effectiveSubQueries.get(qi);
            double wEff = intentProperties.effectiveWeight(sq.intentType());

            SearchResult sr = allResults.get(qi);

            // 必选词过滤（专用通道）
            List<MethodNode> results = sr.getResults();
            if (results != null && intentProperties.isRequiredWordFilterEnabled()
                    && sq.intentType() != IntentType.GENERAL && sq.intentType() != IntentType.KEYWORD_SUPPLEMENT) {
                List<String> requiredWords = hybridSearchService.extractCoreNouns(sq.query());
                if (!requiredWords.isEmpty()) {
                    results = results.stream()
                            .filter(node -> hybridSearchService.matchesAnyRequiredWord(node, requiredWords))
                            .collect(Collectors.toList());
                    if (results.size() != sr.getResults().size()) {
                        log.debug("[REQUIRED-WORD-FILTER] Sub-query '{}' filtered {}/{} results, required words={}",
                                sq.query(), sr.getResults().size() - results.size(),
                                sr.getResults().size(), requiredWords);
                    }
                }
            }

            // RRF 融合（加权）
            if (results != null) {
                for (int rank = 0; rank < results.size(); rank++) {
                    MethodNode node = results.get(rank);
                    String nodeId = node.getNodeId();
                    if (nodeId == null) continue;

                    // w_eff × 1/(K + rank)
                    double contribution = wEff / (rrfK + rank + 1);
                    rrfScores.merge(nodeId, contribution, Double::sum);
                    nodeMap.putIfAbsent(nodeId, node);
                    matchedQueriesMap.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(sq.query());
                    intentTypesMap.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(sq.intentType());
                    maxEffWeightMap.merge(nodeId, wEff, Double::max);
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

        // Post-filter 注解加分
        applyAnnotationBonus(rrfScores, nodeMap, intentTypesMap);

        // 多路命中归一化：cap = max(w_eff) × maxMultiHitRatio
        normalizeMultiHitScores(rrfScores, maxEffWeightMap);

        // Callee 权重传播
        applyCalleePropagation(rrfScores, nodeMap);

        // 按 RRF 得分降序排序
        List<Map.Entry<String, Double>> sorted = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        List<MethodNode> fusedResults = sorted.stream()
                .map(e -> nodeMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<SearchResultItem> fusedItems = sorted.stream()
                .map(e -> {
                    SearchResultItem item = itemMap.get(e.getKey());
                    if (item != null) {
                        item.setSimilarityScore(e.getValue());
                        item.setMatchedSubQueries(matchedQueriesMap.getOrDefault(e.getKey(), Collections.emptyList()));
                        item.setIntentTypes(intentTypesMap.getOrDefault(e.getKey(), Collections.emptyList()));
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

        List<String> subQueryStrings = subQueries.stream()
                .map(SubQuery::query)
                .collect(Collectors.toList());

        log.info("[Intent-Aware-RRF] {} sub-queries ({} expanded) → {} unique nodes, " +
                        "top-3 scores: {}, cost={}ms",
                subQueries.size(), expandedQueries.size(), fusedResults.size(),
                sorted.stream().limit(3)
                        .map(e -> String.format("%.4f", e.getValue()))
                        .collect(Collectors.joining(", ")),
                totalCostMs);

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
                .subQueries(subQueryStrings)
                .rrfScores(finalRrfScores)
                .build();
    }

    /**
     * 展开低置信度双通道冗余：confidence < threshold 的非 GENERAL 子查询
     * 同时走 GENERAL 通道作为兜底。
     */
    private List<SubQuery> expandDualChannels(List<SubQuery> subQueries) {
        List<SubQuery> expanded = new ArrayList<>();
        double threshold = intentProperties.getDualChannelThreshold();

        for (SubQuery sq : subQueries) {
            expanded.add(sq);
            if (sq.needsDualChannel()) {
                SubQuery generalFallback = new SubQuery(sq.query(), IntentType.GENERAL, 1.0);
                expanded.add(generalFallback);
                log.debug("[DUAL-CHANNEL] Low confidence ({}) for '{}' (type={}), adding GENERAL fallback",
                        String.format("%.2f", sq.confidence()), sq.query(), sq.intentType());
            }
        }
        return expanded;
    }

    /**
     * Post-filter 注解加分：对 SCHEDULE/LISTENER 等专用意图类型的子查询结果，
     * 如果方法节点包含关联注解（如 @Scheduled），给予额外加分。
     * 只做加分不排除，避免丢失精准向量结果。
     */
    private void applyAnnotationBonus(Map<String, Double> rrfScores,
                                       Map<String, MethodNode> nodeMap,
                                       Map<String, List<IntentType>> intentTypesMap) {
        double bonusScore = intentProperties.getAnnotationBonusScore();
        if (bonusScore <= 0) return;

        // 收集所有需要注解检查的 intentType
        Set<IntentType> typesNeedingAnnotation = Arrays.stream(IntentType.values())
                .filter(t -> t.associatedAnnotations().length > 0)
                .collect(Collectors.toSet());

        // 收集需要检查的 nodeId（只检查命中了专用意图类型的节点）
        Set<String> nodesToCheck = new HashSet<>();
        for (Map.Entry<String, List<IntentType>> entry : intentTypesMap.entrySet()) {
            for (IntentType it : entry.getValue()) {
                if (typesNeedingAnnotation.contains(it)) {
                    nodesToCheck.add(entry.getKey());
                    break;
                }
            }
        }

        if (nodesToCheck.isEmpty()) return;

        List<String> nodeList = new ArrayList<>(nodesToCheck);

        for (IntentType intentType : typesNeedingAnnotation) {
            String[] annotations = intentType.associatedAnnotations();
            try {
                Map<String, Set<String>> matched = hybridSearchService.batchCheckAnnotations(nodeList, annotations);
                for (Map.Entry<String, Set<String>> match : matched.entrySet()) {
                    String nodeId = match.getKey();
                    if (!match.getValue().isEmpty()) {
                        rrfScores.merge(nodeId, bonusScore, Double::sum);
                        log.debug("[POST-FILTER-BOOST] Node {} matched annotations {} for {}, bonus={}",
                                nodeId, match.getValue(), intentType, bonusScore);
                    }
                }
            } catch (Exception e) {
                log.debug("[POST-FILTER-BOOST] annotation check failed for {}: {}", intentType, e.getMessage());
            }
        }
    }

    /**
     * 多路命中归一化：限制单一节点的总 RRF 得分不超过
     * max(w_eff) × maxMultiHitRatio，防止弱相关多路堆叠超越精准单路命中。
     */
    private void normalizeMultiHitScores(Map<String, Double> rrfScores,
                                          Map<String, Double> maxEffWeightMap) {
        double ratio = intentProperties.getMaxMultiHitRatio();
        if (ratio <= 0) return; // 0 or negative means no normalization

        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            String nodeId = entry.getKey();
            Double maxW = maxEffWeightMap.get(nodeId);
            if (maxW == null) continue;
            double cap = maxW * ratio;
            if (entry.getValue() > cap) {
                log.debug("[MULTI-HIT-NORM] Node {} capped: {} → {} (maxW={}, ratio={})",
                        nodeId, String.format("%.4f", entry.getValue()),
                        String.format("%.4f", cap), String.format("%.2f", maxW), ratio);
                entry.setValue(cap);
            }
        }
    }

    /**
     * Callee 权重传播：高权入口方法的 callee 继承部分权重。
     * 例如 @Scheduled 方法权重高，其调用的 syncReqStatus 方法应继承部分权重。
     */
    private void applyCalleePropagation(Map<String, Double> rrfScores,
                                         Map<String, MethodNode> nodeMap) {
        double propagationRatio = intentProperties.getCalleePropagationRatio();
        if (propagationRatio <= 0) return;

        // 只对高权节点做传播（w_eff > 1.0 的节点）
        List<String> highWeightNodes = rrfScores.entrySet().stream()
                .filter(e -> e.getValue() > 1.0 / (intentProperties.getRrfK() + 1)) // 非最低分
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(20) // 限制传播源数量，避免过度传播
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (highWeightNodes.isEmpty()) return;

        try {
            Map<String, List<String>> calleesMap = hybridSearchService.get1HopCallees(highWeightNodes);
            Map<String, Double> propagatedScores = new HashMap<>();

            for (Map.Entry<String, List<String>> entry : calleesMap.entrySet()) {
                String sourceNodeId = entry.getKey();
                Double sourceScore = rrfScores.get(sourceNodeId);
                if (sourceScore == null || sourceScore <= 0) continue;

                double propagationAmount = sourceScore * propagationRatio;
                for (String calleeId : entry.getValue()) {
                    propagatedScores.merge(calleeId, propagationAmount, Double::sum);
                }
            }

            // 应用传播分数
            for (Map.Entry<String, Double> entry : propagatedScores.entrySet()) {
                String calleeId = entry.getKey();
                rrfScores.merge(calleeId, entry.getValue(), Double::sum);
                // 如果 callee 不在 nodeMap 中，从 repository 加载
                if (!nodeMap.containsKey(calleeId)) {
                    // 跳过，callee 可能不在当前搜索范围内
                    // nodeMap 中没有的话，最终排序会过滤掉
                }
            }

            if (!propagatedScores.isEmpty()) {
                log.debug("[CALLEE-PROP] Propagated weights to {} callee nodes from {} source nodes",
                        propagatedScores.size(), highWeightNodes.size());
            }
        } catch (Exception e) {
            log.debug("[CALLEE-PROP] Failed: {}", e.getMessage());
        }
    }

    private List<SubQuery> decomposeQuery(String query) {
        if (queryDecomposer == null) {
            return List.of(SubQuery.general(query));
        }
        try {
            List<SubQuery> subQueries = queryDecomposer.decompose(query);
            return subQueries.isEmpty() ? List.of(SubQuery.general(query)) : subQueries;
        } catch (Exception e) {
            log.warn("Query decomposition failed, using original query: {}", e.getMessage());
            return List.of(SubQuery.general(query));
        }
    }
}
