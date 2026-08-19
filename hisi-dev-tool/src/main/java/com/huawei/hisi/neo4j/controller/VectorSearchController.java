package com.huawei.hisi.neo4j.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.model.SearchErrorCode;
import com.huawei.hisi.neo4j.model.SearchException;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import com.huawei.hisi.neo4j.service.HybridSearchService;
import com.huawei.hisi.neo4j.service.MultiQueryHybridSearchService;
import com.huawei.hisi.utils.PathUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量搜索控制器
 * 提供混合检索API接口
 */
@RestController
@RequestMapping("/api/vector-search")
public class VectorSearchController {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchController.class);

    /**
     * 最小查询长度
     */
    private static final int MIN_QUERY_LENGTH = 2;

    private final HybridSearchService hybridSearchService;
    private final MultiQueryHybridSearchService multiQueryHybridSearchService;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final EmbeddingService embeddingService;

    public VectorSearchController(
            HybridSearchService hybridSearchService,
            MultiQueryHybridSearchService multiQueryHybridSearchService,
            Neo4jMethodNodeRepository methodNodeRepository,
            EmbeddingService embeddingService) {
        this.hybridSearchService = hybridSearchService;
        this.multiQueryHybridSearchService = multiQueryHybridSearchService;
        this.methodNodeRepository = methodNodeRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * 执行混合检索（旧版，不含多路召回+RRF）
     *
     * @deprecated 使用 {@link #searchV2(SearchRequest)} 替代，支持分词多路召回和 RRF 融合
     * @param request 搜索请求
     * @return 搜索结果
     */
    @Deprecated
    @PostMapping
    public ResponseEntity<ApiResponse<SearchResult>> search(@Valid @RequestBody SearchRequest request) {
        // 路径规范化：统一转为正斜杠形式（项目约定）。
        // 防止 Windows 反斜杠路径与正斜杠路径在 Neo4j 中作为两份独立数据匹配。
        String normalizedProjectPath = PathUtils.normalize(request.getProjectPath());
        List<String> normalizedProjectPaths;
        if (request.getProjectPaths() != null && !request.getProjectPaths().isEmpty()) {
            normalizedProjectPaths = new ArrayList<>(request.getProjectPaths().size());
            for (String p : request.getProjectPaths()) {
                String n = PathUtils.normalize(p);
                if (n != null && !n.isBlank()) {
                    normalizedProjectPaths.add(n);
                }
            }
        } else {
            normalizedProjectPaths = null;
        }

        log.info("收到搜索请求: query={}, projectPath={}, projectPaths={}, language={}, limit={}, graphDepth={}",
                request.getQuery(), normalizedProjectPath, normalizedProjectPaths, request.getLanguage(),
                request.getLimit(), request.getGraphDepth());

        // 1. 查询长度校验
        if (request.getQuery() == null || request.getQuery().trim().length() < MIN_QUERY_LENGTH) {
            return buildErrorResponse(SearchErrorCode.QUERY_TOO_SHORT);
        }

        try {
            // 2. 解析项目路径列表（已规范化）
            List<String> paths = normalizedProjectPaths != null && !normalizedProjectPaths.isEmpty()
                    ? normalizedProjectPaths
                    : normalizedProjectPath != null && !normalizedProjectPath.isBlank()
                            ? List.of(normalizedProjectPath)
                            : List.of();

            // 3. 执行搜索
            SearchResult result = hybridSearchService.hybridSearch(
                    request.getQuery(),
                    normalizedProjectPath,
                    paths,
                    request.getLanguage(),
                    request.getLimit(),
                    request.getGraphDepth(),
                    request.getSearchType()
            );

            log.info("搜索完成: totalCount={}, costTimeMs={}",
                    result.getTotalCount(), result.getCostTimeMs());

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (SearchException e) {
            // 3. 搜索异常处理（包含 EMBEDDING_SERVICE_UNAVAILABLE, GRAPH_SERVICE_ERROR 等错误码）
            log.warn("搜索异常: errorCode={}, message={}", e.getErrorCode(), e.getMessage());
            return buildErrorResponse(e.getErrorCode());

        } catch (IllegalArgumentException e) {
            // 4. 参数异常处理
            log.warn("搜索请求参数无效: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, e.getMessage()));

        } catch (Exception e) {
            // 5. 其他未知异常
            log.error("搜索失败: {}", e.getMessage(), e);
            return buildErrorResponse(SearchErrorCode.UNKNOWN_ERROR);
        }
    }

    /**
     * 构建错误响应
     */
    private ResponseEntity<ApiResponse<SearchResult>> buildErrorResponse(SearchErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getUserMessage()));
    }

    /**
     * 多路召回 + RRF 融合混合检索（v2）
     * 将查询分词为多个子查询，分别搜索后通过 RRF 聚合排序。
     * 返回的 SearchResult 额外包含 subQueries 和 rrfScores 字段。
     *
     * @param request 搜索请求（与 v1 相同的 DTO）
     * @return 带分词信息和 RRF 分数的搜索结果
     */
    @PostMapping("/v2")
    public ResponseEntity<ApiResponse<SearchResult>> searchV2(@Valid @RequestBody SearchRequest request) {
        String normalizedProjectPath = PathUtils.normalize(request.getProjectPath());
        List<String> normalizedProjectPaths;
        if (request.getProjectPaths() != null && !request.getProjectPaths().isEmpty()) {
            normalizedProjectPaths = new ArrayList<>(request.getProjectPaths().size());
            for (String p : request.getProjectPaths()) {
                String n = PathUtils.normalize(p);
                if (n != null && !n.isBlank()) {
                    normalizedProjectPaths.add(n);
                }
            }
        } else {
            normalizedProjectPaths = null;
        }

        log.info("收到 v2 搜索请求: query={}, projectPath={}, projectPaths={}, language={}, limit={}, graphDepth={}",
                request.getQuery(), normalizedProjectPath, normalizedProjectPaths, request.getLanguage(),
                request.getLimit(), request.getGraphDepth());

        if (request.getQuery() == null || request.getQuery().trim().length() < MIN_QUERY_LENGTH) {
            return buildErrorResponse(SearchErrorCode.QUERY_TOO_SHORT);
        }

        try {
            List<String> paths = normalizedProjectPaths != null && !normalizedProjectPaths.isEmpty()
                    ? normalizedProjectPaths
                    : normalizedProjectPath != null && !normalizedProjectPath.isBlank()
                            ? List.of(normalizedProjectPath)
                            : List.of();

            SearchResult result = multiQueryHybridSearchService.multiQuerySearch(
                    request.getQuery(),
                    normalizedProjectPath,
                    paths,
                    request.getLanguage(),
                    request.getLimit(),
                    request.getGraphDepth()
            );

            log.info("v2 搜索完成: totalCount={}, subQueries={}, costTimeMs={}",
                    result.getTotalCount(),
                    result.getSubQueries() != null ? result.getSubQueries().size() : 0,
                    result.getCostTimeMs());

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (SearchException e) {
            log.warn("v2 搜索异常: errorCode={}, message={}", e.getErrorCode(), e.getMessage());
            return buildErrorResponse(e.getErrorCode());

        } catch (IllegalArgumentException e) {
            log.warn("v2 搜索请求参数无效: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, e.getMessage()));

        } catch (Exception e) {
            log.error("v2 搜索失败: {}", e.getMessage(), e);
            return buildErrorResponse(SearchErrorCode.UNKNOWN_ERROR);
        }
    }

    /**
     * 向量索引诊断接口
     * 用于排查向量搜索不返回结果的问题
     */
    @GetMapping("/diagnose/{projectPath}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnose(
            @PathVariable String projectPath,
            @RequestParam(required = false) String testQuery) {
        // 路径规范化：统一为正斜杠形式（项目约定）
        projectPath = PathUtils.normalize(projectPath);
        try {
            Map<String, Object> diagnosis = new HashMap<>();

            // 1. 基本统计
            long totalMethods = methodNodeRepository.countByProjectPath(projectPath);
            long withDescriptionEmbedding = methodNodeRepository.countByProjectPathWithDescriptionEmbedding(projectPath);
            long withCodeEmbedding = methodNodeRepository.countWithCodeEmbedding(projectPath);
            diagnosis.put("totalMethods", totalMethods);
            diagnosis.put("withDescriptionEmbedding", withDescriptionEmbedding);
            diagnosis.put("withCodeEmbedding", withCodeEmbedding);

            // 2. 向量维度检查
            List<Map<String, Object>> dimensions = methodNodeRepository.diagnosticCheckVectorDimensions(projectPath);
            // Unwrap nested "info" map projection for API response
            List<Map<String, Object>> unwrappedDimensions = dimensions.stream()
                    .map(dim -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> info = dim.get("info") instanceof Map ? (Map<String, Object>) dim.get("info") : dim;
                        return info;
                    })
                    .toList();
            diagnosis.put("vectorDimensions", unwrappedDimensions);

            // 3. 索引配置检查
            List<Map<String, Object>> indexes = methodNodeRepository.diagnosticCheckVectorIndexes();
            List<Map<String, Object>> unwrappedIndexes = indexes.stream()
                    .map(idx -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> info = idx.get("info") instanceof Map ? (Map<String, Object>) idx.get("info") : idx;
                        return info;
                    })
                    .toList();
            diagnosis.put("vectorIndexes", unwrappedIndexes);

            // 4. 如果有测试查询，进行查询测试
            if (testQuery != null && !testQuery.isBlank() && withDescriptionEmbedding > 0) {
                // 生成查询向量
                float[] embedding = embeddingService.generateEmbedding(testQuery);
                List<Double> embeddingList = new ArrayList<>(embedding.length);
                for (float v : embedding) {
                    embeddingList.add((double) v);
                }
                diagnosis.put("queryEmbeddingDimension", embedding.length);

                // 直接相似度搜索（不使用索引）
                List<Map<String, Object>> directResults = methodNodeRepository.diagnosticDirectSimilaritySearch(
                        projectPath, embeddingList, 0.0, 10);
                List<Map<String, Object>> unwrappedDirectResults = directResults.stream()
                        .map(row -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> info = row.get("info") instanceof Map ? (Map<String, Object>) row.get("info") : row;
                            return info;
                        })
                        .toList();
                diagnosis.put("directSearchResults", unwrappedDirectResults);

                // 索引搜索（不带阈值）
                List<Map<String, Object>> indexResults = methodNodeRepository.diagnosticTopScoresByDescription(
                        projectPath, embeddingList, 10);
                List<Map<String, Object>> unwrappedIndexResults = indexResults.stream()
                        .map(row -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> info = row.get("info") instanceof Map ? (Map<String, Object>) row.get("info") : row;
                            return info;
                        })
                        .toList();
                diagnosis.put("indexSearchResults", unwrappedIndexResults);
            }

            return ResponseEntity.ok(ApiResponse.success(diagnosis));
        } catch (Exception e) {
            log.error("诊断失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error(500, "诊断失败: " + e.getMessage()));
        }
    }

    /**
     * 搜索请求DTO
     */
    public static class SearchRequest {

        /**
         * 查询文本
         */
        @NotBlank(message = "查询不能为空")
        private String query;

        /**
         * 项目路径
         */
        @NotBlank(message = "项目路径不能为空")
        private String projectPath;

        /**
         * 可选: 返回结果数量限制
         */
        private Integer limit;

        /**
         * 可选: 图遍历深度
         */
        private Integer graphDepth;

        /**
         * 可选: 项目路径列表（多项目跨服务检索）
         * 为空时退化为按 projectPath 单项目检索。
         */
        private List<String> projectPaths;

        /**
         * 可选: 语言过滤（"java" / "python"）
         * 为空时不做语言过滤；旧节点 language=null 会被视为 java 兼容。
         */
        private String language;

        /**
         * 可选: 显式检索类型（METHOD/CLASS/SQL/ENTRY/ALL）
         * 为空时回退到 QueryTypeDetector 自动检测（向后兼容）。
         */
        private String searchType;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public String getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(String projectPath) {
            this.projectPath = projectPath;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public Integer getGraphDepth() {
            return graphDepth;
        }

        public void setGraphDepth(Integer graphDepth) {
            this.graphDepth = graphDepth;
        }

        public List<String> getProjectPaths() {
            return projectPaths;
        }

        public void setProjectPaths(List<String> projectPaths) {
            this.projectPaths = projectPaths;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getSearchType() {
            return searchType;
        }

        public void setSearchType(String searchType) {
            this.searchType = searchType;
        }
    }
}
