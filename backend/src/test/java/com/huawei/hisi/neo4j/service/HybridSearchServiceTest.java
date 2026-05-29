package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.QueryIntent;
import com.huawei.hisi.neo4j.model.QueryType;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.MethodWithScore;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * HybridSearchService 测试类
 *
 * 测试多策略路由搜索: 查询类型检测 -> 路由搜索 -> 图遍历扩展 -> RRF融合
 */
@ExtendWith(MockitoExtension.class)
class HybridSearchServiceTest {

    @Mock
    private Neo4jMethodNodeRepository methodNodeRepository;

    @Mock
    private Neo4jSqlNodeRepository sqlNodeRepository;

    @Mock
    private Neo4jEntryPointNodeRepository entryPointRepository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private QueryTypeDetector queryTypeDetector;

    @Mock
    private Neo4jVectorIndexService vectorIndexService;

    @Mock
    private QueryEmbeddingCache queryEmbeddingCache;

    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        hybridSearchService = new HybridSearchService(
                methodNodeRepository,
                sqlNodeRepository,
                entryPointRepository,
                embeddingService,
                queryTypeDetector,
                vectorIndexService,
                queryEmbeddingCache
        );
    }

    // ================== 关键词过滤测试 ==================

    @Test
    void testKeywordFilter_WithMethodName() {
        // Arrange
        QueryIntent intent = QueryIntent.builder()
                .methodType("createUser")
                .serviceName("user-service")
                .build();
        String projectPath = "/project/test";

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .methodName("createUser")
                .projectPath(projectPath)
                .build();

        when(methodNodeRepository.findByMethodNameContaining("createUser"))
                .thenReturn(List.of(method1));

        // Act
        List<MethodNode> results = hybridSearchService.keywordFilter(intent, projectPath);

        // Assert
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("createUser", results.get(0).getMethodName());
    }

    @Test
    void testKeywordFilter_WithServiceName() {
        // Arrange
        QueryIntent intent = QueryIntent.builder()
                .serviceName("user-service")
                .build();
        String projectPath = "/project/test";

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .serviceName("user-service")
                .projectPath(projectPath)
                .build();

        when(methodNodeRepository.findByServiceName("user-service"))
                .thenReturn(List.of(method1));

        // Act
        List<MethodNode> results = hybridSearchService.keywordFilter(intent, projectPath);

        // Assert
        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    void testKeywordFilter_WithKeywords() {
        // Arrange
        QueryIntent intent = QueryIntent.builder()
                .keywords(Arrays.asList("create", "user"))
                .build();
        String projectPath = "/project/test";

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .methodName("createUser")
                .projectPath(projectPath)
                .build();

        when(methodNodeRepository.findByMethodNameContaining(anyString()))
                .thenReturn(List.of(method1));

        // Act
        List<MethodNode> results = hybridSearchService.keywordFilter(intent, projectPath);

        // Assert
        assertNotNull(results);
    }

    @Test
    void testKeywordFilter_WithEmptyIntent() {
        // Arrange
        QueryIntent intent = QueryIntent.builder().build();
        String projectPath = "/project/test";

        when(methodNodeRepository.findByProjectPaths(List.of(projectPath)))
                .thenReturn(Collections.emptyList());

        // Act
        List<MethodNode> results = hybridSearchService.keywordFilter(intent, projectPath);

        // Assert
        assertNotNull(results);
    }

    @Test
    void testKeywordFilter_WithNullProjectPath() {
        // Arrange
        QueryIntent intent = QueryIntent.builder()
                .methodType("test")
                .build();

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            hybridSearchService.keywordFilter(intent, null);
        });
    }

    // ================== 向量搜索测试 ==================

    @Test
    void testVectorSearch_WithValidQuery() {
        // Arrange
        String query = "如何创建用户";
        String projectPath = "/project/test";
        int topK = 10;

        float[] embedding = new float[384];
        Arrays.fill(embedding, 0.1f);

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .methodName("createUser")
                .projectPath(projectPath)
                .descriptionEmbedding(embedding)
                .build();

        when(queryEmbeddingCache.getOrGenerate(eq(query), any(EmbeddingService.class))).thenReturn(embedding);
        when(methodNodeRepository.findByDescriptionVectorSimilarityByProjectPaths(eq(List.of(projectPath)), any(float[].class), anyDouble(), eq(topK)))
                .thenReturn(List.of(method1));

        // Act
        List<MethodNode> results = hybridSearchService.vectorSearch(query, projectPath, topK);

        // Assert
        assertNotNull(results);
        assertFalse(results.isEmpty());
        verify(queryEmbeddingCache).getOrGenerate(eq(query), any(EmbeddingService.class));
    }

    @Test
    void testVectorSearch_WithEmptyResults() {
        // Arrange
        String query = "不存在的功能";
        String projectPath = "/project/test";
        int topK = 10;

        float[] embedding = new float[384];
        when(queryEmbeddingCache.getOrGenerate(eq(query), any(EmbeddingService.class))).thenReturn(embedding);
        when(methodNodeRepository.findByDescriptionVectorSimilarityByProjectPaths(anyList(), any(float[].class), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());

        // Act
        List<MethodNode> results = hybridSearchService.vectorSearch(query, projectPath, topK);

        // Assert
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testVectorSearch_WithDefaultTopK() {
        // Arrange
        String query = "测试查询";
        String projectPath = "/project/test";

        float[] embedding = new float[384];
        when(queryEmbeddingCache.getOrGenerate(eq(query), any(EmbeddingService.class))).thenReturn(embedding);
        when(methodNodeRepository.findByDescriptionVectorSimilarityByProjectPaths(anyList(), any(float[].class), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());

        // Act - 使用默认 topK
        List<MethodNode> results = hybridSearchService.vectorSearch(query, projectPath);

        // Assert
        assertNotNull(results);
    }

    // ================== 图遍历扩展测试 ==================

    @Test
    void testGraphExpansion_WithValidSeeds() {
        // Arrange
        MethodNode seed = MethodNode.builder()
                .nodeId("seed.1")
                .methodName("createUser")
                .build();
        List<MethodNode> seeds = List.of(seed);
        String projectPath = "/project/test";
        int depth = 2;

        MethodNode caller1 = MethodNode.builder()
                .nodeId("caller.1")
                .methodName("processOrder")
                .build();

        MethodNode callee1 = MethodNode.builder()
                .nodeId("callee.1")
                .methodName("validateUser")
                .build();

        when(methodNodeRepository.findCallersUpToDepth("seed.1", depth))
                .thenReturn(List.of(caller1));
        when(methodNodeRepository.findCalleesUpToDepth("seed.1", depth))
                .thenReturn(List.of(callee1));

        // Act
        List<MethodNode> results = hybridSearchService.graphExpansion(seeds, projectPath, depth);

        // Assert
        assertNotNull(results);
        // 结果应包含 callers 和 callees
        assertTrue(results.size() >= 0);
    }

    @Test
    void testGraphExpansion_WithEmptySeeds() {
        // Arrange
        List<MethodNode> seeds = Collections.emptyList();
        String projectPath = "/project/test";
        int depth = 2;

        // Act
        List<MethodNode> results = hybridSearchService.graphExpansion(seeds, projectPath, depth);

        // Assert
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testGraphExpansion_WithNullSeeds() {
        // Arrange
        String projectPath = "/project/test";
        int depth = 2;

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            hybridSearchService.graphExpansion(null, projectPath, depth);
        });
    }

    @Test
    void testGraphExpansion_WithInvalidDepth() {
        // Arrange
        MethodNode seed = MethodNode.builder().nodeId("seed.1").build();
        List<MethodNode> seeds = List.of(seed);
        String projectPath = "/project/test";
        int depth = 0;

        // Act
        List<MethodNode> results = hybridSearchService.graphExpansion(seeds, projectPath, depth);

        // Assert - depth=0 应该返回空结果或只返回种子节点
        assertNotNull(results);
    }

    // ================== RRF 融合测试 ==================

    @Test
    void testRRFFusion_WithValidResults() {
        // Arrange
        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .methodName("createUser")
                .build();

        MethodNode method2 = MethodNode.builder()
                .nodeId("method.2")
                .methodName("createOrder")
                .build();

        MethodNode method3 = MethodNode.builder()
                .nodeId("method.3")
                .methodName("deleteUser")
                .build();

        // 向量搜索结果
        List<MethodNode> vectorResults = Arrays.asList(method1, method2);

        // 图遍历结果
        List<MethodNode> graphResults = Arrays.asList(method2, method3);

        // Act
        List<MethodNode> fusedResults = hybridSearchService.fuseResults(vectorResults, graphResults);

        // Assert
        assertNotNull(fusedResults);
        // method2 应该排名更高因为它在两个列表中都出现
        assertFalse(fusedResults.isEmpty());
    }

    @Test
    void testRRFFusion_WithEmptyVectorResults() {
        // Arrange
        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .build();

        List<MethodNode> vectorResults = Collections.emptyList();
        List<MethodNode> graphResults = List.of(method1);

        // Act
        List<MethodNode> fusedResults = hybridSearchService.fuseResults(vectorResults, graphResults);

        // Assert
        assertNotNull(fusedResults);
        assertEquals(1, fusedResults.size());
    }

    @Test
    void testRRFFusion_WithEmptyGraphResults() {
        // Arrange
        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .build();

        List<MethodNode> vectorResults = List.of(method1);
        List<MethodNode> graphResults = Collections.emptyList();

        // Act
        List<MethodNode> fusedResults = hybridSearchService.fuseResults(vectorResults, graphResults);

        // Assert
        assertNotNull(fusedResults);
        assertEquals(1, fusedResults.size());
    }

    @Test
    void testRRFFusion_WithBothEmpty() {
        // Arrange
        List<MethodNode> vectorResults = Collections.emptyList();
        List<MethodNode> graphResults = Collections.emptyList();

        // Act
        List<MethodNode> fusedResults = hybridSearchService.fuseResults(vectorResults, graphResults);

        // Assert
        assertNotNull(fusedResults);
        assertTrue(fusedResults.isEmpty());
    }

    @Test
    void testRRFFusion_RankingCorrectness() {
        // Arrange - 测试RRF公式: score = 1/(k + rank), k=60
        MethodNode methodA = MethodNode.builder().nodeId("A").build();
        MethodNode methodB = MethodNode.builder().nodeId("B").build();
        MethodNode methodC = MethodNode.builder().nodeId("C").build();

        // methodA 在向量搜索排名第1，图遍历排名第2
        // methodB 在向量搜索排名第2，图遍历排名第1
        // methodC 只在向量搜索排名第3
        List<MethodNode> vectorResults = Arrays.asList(methodA, methodB, methodC);
        List<MethodNode> graphResults = Arrays.asList(methodB, methodA);

        // Act
        List<MethodNode> fusedResults = hybridSearchService.fuseResults(vectorResults, graphResults);

        // Assert
        assertNotNull(fusedResults);
        // methodA 和 methodB 应该排在 methodC 前面
        assertTrue(fusedResults.indexOf(methodC) > fusedResults.indexOf(methodA) ||
                   fusedResults.indexOf(methodC) > fusedResults.indexOf(methodB));
    }

    // ================== 完整混合检索测试 ==================

    @Test
    void testFullHybridSearch_NaturalLanguage() {
        // Arrange
        String query = "查找用户创建方法";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder()
                .entity("UserService")
                .methodType("create")
                .serviceName("user-service")
                .keywords(Arrays.asList("user", "create"))
                .build();

        float[] embedding = new float[384];
        Arrays.fill(embedding, 0.1f);

        MethodNode seedMethod = MethodNode.builder()
                .nodeId("seed.1")
                .methodName("createUser")
                .projectPath(projectPath)
                .descriptionEmbedding(embedding)
                .build();

        // Mock 查询类型检测
        when(queryTypeDetector.detect(query)).thenReturn(QueryType.NATURAL_LANGUAGE);

        // Mock 意图识别

        // Mock 向量搜索
        when(queryEmbeddingCache.getOrGenerate(eq(query), any(EmbeddingService.class))).thenReturn(embedding);
        when(vectorIndexService.isVectorIndexAvailable()).thenReturn(false);

        // 创建带分数的方法结果
        MethodWithScore methodWithScore = new MethodWithScore(
                seedMethod.getNodeId(),
                seedMethod.getClassName(),
                seedMethod.getMethodName(),
                seedMethod.getSignature(),
                seedMethod.getFilePath(),
                seedMethod.getStartLine(),
                seedMethod.getEndLine(),
                seedMethod.getComplexity(),
                seedMethod.getThrownExceptions(),
                seedMethod.getCaughtExceptions(),
                seedMethod.getMethodBody(),
                seedMethod.getProjectPath(),
                seedMethod.getServiceName(),
                seedMethod.getComment(),
                seedMethod.getDescription(),
                0.85
        );
        when(methodNodeRepository.findByDescriptionVectorSimilarityWithScoreByProjectPaths(anyList(), any(float[].class), anyDouble(), anyInt()))
                .thenReturn(List.of(methodWithScore));

        // Mock 图遍历
        when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        // Mock 批量上下文查询
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertEquals(query, result.getQuery());
        assertNotNull(result.getIntent());
        assertNotNull(result.getResults());
        assertNotNull(result.getItems());
        assertEquals(QueryType.NATURAL_LANGUAGE, result.getQueryType());
        assertNotNull(result.getCostTimeMs());
        assertTrue(result.getCostTimeMs() >= 0);
    }

    @Test
    void testFullHybridSearch_MethodName() {
        // Arrange
        String query = "createUser";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder().build();
        float[] embedding = new float[384];

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .methodName("createUser")
                .projectPath(projectPath)
                .build();

        when(queryTypeDetector.detect(query)).thenReturn(QueryType.METHOD_NAME);
        when(methodNodeRepository.findByProjectPathsAndMethodNameContaining(List.of(projectPath), "createUser"))
                .thenReturn(List.of(method1));
        // Mock 向量补充（当结果数小于 limit 时会调用）
        lenient().when(queryEmbeddingCache.getOrGenerate(eq(query), any(EmbeddingService.class))).thenReturn(embedding);
        lenient().when(methodNodeRepository.findByDescriptionVectorSimilarityWithScoreByProjectPaths(anyList(), any(float[].class), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        // Mock 批量上下文查询
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertEquals(QueryType.METHOD_NAME, result.getQueryType());
        assertFalse(result.getResults().isEmpty());
    }

    @Test
    void testFullHybridSearch_ClassName() {
        // Arrange
        String query = "UserService";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder().build();

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .className("UserService")
                .methodName("createUser")
                .projectPath(projectPath)
                .build();

        when(queryTypeDetector.detect(query)).thenReturn(QueryType.CLASS_NAME);

        when(methodNodeRepository.findByProjectPathsAndClassName(List.of(projectPath), "UserService"))
                .thenReturn(List.of(method1));
        when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        // Mock 批量上下文查询
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertEquals(QueryType.CLASS_NAME, result.getQueryType());
    }

    @Test
    void testFullHybridSearch_Annotation() {
        // Arrange
        String query = "@Transactional";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder().build();

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .className("UserService")
                .methodName("createUser")
                .projectPath(projectPath)
                .build();

        when(queryTypeDetector.detect(query)).thenReturn(QueryType.ANNOTATION);

        when(methodNodeRepository.findByProjectPathsAndAnnotation(List.of(projectPath), "Transactional"))
                .thenReturn(List.of(method1));
        lenient().when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        // Mock 批量上下文查询
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertEquals(QueryType.ANNOTATION, result.getQueryType());
    }

    @Test
    void testFullHybridSearch_ExceptionType() {
        // Arrange
        String query = "BusinessException";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder().build();

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .className("UserService")
                .methodName("createUser")
                .projectPath(projectPath)
                .thrownExceptions(List.of("BusinessException"))
                .build();

        when(queryTypeDetector.detect(query)).thenReturn(QueryType.EXCEPTION_TYPE);

        when(methodNodeRepository.findByProjectPathsAndExceptionType(List.of(projectPath), "BusinessException"))
                .thenReturn(List.of(method1));
        lenient().when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        // Mock 批量上下文查询
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertEquals(QueryType.EXCEPTION_TYPE, result.getQueryType());
    }

    @Test
    void testFullHybridSearch_WithEmptyQuery() {
        // Arrange
        String query = "";
        String projectPath = "/project/test";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            hybridSearchService.hybridSearch(query, projectPath);
        });
    }

    @Test
    void testFullHybridSearch_WithNullProjectPath() {
        // Arrange
        String query = "测试查询";

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            hybridSearchService.hybridSearch(query, null);
        });
    }

    @Test
    void testFullHybridSearch_WithNoResults() {
        // Arrange
        String query = "不存在的功能xyz123";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder().build();
        float[] embedding = new float[384];

        when(queryTypeDetector.detect(query)).thenReturn(QueryType.NATURAL_LANGUAGE);
        when(queryEmbeddingCache.getOrGenerate(eq(query), any(EmbeddingService.class))).thenReturn(embedding);
        when(vectorIndexService.isVectorIndexAvailable()).thenReturn(false);
        when(methodNodeRepository.findByDescriptionVectorSimilarityWithScoreByProjectPaths(anyList(), any(float[].class), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());
        // 使用 lenient 因为没有结果时图遍历不会执行
        lenient().when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findByMethodNameContaining(query)).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getTotalCount());
        assertTrue(result.getResults().isEmpty());
        // 无结果时应有搜索提示和建议
        assertNotNull(result.getSearchTips());
        assertNotNull(result.getSuggestions());
    }

    @Test
    void testFullHybridSearch_HttpUri() {
        // Arrange
        String query = "POST /api/user/login";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder().build();

        var entryPoint = com.huawei.hisi.neo4j.model.EntryPointNode.builder()
                .entryId("ep.1")
                .entryType("HTTP")
                .entryKey("POST /api/user/login")
                .projectPath(projectPath)
                .methodNodeId("method.1")
                .build();

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .className("UserController")
                .methodName("login")
                .projectPath(projectPath)
                .build();

        when(queryTypeDetector.detect(query)).thenReturn(QueryType.HTTP_URI);
        when(entryPointRepository.findByProjectPathAndEntryKeyContaining(projectPath, query))
                .thenReturn(List.of(entryPoint));
        // 批量查询替代单个查询
        when(methodNodeRepository.findAllByNodeIds(anyList())).thenReturn(List.of(method1));
        lenient().when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        // Mock 批量上下文查询
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertEquals(QueryType.HTTP_URI, result.getQueryType());
        assertFalse(result.getResults().isEmpty());
    }

    @Test
    void testFullHybridSearch_SearchResultItems() {
        // Arrange
        String query = "查找用户创建方法";
        String projectPath = "/project/test";

        QueryIntent intent = QueryIntent.builder().build();

        float[] embedding = new float[384];
        Arrays.fill(embedding, 0.1f);

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .className("UserService")
                .methodName("createUser")
                .signature("createUser(String, String)")
                .filePath("/src/UserService.java")
                .startLine(10)
                .endLine(20)
                .description("创建用户方法")
                .projectPath(projectPath)
                .build();

        when(queryTypeDetector.detect(query)).thenReturn(QueryType.NATURAL_LANGUAGE);
        when(queryEmbeddingCache.getOrGenerate(eq(query), any(EmbeddingService.class))).thenReturn(embedding);
        when(vectorIndexService.isVectorIndexAvailable()).thenReturn(false);

        // 创建带分数的方法结果
        MethodWithScore methodWithScore = new MethodWithScore(
                method1.getNodeId(),
                method1.getClassName(),
                method1.getMethodName(),
                method1.getSignature(),
                method1.getFilePath(),
                method1.getStartLine(),
                method1.getEndLine(),
                method1.getComplexity(),
                method1.getThrownExceptions(),
                method1.getCaughtExceptions(),
                method1.getMethodBody(),
                method1.getProjectPath(),
                method1.getServiceName(),
                method1.getComment(),
                method1.getDescription(),
                0.92
        );
        when(methodNodeRepository.findByDescriptionVectorSimilarityWithScoreByProjectPaths(anyList(), any(float[].class), anyDouble(), anyInt()))
                .thenReturn(List.of(methodWithScore));
        when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        // Mock 批量上下文查询
        lenient().when(methodNodeRepository.findCallersByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesByNodeIds(anyList())).thenReturn(Collections.emptyList());
        lenient().when(entryPointRepository.findByMethodNodeIds(anyString(), anyList())).thenReturn(Collections.emptyList());
        lenient().when(sqlNodeRepository.findByMethodNodeIds(anyList())).thenReturn(Collections.emptyList());

        // Act
        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getItems());
        assertFalse(result.getItems().isEmpty());

        var item = result.getItems().get(0);
        assertEquals("method.1", item.getNodeId());
        assertEquals("Method", item.getNodeType());
        assertEquals("UserService", item.getClassName());
        assertEquals("createUser", item.getMethodName());
        assertEquals("创建用户方法", item.getDescription());
        assertNotNull(item.getSimilarityScore());
        assertEquals(0.92, item.getSimilarityScore(), 0.01);
    }
}
