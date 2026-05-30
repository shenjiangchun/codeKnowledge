package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.*;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.CalleeWithRelationBySource;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.CallerWithRelationByTarget;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.MethodWithScore;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository.MethodBySqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository.SqlNodeByMethod;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository.SqlWithScore;
import com.huawei.hisi.utils.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 * 实现多策略路由搜索，根据查询类型选择不同的搜索策略
 *
 * 搜索策略路由:
 * - NATURAL_LANGUAGE: descriptionEmbedding 向量检索 + 图扩展
 * - METHOD_NAME: methodName 模糊匹配 + 向量补充 + 图扩展
 * - FULL_QUALIFIED_NAME: className + methodName 精确匹配 + 图扩展
 * - CLASS_NAME: className 精确/模糊匹配 + 图扩展
 * - SQL_SNIPPET: sqlEmbedding 向量检索 -> EXECUTES_SQL 反查
 * - HTTP_URI: entryKey 模糊匹配 -> methodNodeId 关联
 * - CODE_SNIPPET: codeEmbedding 向量检索 + 图扩展
 * - ANNOTATION: methodBody/comment CONTAINS 匹配
 * - EXCEPTION_TYPE: thrownExceptions/caughtExceptions CONTAINS 匹配
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /**
     * RRF常数K
     * 公式: score = 1/(k + rank)
     */
    private static final int RRF_K = 60;

    /**
     * 默认向量检索TopK
     */
    private static final int DEFAULT_TOP_K = 10;

    /**
     * 默认图遍历深度
     */
    private static final int DEFAULT_GRAPH_DEPTH = 2;

    /**
     * 向量相似度阈值（标准）
     */
    private static final double SIMILARITY_THRESHOLD = 0.5;

    /**
     * 向量相似度阈值（放宽条件）
     */
    private static final double RELAXED_SIMILARITY_THRESHOLD = 0.3;

    /**
     * 关联上下文数量限制（调用者/被调用者/入口点/SQL）
     */
    private static final int CONTEXT_LIMIT = 3;

    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jSqlNodeRepository sqlNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointRepository;
    private final EmbeddingService embeddingService;
    private final QueryTypeDetector queryTypeDetector;
    private final Neo4jVectorIndexService vectorIndexService;
    private final QueryEmbeddingCache queryEmbeddingCache;

    public HybridSearchService(
            Neo4jMethodNodeRepository methodNodeRepository,
            Neo4jSqlNodeRepository sqlNodeRepository,
            Neo4jEntryPointNodeRepository entryPointRepository,
            EmbeddingService embeddingService,
            QueryTypeDetector queryTypeDetector,
            Neo4jVectorIndexService vectorIndexService,
            QueryEmbeddingCache queryEmbeddingCache) {
        this.methodNodeRepository = methodNodeRepository;
        this.sqlNodeRepository = sqlNodeRepository;
        this.entryPointRepository = entryPointRepository;
        this.embeddingService = embeddingService;
        this.queryTypeDetector = queryTypeDetector;
        this.vectorIndexService = vectorIndexService;
        this.queryEmbeddingCache = queryEmbeddingCache;
    }

    /**
     * 混合检索入口（使用默认参数）
     *
     * @param query 用户查询文本
     * @param projectPath 项目路径
     * @return 检索结果
     * @throws IllegalArgumentException 如果参数无效
     */
    public SearchResult hybridSearch(String query, String projectPath) {
        return hybridSearch(query, projectPath, DEFAULT_TOP_K, DEFAULT_GRAPH_DEPTH);
    }

    /**
     * 混合检索入口（支持自定义参数）
     *
     * @param query 用户查询文本
     * @param projectPath 项目路径
     * @param limit 返回结果数量限制（null 使用默认值）
     * @param graphDepth 图遍历深度（null 使用默认值）
     * @return 检索结果
     * @throws IllegalArgumentException 如果参数无效
     */
    public SearchResult hybridSearch(String query, String projectPath, Integer limit, Integer graphDepth) {
        return hybridSearch(query, projectPath, null, null, limit, graphDepth);    }

    /**
     * 混合检索入口（多项目路径 + 多语言过滤版本）
     *
     * @param query 用户查询文本
     * @param projectPath 项目路径（必填，用于参数校验和向后兼容）
     * @param projectPaths 项目路径列表（可空；空时退化为 projectPath，单项目场景）
     * @param language 语言过滤："java" / "python" / null（不过滤）；旧节点 null 视为 java 兼容
     * @param limit 返回结果数量限制（null 使用默认值）
     * @param graphDepth 图遍历深度（null 使用默认值）
     * @return 检索结果
     * @throws IllegalArgumentException 如果参数无效
     */
    public SearchResult hybridSearch(String query, String projectPath, List<String> projectPaths, String language,
                                      Integer limit, Integer graphDepth) {
        // 参数校验
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("查询不能为空");
        }
        if (projectPath == null || projectPath.trim().isEmpty()) {
            throw new IllegalArgumentException("项目路径不能为空");
        }

        // projectPaths 解析：未传入时退化为 projectPath（单项目模式）
        final List<String> effectiveProjectPaths = resolveProjectPaths(projectPath, projectPaths);
        // 使用默认值填充可选参数
        int effectiveLimit = limit != null ? limit : DEFAULT_TOP_K;
        int effectiveGraphDepth = graphDepth != null ? graphDepth : DEFAULT_GRAPH_DEPTH;

        long startTime = System.currentTimeMillis();
        QueryType queryType = null;

        try {
            // 1. 查询类型检测
            queryType = queryTypeDetector.detect(query);
            log.debug("查询类型检测: queryType={}", queryType);

            // 2. 根据查询类型执行路由搜索（用 effectiveProjectPaths 作为范围参数）
            VectorSearchResult<MethodNode> searchResult = executeSearchByType(query, queryType, effectiveProjectPaths, effectiveLimit);
            List<MethodNode> methodResults = applyLanguageFilter(searchResult.results(), language);
            Map<String, Double> similarityScores = searchResult.scores();
            log.debug("路由搜索结果: {} 条 (语言过滤后)", methodResults.size());

            // 3.5 精确匹配短路：精确类型且命中时，跳过图扩展/RRF 融合，直接返回命中结果
            //     避免无关上下文（2-hop callers/callees）污染精确结果
            boolean exactShortCircuit = isExactMatchType(queryType) && !methodResults.isEmpty();

            // 4. 图遍历扩展（精确短路时跳过）
            List<MethodNode> graphResults = exactShortCircuit
                    ? Collections.emptyList()
                    : applyLanguageFilter(graphExpansion(methodResults, effectiveProjectPaths.isEmpty() ? "" : effectiveProjectPaths.get(0), effectiveGraphDepth), language);
            log.debug("图遍历扩展结果: {} 条 (exactShortCircuit={})", graphResults.size(), exactShortCircuit);

            // 5. RRF融合（精确短路时直接用 methodResults）
            List<MethodNode> fusedResults = exactShortCircuit
                    ? new ArrayList<>(methodResults)
                    : fuseResults(methodResults, graphResults);

            // 6. 构建增强搜索结果项
            List<SearchResultItem> items = buildSearchResultItems(fusedResults, effectiveProjectPaths, similarityScores);

            // 6. 构建结果
            long costTime = System.currentTimeMillis() - startTime;
            log.info("混合检索完成，耗时: {}ms, 结果数量: {}", costTime, fusedResults.size());

            // 7. 无结果场景处理
            String searchTips = null;
            List<String> suggestions = Collections.emptyList();
            if (fusedResults.isEmpty()) {
                // 放宽条件重试
                VectorSearchResult<MethodNode> relaxedResult = executeRelaxedSearchWithScores(query, queryType, effectiveProjectPaths, effectiveLimit);
                fusedResults = applyLanguageFilter(relaxedResult.results(), language);
                similarityScores = relaxedResult.scores();
                items = buildSearchResultItems(fusedResults, effectiveProjectPaths, similarityScores);

                if (fusedResults.isEmpty()) {
                    searchTips = generateSearchTips(query, queryType);
                    suggestions = generateSuggestions(query, queryType);
                }
            }

            SearchResult.SearchResultBuilder builder = SearchResult.builder()
                    .query(query)
                    .intent(QueryIntent.builder().keywords(List.of(query.split("\\s+"))).build())
                    .queryType(queryType)
                    .results(fusedResults)
                    .items(items)
                    .totalCount(fusedResults.size())
                    .costTimeMs(costTime)
                    .suggestions(suggestions);

            if (searchTips != null) {
                builder.searchTips(searchTips);
            }

            return builder.build();

        } catch (SearchException e) {
            // 重新抛出 SearchException，让控制器处理
            throw e;
        } catch (Exception e) {
            log.error("混合检索失败: {}", e.getMessage(), e);
            return SearchResult.builder()
                    .query(query)
                    .queryType(queryType != null ? queryType : QueryType.NATURAL_LANGUAGE)
                    .results(Collections.emptyList())
                    .items(Collections.emptyList())
                    .totalCount(0)
                    .costTimeMs(System.currentTimeMillis() - startTime)
                    .searchTips("搜索服务异常: " + e.getMessage())
                    .suggestions(Collections.emptyList())
                    .build();
        }
    }

    /**
     * 根据查询类型执行路由搜索（使用默认 limit）
     * 返回包含方法节点列表和相似度分数映射的结果
     */
    private VectorSearchResult<MethodNode> executeSearchByType(String query, QueryType queryType, List<String> projectPaths) {
        return executeSearchByType(query, queryType, projectPaths, DEFAULT_TOP_K);
    }

    /**
     * 根据查询类型执行路由搜索
     * 返回包含方法节点列表和相似度分数映射的结果
     */
    private VectorSearchResult<MethodNode> executeSearchByType(String query, QueryType queryType, List<String> projectPaths, int limit) {
        return switch (queryType) {
            case NATURAL_LANGUAGE -> searchByNaturalLanguageWithScores(query, projectPaths, limit);
            case METHOD_NAME -> searchByMethodNameWithScores(query, projectPaths, limit);
            case FULL_QUALIFIED_NAME -> wrapWithoutScores(searchByFullQualifiedName(query, projectPaths));
            case FULL_QUALIFIED_CLASS_NAME -> wrapWithoutScores(searchByFullQualifiedClassName(query, projectPaths));
            case CLASS_NAME -> wrapWithoutScores(searchByClassName(query, projectPaths));
            case SQL_SNIPPET -> searchBySqlSnippetWithScores(query, projectPaths, limit);
            case HTTP_URI -> wrapWithoutScores(searchByHttpUri(query, projectPaths));
            case CODE_SNIPPET -> searchByCodeSnippetWithScores(query, projectPaths, limit);
            case ANNOTATION -> wrapWithoutScores(searchByAnnotation(query, projectPaths));
            case EXCEPTION_TYPE -> wrapWithoutScores(searchByExceptionType(query, projectPaths));
        };
    }

    /**
     * 判断是否为精确匹配型查询类型。
     * 精确命中时：跳过图扩展/融合/向量补充，直接返回命中结果。
     * 精确未命中时：不短路，继续走原有流程（可能放宽重试）。
     */
    private static boolean isExactMatchType(QueryType type) {
        return type == QueryType.FULL_QUALIFIED_NAME
                || type == QueryType.FULL_QUALIFIED_CLASS_NAME
                || type == QueryType.HTTP_URI
                || type == QueryType.ANNOTATION
                || type == QueryType.EXCEPTION_TYPE;
    }

    /**
     * 将不带分数的结果包装为 VectorSearchResult
     */
    private VectorSearchResult<MethodNode> wrapWithoutScores(List<MethodNode> methods) {
        return new VectorSearchResult<>(methods, Collections.emptyMap());
    }

    /**
     * NATURAL_LANGUAGE 搜索策略 (带分数)
     * descriptionEmbedding 向量检索
     */
    private VectorSearchResult<MethodNode> searchByNaturalLanguageWithScores(String query, List<String> projectPaths, int limit) {
        float[] embedding = getOrGenerateEmbedding(query);
        log.info("[EMBEDDING-DIAG] query='{}', embeddingDim={}, embeddingServiceDim={}, first5={}, useVectorIndex={}, projectPaths={}",
                query, embedding.length, embeddingService.getEmbeddingDimension(),
                java.util.Arrays.toString(java.util.Arrays.copyOf(embedding, Math.min(5, embedding.length))),
                vectorIndexService.isVectorIndexAvailable(), projectPaths);

        boolean useVectorIndex = vectorIndexService.isVectorIndexAvailable();
        Map<String, Double> scoreMap = new HashMap<>();
        List<MethodNode> methods;

        if (useVectorIndex) {
            try {
                List<Double> embeddingList = new ArrayList<>(embedding.length);
                for (float v : embedding) {
                    embeddingList.add((double) v);
                }
                List<MethodWithScore> results = methodNodeRepository.findByDescriptionVectorIndexWithScoreByProjectPaths(
                        projectPaths, embeddingList, SIMILARITY_THRESHOLD, limit);
                if (results.isEmpty()) {
                    String firstPath = projectPaths.isEmpty() ? "" : projectPaths.get(0);
                    long totalInProject = methodNodeRepository.countByProjectPath(firstPath);
                    long withEmbedding = methodNodeRepository.countByProjectPathWithDescriptionEmbedding(firstPath);
                    log.warn("[DEBUG-VECTOR] vector index returned 0 results. projectPaths={}, totalMethodsInProject={}, methodsWithDescriptionEmbedding={}, threshold={}",
                            projectPaths, totalInProject, withEmbedding, SIMILARITY_THRESHOLD);
                    if (withEmbedding > 0) {
                        try {
                            // 检查向量维度
                            log.warn("[DEBUG-VECTOR] ====== 检查向量维度和索引配置 ======");
                            List<Map<String, Object>> dimensions = methodNodeRepository.diagnosticCheckVectorDimensions(firstPath);
                            for (Map<String, Object> dim : dimensions) {
                                log.warn("[DEBUG-VECTOR]   {}#{} dimension={}",
                                        dim.get("className"), dim.get("methodName"), dim.get("dimension"));
                            }
                            // 检查索引配置
                            List<Map<String, Object>> indexes = methodNodeRepository.diagnosticCheckVectorIndexes();
                            for (Map<String, Object> idx : indexes) {
                                log.warn("[DEBUG-VECTOR]   Index: {} type={} options={}",
                                        idx.get("name"), idx.get("type"), idx.get("options"));
                            }
                            // 尝试不使用索引直接搜索
                            log.warn("[DEBUG-VECTOR] ====== 尝试直接相似度搜索（不使用向量索引）======");
                            List<Map<String, Object>> directResults = methodNodeRepository.diagnosticDirectSimilaritySearch(
                                    firstPath, embeddingList, 0.0, Math.max(limit, 10));
                            log.warn("[DEBUG-VECTOR]   Direct search returned {} results", directResults.size());
                            for (int i = 0; i < directResults.size(); i++) {
                                Map<String, Object> row = directResults.get(i);
                                Object desc = row.get("description");
                                String descStr = desc == null ? "<null>" : desc.toString();
                                if (descStr.length() > 120) descStr = descStr.substring(0, 120) + "...";
                                log.warn("[DEBUG-VECTOR]   #{} score={} {}#{} desc={}",
                                        i, row.get("score"), row.get("className"), row.get("methodName"), descStr);
                            }
                            // 尝试不带阈值的索引搜索
                            log.warn("[DEBUG-VECTOR] ====== 尝试不带阈值的索引搜索 ======");
                            List<Map<String, Object>> topScores = methodNodeRepository.diagnosticTopScoresByDescription(firstPath, embeddingList, Math.max(limit, 10));
                            log.warn("[DEBUG-VECTOR]   Index search (no threshold) returned {} results", topScores.size());
                            for (int i = 0; i < topScores.size(); i++) {
                                Map<String, Object> row = topScores.get(i);
                                Object desc = row.get("description");
                                String descStr = desc == null ? "<null>" : desc.toString();
                                if (descStr.length() > 120) descStr = descStr.substring(0, 120) + "...";
                                log.warn("[DEBUG-VECTOR]   #{} score={} {}#{} desc={}",
                                        i, row.get("score"), row.get("className"), row.get("methodName"), descStr);
                            }
                        } catch (Exception diagEx) {
                            log.warn("[DEBUG-VECTOR] diagnostic query failed: {}", diagEx.getMessage(), diagEx);
                        }
                    }
                }
                methods = new ArrayList<>();
                for (MethodWithScore result : results) {
                    methods.add(result.toMethodNode());
                    if (result.score() != null) {
                        scoreMap.put(result.nodeId(), result.score());
                    }
                }
                return new VectorSearchResult<>(methods, scoreMap);
            } catch (SearchException e) {
                throw e;
            } catch (Exception e) {
                log.warn("向量索引查询失败，降级为全表扫描: {}", e.getMessage(), e);
            }
        }

        // 全表扫描降级
        try {
            List<MethodWithScore> results = methodNodeRepository.findByDescriptionVectorSimilarityWithScoreByProjectPaths(
                    projectPaths, embedding, SIMILARITY_THRESHOLD, limit);
            methods = new ArrayList<>();
            for (MethodWithScore result : results) {
                methods.add(result.toMethodNode());
                if (result.score() != null) {
                    scoreMap.put(result.nodeId(), result.score());
                }
            }
            return new VectorSearchResult<>(methods, scoreMap);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DEBUG-VECTOR] fallback findByDescriptionVectorSimilarityWithScore failed", e);
            throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, e);
        }
    }

    /**
     * METHOD_NAME 搜索策略 (带分数)
     * methodName 模糊匹配 + 向量补充
     * 当同时指定 className 和 methodName 时，取交集而非并集
     */
    private VectorSearchResult<MethodNode> searchByMethodNameWithScores(String query, List<String> projectPaths, int limit) {
        String methodName = query;
        String className = null;

        // 检查是否为 ClassName.methodName 格式
        if (query.contains(".")) {
            String[] parts = query.split("\\.");
            if (parts.length >= 2) {
                className = parts[0];
                methodName = parts[1];
            }
        }

        List<MethodNode> results = new ArrayList<>();
        Map<String, Double> scoreMap = new HashMap<>();

        // 方法名模糊匹配
        List<MethodNode> byMethodName = methodNodeRepository.findByProjectPathsAndMethodNameContaining(
                projectPaths, methodName);

        if (className != null && !className.isEmpty()) {
            // 同时指定了类名和方法名，取交集
            List<MethodNode> byClassName = methodNodeRepository.findByProjectPathsAndClassNameContaining(
                    projectPaths, className);
            Set<String> classNameNodeIds = byClassName.stream()
                    .map(MethodNode::getNodeId)
                    .collect(Collectors.toSet());
            results.addAll(byMethodName.stream()
                    .filter(m -> classNameNodeIds.contains(m.getNodeId()))
                    .collect(Collectors.toList()));
        } else {
            results.addAll(byMethodName);
        }

        // 向量补充（取前5个作为种子）
        if (results.size() < limit) {
            float[] embedding = getOrGenerateEmbedding(query);
            List<MethodWithScore> vectorResults = methodNodeRepository.findByDescriptionVectorSimilarityWithScoreByProjectPaths(
                    projectPaths, embedding, SIMILARITY_THRESHOLD, limit - results.size());
            for (MethodWithScore result : vectorResults) {
                results.add(result.toMethodNode());
                if (result.score() != null) {
                    scoreMap.put(result.nodeId(), result.score());
                }
            }
        }

        return new VectorSearchResult<>(deduplicate(results), scoreMap);
    }

    /**
     * FULL_QUALIFIED_NAME 搜索策略
     * className + methodName 精确匹配
     */
    private List<MethodNode> searchByFullQualifiedName(String query, List<String> projectPaths) {
        // 解析全限定名: com.example.mapper.UserMapper.selectById
        String[] parts = query.split("\\.");
        if (parts.length < 3) {
            return Collections.emptyList();
        }

        String methodName = parts[parts.length - 1];
        String className = parts[parts.length - 2];
        String fullClassName = String.join(".", Arrays.copyOf(parts, parts.length - 1));

        List<MethodNode> results = new ArrayList<>();

        // 尝试精确匹配
        List<MethodNode> exactMatch = methodNodeRepository.findByProjectPathsAndClassNameAndMethodName(
                projectPaths, fullClassName, methodName);
        results.addAll(exactMatch);

        // 如果没找到，尝试类名模糊匹配
        if (results.isEmpty()) {
            List<MethodNode> fuzzyMatch = methodNodeRepository.findByProjectPathsAndClassNameAndMethodName(
                    projectPaths, className, methodName);
            results.addAll(fuzzyMatch);
        }

        return results;
    }

    /**
     * CLASS_NAME 搜索策略
     * className 精确/模糊匹配
     */
    private List<MethodNode> searchByClassName(String query, List<String> projectPaths) {
        // 先尝试精确匹配
        List<MethodNode> exactMatch = methodNodeRepository.findByProjectPathsAndClassName(projectPaths, query);

        if (!exactMatch.isEmpty()) {
            return exactMatch;
        }

        // 模糊匹配
        return methodNodeRepository.findByProjectPathsAndClassNameContaining(projectPaths, query);
    }

    /**
     * FULL_QUALIFIED_CLASS_NAME 搜索策略
     * 全限定类名精确匹配（不做模糊降级，避免跨目录污染）
     * 例如：com.huawei.hisi.agent.controller.DiagnosisController → 该类的所有方法
     */
    private List<MethodNode> searchByFullQualifiedClassName(String query, List<String> projectPaths) {
        return methodNodeRepository.findByProjectPathsAndClassName(projectPaths, query);
    }

    /**
     * 从查询文本中提取有检索价值的关键术语。
     * 规则：长度2-20的中文/英文/驼峰片段，排除常见停用词。
     */
    private List<String> extractSearchKeywords(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        // 按空格/标点拆分，保留有检索价值的片段
        String[] tokens = query.split("[\\s，,。.；;、！!？?（）()\\[\\]【】{}\"'<>《》]+");
        List<String> keywords = new ArrayList<>();
        Set<String> stopWords = Set.of(
                "的", "了", "在", "是", "和", "与", "及", "或", "不", "有", "无",
                "从", "到", "向", "上", "下", "中", "后", "前", "时", "当",
                "the", "a", "an", "is", "are", "was", "and", "or", "not", "in", "on", "at", "to", "for"
        );
        for (String token : tokens) {
            String t = token.trim();
            // 跳过太短/太长/停用词
            if (t.length() < 2 || t.length() > 30) continue;
            if (stopWords.contains(t.toLowerCase())) continue;
            // 保留中文片段(>=2字)、英文/驼峰片段(>=3字符)、混合片段
            if (t.matches(".*[\\u4e00-\\u9fa5].*") || t.length() >= 3) {
                keywords.add(t);
            }
        }
        // 最多取前5个关键词，避免过多查询
        return keywords.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * SQL_SNIPPET 搜索策略 (带分数)
     * sqlEmbedding 向量检索 -> EXECUTES_SQL 批量反查
     */
    private VectorSearchResult<MethodNode> searchBySqlSnippetWithScores(String query, List<String> projectPaths, int limit) {
        List<Double> embedding = getOrGenerateEmbeddingList(query);

        List<SqlWithScore> sqlResults;
        boolean useVectorIndex = vectorIndexService.isVectorIndexAvailable();
        Map<String, Double> sqlScoreMap = new HashMap<>();
        String firstPath = projectPaths.isEmpty() ? "" : projectPaths.get(0);

        if (useVectorIndex) {
            try {
                sqlResults = sqlNodeRepository.findBySqlVectorIndexWithScore(
                        firstPath, embedding, SIMILARITY_THRESHOLD, limit);
            } catch (SearchException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SQL向量索引查询失败，降级为全表扫描: {}", e.getMessage());
                try {
                    sqlResults = sqlNodeRepository.findBySqlVectorSimilarityWithScore(
                            firstPath, embedding, SIMILARITY_THRESHOLD, limit);
                } catch (SearchException se) {
                    throw se;
                } catch (Exception ex) {
                    throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, ex);
                }
            }
        } else {
            try {
                sqlResults = sqlNodeRepository.findBySqlVectorSimilarityWithScore(
                        firstPath, embedding, SIMILARITY_THRESHOLD, limit);
            } catch (SearchException e) {
                throw e;
            } catch (Exception e) {
                throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, e);
            }
        }

        // 记录 SQL 节点的分数
        for (SqlWithScore sqlResult : sqlResults) {
            if (sqlResult.score() != null) {
                sqlScoreMap.put(sqlResult.nodeId(), sqlResult.score());
            }
        }

        if (sqlResults.isEmpty()) {
            return VectorSearchResult.empty();
        }

        // 批量通过 EXECUTES_SQL 关系反查方法（解决 N+1 查询问题）
        List<String> sqlNodeIds = sqlResults.stream()
                .map(SqlWithScore::nodeId)
                .collect(Collectors.toList());
        List<MethodBySqlNode> methodBySqlNodes = sqlNodeRepository.findMethodsBySqlNodeIds(sqlNodeIds);

        // 构建 sqlNodeId -> sqlScore 映射
        Map<String, Double> sqlNodeScoreMap = new HashMap<>();
        for (SqlWithScore sqlResult : sqlResults) {
            if (sqlResult.score() != null) {
                sqlNodeScoreMap.put(sqlResult.nodeId(), sqlResult.score());
            }
        }

        // 根据 sqlNodeId 批量查询 MethodNode
        List<String> methodNodeIds = methodBySqlNodes.stream()
                .map(MethodBySqlNode::methodNodeId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, MethodNode> methodNodeMap = methodNodeRepository.findAllByNodeIds(methodNodeIds).stream()
                .filter(m -> matchesProjectPaths(m, projectPaths))
                .collect(Collectors.toMap(MethodNode::getNodeId, m -> m, (a, b) -> a));

        // 组装结果
        List<MethodNode> results = new ArrayList<>();
        Map<String, Double> methodScoreMap = new HashMap<>();
        Map<String, String> methodToSqlMap = methodBySqlNodes.stream()
                .collect(Collectors.toMap(MethodBySqlNode::methodNodeId, MethodBySqlNode::sqlNodeId, (a, b) -> a));

        for (Map.Entry<String, MethodNode> entry : methodNodeMap.entrySet()) {
            results.add(entry.getValue());
            String sqlNodeId = methodToSqlMap.get(entry.getKey());
            if (sqlNodeId != null && sqlNodeScoreMap.containsKey(sqlNodeId)) {
                methodScoreMap.put(entry.getKey(), sqlNodeScoreMap.get(sqlNodeId));
            }
        }

        return new VectorSearchResult<>(deduplicate(results), methodScoreMap);
    }

    /**
     * HTTP_URI 搜索策略
     * entryKey 模糊匹配 -> 批量关联 methodNodeId
     */
    private List<MethodNode> searchByHttpUri(String query, List<String> projectPaths) {
        // 查找入口点 (use first path for backward compat with entryPointRepository)
        String firstPath = projectPaths.isEmpty() ? "" : projectPaths.get(0);
        List<EntryPointNode> entryPoints = entryPointRepository.findByProjectPathAndEntryKeyContaining(
                firstPath, query);

        if (entryPoints.isEmpty()) {
            return Collections.emptyList();
        }

        // 收集所有 methodNodeId 并批量查询（解决 N+1 查询问题）
        List<String> methodNodeIds = entryPoints.stream()
                .map(EntryPointNode::getMethodNodeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (methodNodeIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, MethodNode> methodMap = methodNodeRepository.findAllByNodeIds(methodNodeIds).stream()
                .filter(m -> matchesProjectPaths(m, projectPaths))
                .collect(Collectors.toMap(MethodNode::getNodeId, m -> m, (a, b) -> a));

        return new ArrayList<>(methodMap.values());
    }

    /**
     * CODE_SNIPPET 搜索策略 (带分数)
     * codeEmbedding 向量检索
     */
    private VectorSearchResult<MethodNode> searchByCodeSnippetWithScores(String query, List<String> projectPaths, int limit) {
        float[] embedding = getOrGenerateEmbedding(query);

        boolean useVectorIndex = vectorIndexService.isVectorIndexAvailable();
        Map<String, Double> scoreMap = new HashMap<>();
        List<MethodNode> methods;

        if (useVectorIndex) {
            try {
                List<Double> embeddingList = new ArrayList<>(embedding.length);
                for (float v : embedding) {
                    embeddingList.add((double) v);
                }
                List<MethodWithScore> results = methodNodeRepository.findByCodeVectorIndexWithScoreByProjectPaths(
                        projectPaths, embeddingList, SIMILARITY_THRESHOLD, limit);
                methods = new ArrayList<>();
                for (MethodWithScore result : results) {
                    methods.add(result.toMethodNode());
                    if (result.score() != null) {
                        scoreMap.put(result.nodeId(), result.score());
                    }
                }
                return new VectorSearchResult<>(methods, scoreMap);
            } catch (SearchException e) {
                throw e;
            } catch (Exception e) {
                log.warn("代码向量索引查询失败，降级为全表扫描: {}", e.getMessage());
            }
        }

        // 全表扫描降级
        try {
            List<MethodWithScore> results = methodNodeRepository.findByCodeVectorSimilarityWithScoreByProjectPaths(
                    projectPaths, embedding, SIMILARITY_THRESHOLD, limit);
            methods = new ArrayList<>();
            for (MethodWithScore result : results) {
                methods.add(result.toMethodNode());
                if (result.score() != null) {
                    scoreMap.put(result.nodeId(), result.score());
                }
            }
            return new VectorSearchResult<>(methods, scoreMap);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, e);
        }
    }

    /**
     * ANNOTATION 搜索策略
     * methodBody/comment CONTAINS 匹配
     */
    private List<MethodNode> searchByAnnotation(String query, List<String> projectPaths) {
        // 移除 @ 符号
        String annotation = query.startsWith("@") ? query.substring(1) : query;
        return methodNodeRepository.findByProjectPathsAndAnnotation(projectPaths, annotation);
    }

    /**
     * EXCEPTION_TYPE 搜索策略
     * thrownExceptions/caughtExceptions CONTAINS 匹配
     */
    private List<MethodNode> searchByExceptionType(String query, List<String> projectPaths) {
        return methodNodeRepository.findByProjectPathsAndExceptionType(projectPaths, query);
    }

    /**
     * 放宽条件重试搜索 (带分数，使用默认 limit)
     * CODE_SNIPPET 降级时用 codeEmbedding，SQL_SNIPPET 降级时用 SQL 向量搜索
     */
    private VectorSearchResult<MethodNode> executeRelaxedSearchWithScores(String query, QueryType queryType, List<String> projectPaths) {
        return executeRelaxedSearchWithScores(query, queryType, projectPaths, DEFAULT_TOP_K);
    }

    /**
     * 放宽条件重试搜索 (带分数)
     * CODE_SNIPPET 降级时用 codeEmbedding，SQL_SNIPPET 降级时用 SQL 向量搜索
     */
    private VectorSearchResult<MethodNode> executeRelaxedSearchWithScores(String query, QueryType queryType, List<String> projectPaths, int limit) {
        log.debug("尝试放宽条件重试搜索...");

        float[] embedding = getOrGenerateEmbedding(query);

        if (queryType == QueryType.NATURAL_LANGUAGE) {
            // 自然语言降级：使用 descriptionEmbedding 降低阈值
            return searchByDescriptionWithRelaxedThreshold(projectPaths, embedding, limit);
        } else if (queryType == QueryType.CODE_SNIPPET) {
            // 代码片段降级：使用 codeEmbedding 降低阈值
            return searchByCodeWithRelaxedThreshold(projectPaths, embedding, limit);
        } else if (queryType == QueryType.SQL_SNIPPET) {
            // SQL片段降级：使用 SQL 向量搜索降低阈值
            return searchBySqlWithRelaxedThreshold(projectPaths, embedding, limit);
        }

        // 尝试关键词搜索
        try {
            List<MethodNode> results = methodNodeRepository.findByMethodNameContaining(query).stream()
                    .filter(m -> matchesProjectPaths(m, projectPaths))
                    .limit(limit)
                    .collect(Collectors.toList());
            return new VectorSearchResult<>(results, Collections.emptyMap());
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, e);
        }
    }

    /**
     * 使用放宽阈值的描述向量搜索
     */
    private VectorSearchResult<MethodNode> searchByDescriptionWithRelaxedThreshold(List<String> projectPaths, float[] embedding, int limit) {
        try {
            List<MethodWithScore> results = methodNodeRepository.findByDescriptionVectorSimilarityWithScoreByProjectPaths(
                    projectPaths, embedding, RELAXED_SIMILARITY_THRESHOLD, limit);
            List<MethodNode> methods = new ArrayList<>();
            Map<String, Double> scoreMap = new HashMap<>();
            for (MethodWithScore result : results) {
                methods.add(result.toMethodNode());
                if (result.score() != null) {
                    scoreMap.put(result.nodeId(), result.score());
                }
            }
            return new VectorSearchResult<>(methods, scoreMap);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, e);
        }
    }

    /**
     * 使用放宽阈值的代码向量搜索
     */
    private VectorSearchResult<MethodNode> searchByCodeWithRelaxedThreshold(List<String> projectPaths, float[] embedding, int limit) {
        try {
            List<MethodWithScore> results = methodNodeRepository.findByCodeVectorSimilarityWithScoreByProjectPaths(
                    projectPaths, embedding, RELAXED_SIMILARITY_THRESHOLD, limit);
            List<MethodNode> methods = new ArrayList<>();
            Map<String, Double> scoreMap = new HashMap<>();
            for (MethodWithScore result : results) {
                methods.add(result.toMethodNode());
                if (result.score() != null) {
                    scoreMap.put(result.nodeId(), result.score());
                }
            }
            return new VectorSearchResult<>(methods, scoreMap);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, e);
        }
    }

    /**
     * 使用放宽阈值的 SQL 向量搜索
     */
    private VectorSearchResult<MethodNode> searchBySqlWithRelaxedThreshold(List<String> projectPaths, float[] embedding, int limit) {
        List<Double> embeddingList = new ArrayList<>(embedding.length);
        for (float v : embedding) {
            embeddingList.add((double) v);
        }
        String firstPath = projectPaths.isEmpty() ? "" : projectPaths.get(0);
        try {
            List<SqlWithScore> sqlResults = sqlNodeRepository.findBySqlVectorSimilarityWithScore(
                    firstPath, embeddingList, RELAXED_SIMILARITY_THRESHOLD, limit);

            if (sqlResults.isEmpty()) {
                return VectorSearchResult.empty();
            }

            // 批量反查方法
            List<String> sqlNodeIds = sqlResults.stream()
                    .map(SqlWithScore::nodeId)
                    .collect(Collectors.toList());
            List<MethodBySqlNode> methodBySqlNodes = sqlNodeRepository.findMethodsBySqlNodeIds(sqlNodeIds);

            Map<String, Double> sqlNodeScoreMap = new HashMap<>();
            for (SqlWithScore sqlResult : sqlResults) {
                if (sqlResult.score() != null) {
                    sqlNodeScoreMap.put(sqlResult.nodeId(), sqlResult.score());
                }
            }

            List<String> methodNodeIds = methodBySqlNodes.stream()
                    .map(MethodBySqlNode::methodNodeId)
                    .distinct()
                    .collect(Collectors.toList());
            Map<String, MethodNode> methodNodeMap = methodNodeRepository.findAllByNodeIds(methodNodeIds).stream()
                    .filter(m -> matchesProjectPaths(m, projectPaths))
                    .collect(Collectors.toMap(MethodNode::getNodeId, m -> m, (a, b) -> a));

            List<MethodNode> methods = new ArrayList<>(methodNodeMap.values());
            Map<String, Double> methodScoreMap = new HashMap<>();
            Map<String, String> methodToSqlMap = methodBySqlNodes.stream()
                    .collect(Collectors.toMap(MethodBySqlNode::methodNodeId, MethodBySqlNode::sqlNodeId, (a, b) -> a));
            for (Map.Entry<String, MethodNode> entry : methodNodeMap.entrySet()) {
                String sqlNodeId = methodToSqlMap.get(entry.getKey());
                if (sqlNodeId != null && sqlNodeScoreMap.containsKey(sqlNodeId)) {
                    methodScoreMap.put(entry.getKey(), sqlNodeScoreMap.get(sqlNodeId));
                }
            }

            return new VectorSearchResult<>(deduplicate(methods), methodScoreMap);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(SearchErrorCode.GRAPH_SERVICE_ERROR, e);
        }
    }

    /**
     * 解析 projectPaths：未传入时退化为 projectPath（单项目模式）。
     */
    private List<String> resolveProjectPaths(String projectPath, List<String> projectPaths) {
        // 路径规范化：统一转为正斜杠形式，防止 Windows 反斜杠路径与正斜杠路径
        // 在 Neo4j 中作为两份独立数据匹配（导致维度不一致 / 0 结果）。
        // 项目约定：所有传入的路径在边界处必须经过 PathUtils.normalize() 归一。
        if (projectPaths != null && !projectPaths.isEmpty()) {
            List<String> normalized = new ArrayList<>(projectPaths.size());
            for (String p : projectPaths) {
                String n = PathUtils.normalize(p);
                if (n != null && !n.isBlank()) {
                    normalized.add(n);
                }
            }
            if (!normalized.isEmpty()) return normalized;
        }
        String normalizedSingle = PathUtils.normalize(projectPath);
        if (normalizedSingle != null && !normalizedSingle.isBlank()) {
            return List.of(normalizedSingle);
        }
        return List.of();
    }

    /**
     * 语言后过滤：null/空 = 不过滤；
     * 旧节点 language 为 null 时视为 java 兼容（绝大多数旧数据是 Java）。
     */
    private List<MethodNode> applyLanguageFilter(List<MethodNode> methods, String language) {
        if (language == null || language.isBlank() || methods == null || methods.isEmpty()) {
            return methods;
        }
        String lang = language.toLowerCase();
        return methods.stream()
                .filter(m -> {
                    String ml = m.getLanguage();
                    if (ml == null || ml.isBlank()) {
                        return "java".equals(lang); // 旧数据视为 java
                    }
                    return lang.equals(ml.toLowerCase());
                })
                .collect(Collectors.toList());
    }

    /**
     * 节点是否属于给定项目路径列表。
     */
    private boolean matchesProjectPaths(MethodNode m, List<String> projectPaths) {
        if (projectPaths == null || projectPaths.isEmpty()) return true;
        return projectPaths.contains(m.getProjectPath());
    }

    /**
     * 获取或生成嵌入向量（使用缓存）
     *
     * @throws SearchException 当 Embedding 服务不可用时抛出 EMBEDDING_SERVICE_UNAVAILABLE
     */
    private float[] getOrGenerateEmbedding(String text) {
        try {
            return queryEmbeddingCache.getOrGenerate(text, embeddingService);
        } catch (Exception e) {
            log.warn("生成嵌入向量失败: {}", e.getMessage());
            throw new SearchException(SearchErrorCode.EMBEDDING_SERVICE_UNAVAILABLE, e);
        }
    }

    /**
     * 获取或生成嵌入向量（以 List&lt;Double&gt; 形式返回）
     * <p>
     * Neo4j Java 驱动的 {@code Values.value(Object)} 仅原生支持 {@code Double}（映射为 Cypher Float）。
     * 传入 {@code List<Float>}（boxed {@code java.lang.Float}）时，驱动会对每个元素调用 {@code toString()}，
     * 产生 {@code List<String>}，导致 GDS {@code gds.similarity.cosine()} 与 {@code db.index.vector.queryNodes()}
     * 报错 "Vector must only contain finite values..." 或 "expected List&lt;Float&gt; but was List&lt;String&gt;"。
     * 所有向量检索调用统一使用此方法获取 {@code List<Double>}，避免类型不匹配。
     */
    private List<Double> getOrGenerateEmbeddingList(String text) {
        float[] arr = getOrGenerateEmbedding(text);
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add((double) v);
        }
        log.info("[DEBUG-VECTOR] getOrGenerateEmbeddingList: size={}, listClass={}, elemClass={}, first3={}",
                list.size(),
                list.getClass().getName(),
                list.isEmpty() ? "n/a" : list.get(0).getClass().getName(),
                list.subList(0, Math.min(3, list.size())));
        return list;
    }

    /**
     * 第一层: 关键词过滤（保留兼容性）
     */
    public List<MethodNode> keywordFilter(QueryIntent intent, String projectPath) {
        return keywordFilter(intent, projectPath, null, null);
    }

    /**
     * 第一层: 关键词过滤（多项目 + 多语言版本）
     *
     * @param intent 查询意图
     * @param projectPath 项目路径（必填，参数校验用）
     * @param projectPaths 项目路径列表（可空，空时退化为 projectPath）
     * @param language 语言过滤（可空，不过滤）
     */
    public List<MethodNode> keywordFilter(QueryIntent intent, String projectPath, List<String> projectPaths, String language) {
        if (intent == null) {
            return Collections.emptyList();
        }

        if (projectPath == null || projectPath.trim().isEmpty()) {
            throw new IllegalArgumentException("项目路径不能为空");
        }

        final List<String> effectiveProjectPaths = resolveProjectPaths(projectPath, projectPaths);
        List<MethodNode> results = new ArrayList<>();

        if (intent.getMethodType() != null && !intent.getMethodType().isEmpty()) {
            List<MethodNode> byMethodName = methodNodeRepository
                    .findByMethodNameContaining(intent.getMethodType());
            results.addAll(byMethodName.stream()
                    .filter(m -> matchesProjectPaths(m, effectiveProjectPaths))
                    .collect(Collectors.toList()));
        }

        if (intent.getServiceName() != null && !intent.getServiceName().isEmpty()) {
            List<MethodNode> byServiceName = methodNodeRepository
                    .findByServiceName(intent.getServiceName());
            results.addAll(byServiceName.stream()
                    .filter(m -> matchesProjectPaths(m, effectiveProjectPaths))
                    .collect(Collectors.toList()));
        }

        if (intent.getKeywords() != null) {
            for (String keyword : intent.getKeywords()) {
                if (keyword != null && !keyword.isEmpty()) {
                    List<MethodNode> byKeyword = methodNodeRepository
                            .findByMethodNameContaining(keyword);
                    results.addAll(byKeyword.stream()
                            .filter(m -> matchesProjectPaths(m, effectiveProjectPaths))
                            .collect(Collectors.toList()));
                }
            }
        }

        if (results.isEmpty() &&
                (intent.getMethodType() == null || intent.getMethodType().isEmpty()) &&
                (intent.getServiceName() == null || intent.getServiceName().isEmpty()) &&
                (intent.getKeywords() == null || intent.getKeywords().isEmpty())) {
            results.addAll(methodNodeRepository.findByProjectPaths(effectiveProjectPaths));
        }

        List<MethodNode> deduped = results.stream().distinct().collect(Collectors.toList());
        return applyLanguageFilter(deduped, language);
    }

    /**
     * 向量搜索（保留兼容性）
     */
    public List<MethodNode> vectorSearch(String query, String projectPath) {
        return vectorSearch(query, projectPath, DEFAULT_TOP_K);
    }

    /**
     * 向量搜索（保留兼容性）
     */
    public List<MethodNode> vectorSearch(String query, String projectPath, int topK) {
        return vectorSearch(query, projectPath, null, null, topK);
    }

    /**
     * 向量搜索（多项目 + 多语言版本）
     */
    public List<MethodNode> vectorSearch(String query, String projectPath, List<String> projectPaths, String language, int topK) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }

        final List<String> effectiveProjectPaths = resolveProjectPaths(projectPath, projectPaths);
        try {
            float[] queryEmbedding = getOrGenerateEmbedding(query);
            List<MethodNode> raw = methodNodeRepository.findByDescriptionVectorSimilarityByProjectPaths(
                    effectiveProjectPaths, queryEmbedding, SIMILARITY_THRESHOLD, topK);
            return applyLanguageFilter(raw, language);
        } catch (SearchException e) {
            // 向量搜索作为兼容性方法，静默处理 SearchException
            log.warn("向量搜索失败(兼容方法): {}", e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("向量搜索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 第三层: 图遍历扩展
     */
    public List<MethodNode> graphExpansion(List<MethodNode> seeds, String projectPath, int depth) {
        if (seeds == null) {
            throw new IllegalArgumentException("种子节点不能为null");
        }

        if (seeds.isEmpty() || depth <= 0) {
            return new ArrayList<>(seeds);
        }

        Set<MethodNode> expandedNodes = new LinkedHashSet<>(seeds);

        for (MethodNode seed : seeds) {
            if (seed.getNodeId() == null) {
                continue;
            }

            try {
                List<MethodNode> callers = methodNodeRepository
                        .findCallersUpToDepth(seed.getNodeId(), depth);
                expandedNodes.addAll(callers);

                List<MethodNode> callees = methodNodeRepository
                        .findCalleesUpToDepth(seed.getNodeId(), depth);
                expandedNodes.addAll(callees);

            } catch (Exception e) {
                log.warn("图遍历扩展失败，节点ID: {}, 错误: {}", seed.getNodeId(), e.getMessage());
            }
        }

        return new ArrayList<>(expandedNodes);
    }

    /**
     * RRF融合算法
     */
    public List<MethodNode> fuseResults(List<MethodNode> vectorResults, List<MethodNode> graphResults) {
        if (vectorResults == null) {
            vectorResults = Collections.emptyList();
        }
        if (graphResults == null) {
            graphResults = Collections.emptyList();
        }

        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, MethodNode> nodeMap = new HashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            MethodNode node = vectorResults.get(i);
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            String nodeId = node.getNodeId();
            double score = 1.0 / (RRF_K + i + 1);
            scoreMap.merge(nodeId, score, Double::sum);
            nodeMap.putIfAbsent(nodeId, node);
        }

        for (int i = 0; i < graphResults.size(); i++) {
            MethodNode node = graphResults.get(i);
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            String nodeId = node.getNodeId();
            double score = 1.0 / (RRF_K + i + 1);
            scoreMap.merge(nodeId, score, Double::sum);
            nodeMap.putIfAbsent(nodeId, node);
        }

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> nodeMap.get(entry.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 构建增强搜索结果项 (带相似度分数)
     * 使用批量查询获取上下文数据，解决 N+1 查询问题
     */
    private List<SearchResultItem> buildSearchResultItems(List<MethodNode> methods, List<String> projectPaths, Map<String, Double> similarityScores) {
        if (methods == null || methods.isEmpty()) {
            return Collections.emptyList();
        }

        // 收集所有 nodeId 用于批量查询
        List<String> nodeIds = methods.stream()
                .map(MethodNode::getNodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (nodeIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 批量获取调用者摘要
        Map<String, List<SearchResultItem.CallerSummary>> callersMap = batchGetCallerSummaries(nodeIds);

        // 2. 批量获取被调用者摘要
        Map<String, List<SearchResultItem.CalleeSummary>> calleesMap = batchGetCalleeSummaries(nodeIds);

        // 3. 批量获取入口点摘要
        String firstPath = projectPaths.isEmpty() ? "" : projectPaths.get(0);
        Map<String, List<SearchResultItem.EntryPointSummary>> entryPointsMap = batchGetEntryPointSummaries(firstPath, nodeIds);

        // 4. 批量获取SQL摘要
        Map<String, List<SearchResultItem.SqlSummary>> sqlNodesMap = batchGetSqlSummaries(nodeIds);

        // 5. 组装结果
        List<SearchResultItem> items = new ArrayList<>();
        for (MethodNode method : methods) {
            String nodeId = method.getNodeId();
            Double score = similarityScores != null ? similarityScores.get(nodeId) : null;

            SearchResultItem item = SearchResultItem.builder()
                    .nodeId(nodeId)
                    .nodeType("Method")
                    .className(method.getClassName())
                    .methodName(method.getMethodName())
                    .signature(method.getSignature())
                    .filePath(method.getFilePath())
                    .startLine(method.getStartLine())
                    .endLine(method.getEndLine())
                    .description(method.getDescription())
                    .similarityScore(score)
                    .callers(callersMap.getOrDefault(nodeId, Collections.emptyList()))
                    .callees(calleesMap.getOrDefault(nodeId, Collections.emptyList()))
                    .entryPoints(entryPointsMap.getOrDefault(nodeId, Collections.emptyList()))
                    .sqlNodes(sqlNodesMap.getOrDefault(nodeId, Collections.emptyList()))
                    .build();
            items.add(item);
        }
        return items;
    }

    /**
     * 批量获取调用者摘要
     * 一次查询获取所有节点的调用者，按 nodeId 分组
     */
    private Map<String, List<SearchResultItem.CallerSummary>> batchGetCallerSummaries(List<String> nodeIds) {
        try {
            List<CallerWithRelationByTarget> callers = methodNodeRepository.findCallersByNodeIds(nodeIds);
            return callers.stream()
                    .collect(Collectors.groupingBy(
                            CallerWithRelationByTarget::targetNodeId,
                            Collectors.mapping(
                                    c -> SearchResultItem.CallerSummary.builder()
                                            .className(c.callerClassName())
                                            .methodName(c.callerMethodName())
                                            .signature(c.callerSignature())
                                            .build(),
                                    Collectors.collectingAndThen(
                                            Collectors.toList(),
                                            list -> list.stream().limit(CONTEXT_LIMIT).collect(Collectors.toList())
                                    )
                            )
                    ));
        } catch (Exception e) {
            log.debug("批量获取调用者摘要失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 批量获取被调用者摘要
     * 一次查询获取所有节点的被调用者，按 nodeId 分组
     */
    private Map<String, List<SearchResultItem.CalleeSummary>> batchGetCalleeSummaries(List<String> nodeIds) {
        try {
            List<CalleeWithRelationBySource> callees = methodNodeRepository.findCalleesByNodeIds(nodeIds);
            return callees.stream()
                    .collect(Collectors.groupingBy(
                            CalleeWithRelationBySource::sourceNodeId,
                            Collectors.mapping(
                                    c -> SearchResultItem.CalleeSummary.builder()
                                            .className(c.calleeClassName())
                                            .methodName(c.calleeMethodName())
                                            .signature(c.calleeSignature())
                                            .build(),
                                    Collectors.collectingAndThen(
                                            Collectors.toList(),
                                            list -> list.stream().limit(CONTEXT_LIMIT).collect(Collectors.toList())
                                    )
                            )
                    ));
        } catch (Exception e) {
            log.debug("批量获取被调用者摘要失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 批量获取入口点摘要
     * 一次查询获取所有节点的关联入口点，按 methodNodeId 分组
     */
    private Map<String, List<SearchResultItem.EntryPointSummary>> batchGetEntryPointSummaries(String projectPath, List<String> methodNodeIds) {
        try {
            List<EntryPointNode> entryPoints = entryPointRepository.findByMethodNodeIds(projectPath, methodNodeIds);
            return entryPoints.stream()
                    .filter(ep -> ep.getMethodNodeId() != null)
                    .collect(Collectors.groupingBy(
                            EntryPointNode::getMethodNodeId,
                            Collectors.mapping(
                                    ep -> SearchResultItem.EntryPointSummary.builder()
                                            .entryType(ep.getEntryType())
                                            .entryKey(ep.getEntryKey())
                                            .build(),
                                    Collectors.collectingAndThen(
                                            Collectors.toList(),
                                            list -> list.stream().limit(CONTEXT_LIMIT).collect(Collectors.toList())
                                    )
                            )
                    ));
        } catch (Exception e) {
            log.debug("批量获取入口点摘要失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 批量获取SQL摘要
     * 一次查询获取所有节点的关联SQL，按 methodNodeId 分组
     */
    private Map<String, List<SearchResultItem.SqlSummary>> batchGetSqlSummaries(List<String> methodNodeIds) {
        try {
            List<SqlNodeByMethod> sqlNodes = sqlNodeRepository.findByMethodNodeIds(methodNodeIds);
            return sqlNodes.stream()
                    .collect(Collectors.groupingBy(
                            SqlNodeByMethod::methodNodeId,
                            Collectors.mapping(
                                    sql -> SearchResultItem.SqlSummary.builder()
                                            .sqlId(sql.sqlId())
                                            .statementType(sql.statementType())
                                            .sqlStatement(truncateSql(sql.sqlStatement(), 200))
                                            .build(),
                                    Collectors.collectingAndThen(
                                            Collectors.toList(),
                                            list -> list.stream().limit(CONTEXT_LIMIT).collect(Collectors.toList())
                                    )
                            )
                    ));
        } catch (Exception e) {
            log.debug("批量获取SQL摘要失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 去重
     */
    private List<MethodNode> deduplicate(List<MethodNode> methods) {
        return methods.stream()
                .filter(m -> m.getNodeId() != null)
                .collect(Collectors.toMap(
                        MethodNode::getNodeId,
                        m -> m,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .collect(Collectors.toList());
    }

    /**
     * 生成搜索提示
     */
    private String generateSearchTips(String query, QueryType queryType) {
        return switch (queryType) {
            case NATURAL_LANGUAGE -> "尝试使用更具体的关键词或方法名进行搜索";
            case METHOD_NAME -> "检查方法名拼写是否正确，或尝试使用类名进行搜索";
            case FULL_QUALIFIED_NAME -> "确认类名和方法名的完整路径是否正确";
            case FULL_QUALIFIED_CLASS_NAME -> "确认全限定类名是否正确（包名.类名）";
            case CLASS_NAME -> "尝试使用部分类名或模糊匹配";
            case SQL_SNIPPET -> "尝试使用SQL关键字或表名进行搜索";
            case HTTP_URI -> "确认URI路径是否正确，或尝试使用HTTP方法进行搜索";
            case CODE_SNIPPET -> "尝试使用更具体的代码片段或关键字";
            case ANNOTATION -> "确认注解名称是否正确，如 @Transactional";
            case EXCEPTION_TYPE -> "确认异常类型名称是否正确";
        };
    }

    /**
     * 生成搜索建议
     */
    private List<String> generateSuggestions(String query, QueryType queryType) {
        List<String> suggestions = new ArrayList<>();

        // 根据查询类型生成建议
        switch (queryType) {
            case METHOD_NAME -> {
                if (!query.contains(".")) {
                    suggestions.add("尝试使用 ClassName." + query + " 格式搜索");
                }
                suggestions.add("尝试使用相关类名进行搜索");
            }
            case CLASS_NAME -> {
                suggestions.add("尝试搜索该类中的方法");
                suggestions.add("尝试使用更通用的类名搜索");
            }
            case HTTP_URI -> {
                if (query.startsWith("/")) {
                    suggestions.add("尝试添加HTTP方法前缀，如 GET " + query);
                }
            }
            case NATURAL_LANGUAGE -> {
                // 提取关键词作为建议
                String[] words = query.split("[\\s,，。.!！?？]+");
                for (String word : words) {
                    if (word.length() >= 2 && word.length() <= 20) {
                        suggestions.add("尝试搜索: " + word);
                    }
                }
            }
            default -> {
                suggestions.add("尝试使用不同的关键词");
                suggestions.add("检查项目路径是否正确");
            }
        }

        return suggestions.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * 截断SQL语句
     */
    private String truncateSql(String sql, int maxLength) {
        if (sql == null) {
            return null;
        }
        if (sql.length() <= maxLength) {
            return sql;
        }
        return sql.substring(0, maxLength) + "...";
    }

    /**
     * Find nodes with a specific annotation by checking methodBody and comment.
     *
     * @param nodeIds    method node IDs to check
     * @param annotation the annotation to look for (with or without @)
     * @return set of nodeIds that have the annotation
     */
    public Set<String> findNodesWithAnnotation(List<String> nodeIds, String annotation) {
        if (nodeIds == null || nodeIds.isEmpty() || annotation == null || annotation.isBlank()) {
            return Collections.emptySet();
        }
        String normalized = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        try {
            // Query all methods with the annotation, then intersect with nodeIds
            List<MethodNode> annotated = methodNodeRepository.findByProjectPathsAndAnnotation(
                    List.of(), normalized); // empty projectPaths → all projects
            Set<String> annotatedIds = annotated.stream()
                    .map(MethodNode::getNodeId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<String> result = new LinkedHashSet<>(nodeIds);
            result.retainAll(annotatedIds);
            return result;
        } catch (Exception e) {
            log.debug("[POST-FILTER] annotation check failed for '{}': {}", annotation, e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Batch check annotations for a list of node IDs.
     * Used for annotation bonus in intent-aware search.
     *
     * @param nodeIds     list of node IDs to check
     * @param annotations annotations to look for
     * @return map of nodeId -> set of matched annotations
     */
    public Map<String, Set<String>> batchCheckAnnotations(List<String> nodeIds, String[] annotations) {
        if (nodeIds == null || nodeIds.isEmpty() || annotations == null || annotations.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (String annotation : annotations) {
            Set<String> matched = findNodesWithAnnotation(nodeIds, annotation);
            for (String nodeId : matched) {
                result.computeIfAbsent(nodeId, k -> new LinkedHashSet<>()).add(annotation);
            }
        }
        return result;
    }

    /**
     * Get 1-hop callees for a list of node IDs.
     * Used for callee weight propagation in intent-aware search.
     *
     * @param nodeIds source node IDs
     * @return map of source nodeId -> list of callee nodeIds
     */
    public Map<String, List<String>> get1HopCallees(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            List<Neo4jMethodNodeRepository.CalleeWithRelationBySource> callees = methodNodeRepository.findCalleesByNodeIds(nodeIds);
            return callees.stream()
                    .filter(c -> c.calleeId() != null)
                    .collect(Collectors.groupingBy(
                            Neo4jMethodNodeRepository.CalleeWithRelationBySource::sourceNodeId,
                            Collectors.mapping(
                                    Neo4jMethodNodeRepository.CalleeWithRelationBySource::calleeId,
                                    Collectors.toList()
                            )
                    ));
        } catch (Exception e) {
            log.debug("[CALLEE-PROP] 1-hop callee lookup failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Extract core nouns from a query for required word filtering.
     * Delegates to extractSearchKeywords for implementation.
     *
     * @param query user query
     * @return list of core nouns/keywords
     */
    public List<String> extractCoreNouns(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        // Reuse extractSearchKeywords logic — same stop words and length rules
        return extractSearchKeywords(query);
    }

    /**
     * Check if a method node matches at least one of the required words.
     * Matches against methodName, className, and description.
     *
     * @param node          the method node to check
     * @param requiredWords list of required words
     * @return true if at least one word matches
     */
    public boolean matchesAnyRequiredWord(MethodNode node, List<String> requiredWords) {
        if (requiredWords == null || requiredWords.isEmpty()) return true;
        if (node == null) return false;

        String text = String.join(" ",
                Objects.toString(node.getMethodName(), ""),
                Objects.toString(node.getClassName(), ""),
                Objects.toString(node.getDescription(), ""));

        for (String word : requiredWords) {
            if (word != null && text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}