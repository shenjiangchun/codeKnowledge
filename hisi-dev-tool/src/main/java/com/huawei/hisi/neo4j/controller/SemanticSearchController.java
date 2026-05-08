package com.huawei.hisi.neo4j.controller;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.model.SearchResultItem;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 语义搜索控制器
 * 提供前端 SemanticSearchView 所需的 API 端点
 *
 * 端点列表:
 * - POST /api/search/semantic  - 语义搜索
 * - GET  /api/search/history   - 搜索历史
 * - GET  /api/search/node/{id} - 获取代码节点详情
 * - GET  /api/search/node/{id}/relations - 获取节点关系
 * - GET  /api/search/suggestions - 搜索建议
 */
@RestController
@RequestMapping("/api/search")
public class SemanticSearchController {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchController.class);

    private final HybridSearchService hybridSearchService;
    private final Neo4jMethodNodeRepository methodNodeRepository;

    /**
     * 内存中的搜索历史（按项目路径分组）
     * 最多保留每个项目最近20条
     */
    private final ConcurrentHashMap<String, LinkedList<String>> searchHistoryMap = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_PER_PROJECT = 20;

    public SemanticSearchController(
            HybridSearchService hybridSearchService,
            Neo4jMethodNodeRepository methodNodeRepository) {
        this.hybridSearchService = hybridSearchService;
        this.methodNodeRepository = methodNodeRepository;
    }

    /**
     * 语义搜索
     * 将前端 SemanticSearchRequest 适配到 HybridSearchService
     */
    @PostMapping("/semantic")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> semanticSearch(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        String projectPath = (String) request.get("projectPath");
        List<String> projectPaths = request.get("projectPaths") != null ? (List<String>) request.get("projectPaths") : null;
        Integer limit = request.get("limit") != null ? ((Number) request.get("limit")).intValue() : 20;
        Double threshold = request.get("threshold") != null ? ((Number) request.get("threshold")).doubleValue() : 0.5;

        // 从 filters 或顶层提取 language 参数
        String language = null;
        if (request.get("filters") instanceof Map) {
            Map<String, Object> filters = (Map<String, Object>) request.get("filters");
            if (filters.get("language") instanceof String lang && !lang.isBlank()) {
                language = lang;
            }
        }
        if (language == null && request.get("language") instanceof String lang && !lang.isBlank()) {
            language = lang;
        }

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "查询不能为空",
                    "results", Collections.emptyList(),
                    "total", 0,
                    "queryTime", 0
            ));
        }

        // 构建要搜索的项目路径列表
        List<String> searchPaths = new ArrayList<>();
        if (projectPaths != null && !projectPaths.isEmpty()) {
            searchPaths.addAll(projectPaths);
        } else if (projectPath != null && !projectPath.trim().isEmpty()) {
            searchPaths.add(projectPath);
        } else {
            List<String> projects = methodNodeRepository.findDistinctProjectPaths();
            if (projects.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "results", Collections.emptyList(),
                        "total", 0,
                        "queryTime", 0,
                        "suggestedQueries", Collections.emptyList()
                ));
            }
            searchPaths.add(projects.get(0));
            log.debug("未指定项目路径，使用默认: {}", searchPaths.get(0));
        }

        log.debug("语义搜索项目范围: {}", searchPaths);
        long startTime = System.currentTimeMillis();

        try {
            // 搜索每个项目并合并结果
            List<Map<String, Object>> allResults = new ArrayList<>();
            int totalCount = 0;

            for (String path : searchPaths) {
                // graphDepth=0：默认不做图遍历扩展，仅返回向量搜索直接命中的结果
                // 前端可通过 graphDepth 参数按需开启（如需查看调用链上下文）
                int graphDepth = request.get("graphDepth") != null ? ((Number) request.get("graphDepth")).intValue() : 0;
                SearchResult searchResult = hybridSearchService.hybridSearch(query, path, searchPaths, language, limit, graphDepth);

                // 构建 nodeId -> similarityScore 映射
                Map<String, Double> scoreMap = new HashMap<>();
                if (searchResult.getItems() != null) {
                    for (SearchResultItem item : searchResult.getItems()) {
                        if (item.getNodeId() != null && item.getSimilarityScore() != null) {
                            scoreMap.put(item.getNodeId(), item.getSimilarityScore());
                        }
                    }
                }

                List<Map<String, Object>> formatted = searchResult.getResults().stream()
                        .map(node -> convertToSemanticResult(node, scoreMap.getOrDefault(node.getNodeId(), 0.0)))
                        .collect(Collectors.toList());
                allResults.addAll(formatted);
                totalCount += searchResult.getTotalCount();
            }

            long queryTime = System.currentTimeMillis() - startTime;

            // 记录搜索历史（使用第一个项目路径）
            recordSearchHistory(searchPaths.get(0), query);

            // 按相关度排序并截取 limit 条
            allResults.sort((a, b) -> {
                double scoreA = a.get("relevanceScore") != null ? ((Number) a.get("relevanceScore")).doubleValue() : 0;
                double scoreB = b.get("relevanceScore") != null ? ((Number) b.get("relevanceScore")).doubleValue() : 0;
                return Double.compare(scoreB, scoreA);
            });
            if (allResults.size() > limit) {
                allResults = allResults.subList(0, limit);
            }

            // 构建响应
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", allResults);
            response.put("total", totalCount);
            response.put("queryTime", queryTime);
            response.put("suggestedQueries", Collections.emptyList());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("语义搜索失败: {}", e.getMessage(), e);
            long queryTime = System.currentTimeMillis() - startTime;
            return ResponseEntity.ok(Map.of(
                    "results", Collections.emptyList(),
                    "total", 0,
                    "queryTime", queryTime,
                    "suggestedQueries", Collections.emptyList()
            ));
        }
    }

    /**
     * 获取搜索历史
     */
    @GetMapping("/history")
    public ResponseEntity<List<String>> getSearchHistory(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String projectPath) {

        // 如果未指定项目路径，合并所有项目的历史
        if (projectPath == null || projectPath.trim().isEmpty()) {
            List<String> allHistory = searchHistoryMap.values().stream()
                    .flatMap(List::stream)
                    .distinct()
                    .limit(limit)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(allHistory);
        }

        LinkedList<String> history = searchHistoryMap.getOrDefault(projectPath, new LinkedList<>());
        return ResponseEntity.ok(history.stream().limit(limit).collect(Collectors.toList()));
    }

    /**
     * 获取代码节点详情
     */
    @GetMapping("/node/{nodeId}")
    public ResponseEntity<Map<String, Object>> getCodeNode(@PathVariable String nodeId) {
        return methodNodeRepository.findByNodeId(nodeId)
                .map(node -> ResponseEntity.ok(convertToSemanticResult(node)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取代码节点的关系（调用者和被调用者）
     */
    @GetMapping("/node/{nodeId}/relations")
    public ResponseEntity<List<Map<String, Object>>> getNodeRelations(@PathVariable String nodeId) {
        List<Map<String, Object>> relations = new ArrayList<>();

        // 获取调用者
        List<MethodNode> callers = methodNodeRepository.findCallers(nodeId);
        for (MethodNode caller : callers) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("sourceId", caller.getNodeId());
            rel.put("targetId", nodeId);
            rel.put("type", "calls");
            rel.put("weight", 1.0);
            relations.add(rel);
        }

        // 获取被调用者
        List<MethodNode> callees = methodNodeRepository.findCallees(nodeId);
        for (MethodNode callee : callees) {
            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("sourceId", nodeId);
            rel.put("targetId", callee.getNodeId());
            rel.put("type", "calls");
            rel.put("weight", 1.0);
            relations.add(rel);
        }

        return ResponseEntity.ok(relations);
    }

    /**
     * 获取搜索建议
     * 基于搜索历史提供前缀匹配建议
     */
    @GetMapping("/suggestions")
    public ResponseEntity<List<String>> getSearchSuggestions(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit) {

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        // 从搜索历史中匹配
        List<String> suggestions = searchHistoryMap.values().stream()
                .flatMap(List::stream)
                .filter(h -> h.contains(query))
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());

        return ResponseEntity.ok(suggestions);
    }

    // ==================== 私有方法 ====================

    /**
     * 将 SearchResultItem（含 similarityScore）转换为前端格式
     */
    private Map<String, Object> convertFromSearchResultItem(SearchResultItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getNodeId());
        result.put("nodeId", item.getNodeId());
        result.put("type", "method");
        result.put("name", item.getMethodName());
        result.put("filePath", item.getFilePath() != null ? item.getFilePath() : "");
        result.put("lineNumber", item.getStartLine() != null ? item.getStartLine() : 0);
        result.put("endLineNumber", item.getEndLine() != null ? item.getEndLine() : 0);
        result.put("codeSnippet", item.getDescription() != null ?
                truncate(item.getDescription(), 500) : "");
        result.put("relevanceScore", item.getSimilarityScore() != null ? item.getSimilarityScore() : 0.0);

        // 元数据
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("className", item.getClassName());
        metadata.put("methodName", item.getMethodName());
        metadata.put("signature", item.getSignature());
        metadata.put("documentation", item.getDescription());
        result.put("metadata", metadata);

        return result;
    }

    /**
     * 将 MethodNode 转换为前端 SemanticSearchResult 格式（带分数）
     */
    private Map<String, Object> convertToSemanticResult(MethodNode node, double relevanceScore) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.getNodeId());
        result.put("nodeId", node.getNodeId());
        result.put("type", "method");
        result.put("name", node.getMethodName());
        result.put("filePath", node.getFilePath() != null ? node.getFilePath() : "");
        result.put("lineNumber", node.getStartLine() != null ? node.getStartLine() : 0);
        result.put("endLineNumber", node.getEndLine() != null ? node.getEndLine() : 0);
        result.put("codeSnippet", node.getMethodBody() != null ?
                truncate(node.getMethodBody(), 500) : "");
        result.put("relevanceScore", relevanceScore);

        // 元数据
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("className", node.getClassName());
        metadata.put("methodName", node.getMethodName());
        metadata.put("signature", node.getSignature());
        metadata.put("documentation", node.getDescription());
        result.put("metadata", metadata);

        return result;
    }

    /**
     * 将 MethodNode 转换为前端 SemanticSearchResult 格式（无分数，用于节点详情查询）
     */
    private Map<String, Object> convertToSemanticResult(MethodNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.getNodeId());
        result.put("nodeId", node.getNodeId());
        result.put("type", "method");
        result.put("name", node.getMethodName());
        result.put("filePath", node.getFilePath() != null ? node.getFilePath() : "");
        result.put("lineNumber", node.getStartLine() != null ? node.getStartLine() : 0);
        result.put("endLineNumber", node.getEndLine() != null ? node.getEndLine() : 0);
        result.put("codeSnippet", node.getMethodBody() != null ?
                truncate(node.getMethodBody(), 500) : "");
        result.put("relevanceScore", 0.0);

        // 元数据
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("className", node.getClassName());
        metadata.put("methodName", node.getMethodName());
        metadata.put("signature", node.getSignature());
        metadata.put("documentation", node.getDescription());
        result.put("metadata", metadata);

        return result;
    }

    /**
     * 记录搜索历史
     */
    private void recordSearchHistory(String projectPath, String query) {
        searchHistoryMap.compute(projectPath, (key, history) -> {
            if (history == null) {
                history = new LinkedList<>();
            }
            // 去重：如果已存在则移到最前
            history.remove(query);
            history.addFirst(query);
            // 限制历史数量
            while (history.size() > MAX_HISTORY_PER_PROJECT) {
                history.removeLast();
            }
            return history;
        });
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
