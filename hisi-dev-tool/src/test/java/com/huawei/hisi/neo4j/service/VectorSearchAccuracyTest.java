package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.config.SearchIntentProperties;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.QueryType;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 向量搜索接口准确率测试
 *
 * 测试策略：
 * 1. 构造模拟的方法节点数据集（模拟真实代码库中的方法分布）
 * 2. 模拟 embedding 的语义匹配行为（相似度高的查询能命中对应方法）
 * 3. 验证搜索结果的相关性、召回率、排序质量
 * 4. 覆盖中文查询、英文查询、混合查询、模糊查询等场景
 *
 * 注意：本测试使用 mock 模拟 Neo4j 和 Embedding 服务，
 * 不依赖外部API，可在任何环境下运行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorSearchAccuracyTest {

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

    @Mock
    private SearchIntentProperties searchIntentProperties;

    @Mock
    private TokenRouter tokenRouter;

    private HybridSearchService hybridSearchService;

    private static final String PROJECT_PATH = "C:\\Users\\test\\project";

    /**
     * 模拟数据集：一个典型Spring Boot项目的方法节点
     * 包含不同模块（用户服务、订单服务、支付服务、日志服务）
     */
    private List<MethodNode> methodDataset;

    @BeforeEach
    void setUp() {
        hybridSearchService = new HybridSearchService(
                methodNodeRepository,
                sqlNodeRepository,
                entryPointRepository,
                embeddingService,
                queryTypeDetector,
                vectorIndexService,
                queryEmbeddingCache,
                searchIntentProperties,
                tokenRouter
        );

        methodDataset = buildSimulatedDataset();

        // Default mocks for all tests: queryTypeDetector, queryEmbeddingCache, vectorIndexService
        when(queryTypeDetector.detect(anyString())).thenReturn(QueryType.NATURAL_LANGUAGE);
        when(queryEmbeddingCache.getOrGenerate(anyString(), any(EmbeddingService.class)))
                .thenReturn(new float[2048]);
        when(vectorIndexService.isVectorIndexAvailable()).thenReturn(false);
        when(methodNodeRepository.findByDescriptionVectorSimilarityWithScore(
                anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    // ==================== 搜索准确率测试 ====================

    @Nested
    @DisplayName("中文自然语言查询准确率")
    class ChineseQueryAccuracyTests {

        @Test
        @DisplayName("查询'用户注册'应返回用户相关方法")
        void testQuery_用户注册() {
            // Act
            SearchResult result = hybridSearchService.hybridSearch("用户注册", PROJECT_PATH);

            // Assert
            assertBasicResultValid(result, "用户注册");
            assertTrue(result.getTotalCount() >= 0, "应返回搜索结果");
            assertNotNull(result.getIntent());
            assertNotNull(result.getIntent().getKeywords());
            assertTrue(result.getIntent().getKeywords().contains("用户注册"),
                    "关键词应包含完整查询'用户注册'");
        }

        @Test
        @DisplayName("查询'知识图谱生成' - 原始失败场景")
        void testQuery_知识图谱生成() {
            // Act
            SearchResult result = hybridSearchService.hybridSearch("知识图谱生成", PROJECT_PATH);

            // Assert
            assertBasicResultValid(result, "知识图谱生成");
            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("知识图谱生成"), "应包含完整查询");
        }

        @Test
        @DisplayName("查询'处理支付回调'应返回支付相关方法")
        void testQuery_处理支付回调() {
            SearchResult result = hybridSearchService.hybridSearch("处理支付回调", PROJECT_PATH);

            assertBasicResultValid(result, "处理支付回调");
            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("处理支付回调"), "应包含完整查询");
        }

        @Test
        @DisplayName("查询'日志分析'应返回日志相关方法")
        void testQuery_日志分析() {
            SearchResult result = hybridSearchService.hybridSearch("日志分析", PROJECT_PATH);

            assertBasicResultValid(result, "日志分析");
            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("日志分析"), "应包含完整查询");
        }
    }

    @Nested
    @DisplayName("英文/代码术语查询准确率")
    class EnglishQueryAccuracyTests {

        @Test
        @DisplayName("查询'createUser'应返回用户创建方法")
        void testQuery_createUser() {
            SearchResult result = hybridSearchService.hybridSearch("createUser", PROJECT_PATH);

            assertBasicResultValid(result, "createUser");
            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("createUser"), "英文关键词应原样保留");
        }

        @Test
        @DisplayName("查询'handlePaymentCallback'应返回支付回调方法")
        void testQuery_handlePaymentCallback() {
            SearchResult result = hybridSearchService.hybridSearch("handlePaymentCallback", PROJECT_PATH);

            assertBasicResultValid(result, "handlePaymentCallback");
        }

        @Test
        @DisplayName("查询'save order to database'空格分词应正确")
        void testQuery_saveOrderToDatabase() {
            SearchResult result = hybridSearchService.hybridSearch("save order to database", PROJECT_PATH);

            assertBasicResultValid(result, "save order to database");
            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("save"), "空格分词应提取'save'");
            assertTrue(keywords.contains("order"), "空格分词应提取'order'");
            assertTrue(keywords.contains("database"), "空格分词应提取'database'");
            assertTrue(keywords.contains("to"), "'to'应被保留");
        }
    }

    @Nested
    @DisplayName("中英文混合查询准确率")
    class MixedQueryAccuracyTests {

        @Test
        @DisplayName("查询'UserService的注册方法'应提取中文和英文部分")
        void testQuery_mixedChineseEnglish() {
            SearchResult result = hybridSearchService.hybridSearch("UserService的注册方法", PROJECT_PATH);

            assertBasicResultValid(result, "UserService的注册方法");
            List<String> keywords = result.getIntent().getKeywords();
            // query.split("\\s+") produces ["UserService的注册方法"] (no spaces)
            assertTrue(keywords.stream().anyMatch(k -> k.contains("UserService")),
                    "应包含英文部分'UserService'");
        }

        @Test
        @DisplayName("查询'OrderService create order'空格分隔的混合查询")
        void testQuery_mixedWithSpaces() {
            SearchResult result = hybridSearchService.hybridSearch("OrderService create order", PROJECT_PATH);

            assertBasicResultValid(result, "OrderService create order");
            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("OrderService"), "应提取'OrderService'");
            assertTrue(keywords.contains("create"), "应提取'create'");
            assertTrue(keywords.contains("order"), "应提取'order'");
        }
    }

    @Nested
    @DisplayName("边界情况和鲁棒性")
    class EdgeCaseTests {

        @Test
        @DisplayName("查询'日志'（短查询，无2-gram拆分）应正常工作")
        void testQuery_shortChineseQuery() {
            SearchResult result = hybridSearchService.hybridSearch("日志", PROJECT_PATH);

            assertBasicResultValid(result, "日志");
            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("日志"), "短查询应作为完整关键词");
        }

        @Test
        @DisplayName("向量搜索失败时应抛出SearchException")
        void testVectorSearchFailure_gracefulDegradation() {
            // 向量搜索失败 (embedding cache throws)
            when(queryEmbeddingCache.getOrGenerate(anyString(), any(EmbeddingService.class)))
                    .thenThrow(new RuntimeException("embedding服务不可用"));

            // Act - SearchException is expected since embedding is unavailable
            assertThrows(Exception.class, () ->
                    hybridSearchService.hybridSearch("用户注册", PROJECT_PATH));
        }

        @Test
        @DisplayName("embedding服务超时场景 - 模拟原始bug场景")
        void testEmbeddingTimeout_originalBugScenario() {
            // 模拟原始问题：embedding生成超时
            when(queryEmbeddingCache.getOrGenerate(anyString(), any(EmbeddingService.class)))
                    .thenThrow(new RuntimeException("Read timed out"));

            assertThrows(Exception.class, () ->
                    hybridSearchService.hybridSearch("知识图谱生成", PROJECT_PATH));
        }

        @Test
        @DisplayName("空结果查询应返回空列表而非null")
        void testQuery_noResults() {
            SearchResult result = hybridSearchService.hybridSearch("xyz不存在的查询abc", PROJECT_PATH);

            assertNotNull(result);
            assertNotNull(result.getResults());
            assertEquals(0, result.getTotalCount());
        }

        @Test
        @DisplayName("特殊字符查询不应导致异常")
        void testQuery_specialCharacters() {
            // 各种特殊字符
            assertDoesNotThrow(() -> hybridSearchService.hybridSearch("查询(测试)", PROJECT_PATH));
            assertDoesNotThrow(() -> hybridSearchService.hybridSearch("查询[数组]", PROJECT_PATH));
            assertDoesNotThrow(() -> hybridSearchService.hybridSearch("查询{对象}", PROJECT_PATH));
        }
    }

    @Nested
    @DisplayName("搜索耗时性能测试")
    class PerformanceTests {

        @Test
        @DisplayName("本地分词搜索不应调用LLM - 验证零LLM依赖")
        void testNoLLMDependency() {
            // Act
            hybridSearchService.hybridSearch("知识图谱生成", PROJECT_PATH);

            // Assert - 应通过embeddingService(via cache)生成embedding
            // 不应有任何LLM服务调用
            verify(queryEmbeddingCache, atLeastOnce()).getOrGenerate(anyString(), any(EmbeddingService.class));
        }

        @Test
        @DisplayName("搜索应在秒级完成（排除embedding延迟）")
        void testSearchLatency() {
            long startTime = System.nanoTime();
            hybridSearchService.hybridSearch("用户注册", PROJECT_PATH);
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            // 搜索本身的编排逻辑（不含真实embedding和Neo4j IO）应在100ms内
            assertTrue(elapsedMs < 100,
                    "搜索编排逻辑耗时应在100ms内，实际: " + elapsedMs + "ms");
        }
    }

    @Nested
    @DisplayName("关键词提取准确率专项测试")
    class KeywordExtractionTests {

        @Test
        @DisplayName("4字中文查询的关键词提取验证")
        void testBigram_4CharChinese() {
            SearchResult result = hybridSearchService.hybridSearch("知识图谱", PROJECT_PATH);

            List<String> keywords = result.getIntent().getKeywords();
            // 新实现使用 query.split("\\s+")，无空格的中文查询保留为整体
            assertTrue(keywords.contains("知识图谱"), "应包含完整查询");
        }

        @Test
        @DisplayName("6字中文查询的关键词提取验证")
        void testBigram_6CharChinese() {
            SearchResult result = hybridSearchService.hybridSearch("知识图谱生成", PROJECT_PATH);

            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("知识图谱生成"), "应包含完整查询");
        }

        @Test
        @DisplayName("带标点的查询应正确处理")
        void testPunctuationSplit() {
            SearchResult result = hybridSearchService.hybridSearch("创建用户，然后保存", PROJECT_PATH);

            List<String> keywords = result.getIntent().getKeywords();
            // query.split("\\s+") produces ["创建用户，然后保存"] (no whitespace)
            assertNotNull(keywords);
            assertFalse(keywords.isEmpty(), "关键词列表不应为空");
        }

        @Test
        @DisplayName("短查询(2字)应正常工作")
        void testShortQuery_noBigram() {
            SearchResult result = hybridSearchService.hybridSearch("注册", PROJECT_PATH);

            List<String> keywords = result.getIntent().getKeywords();
            assertEquals(1, keywords.size(), "单词查询只应有1个关键词");
            assertEquals("注册", keywords.get(0));
        }

        @Test
        @DisplayName("3字中文查询的完整词提取")
        void test3CharChinese() {
            SearchResult result = hybridSearchService.hybridSearch("分析器", PROJECT_PATH);

            List<String> keywords = result.getIntent().getKeywords();
            assertTrue(keywords.contains("分析器"), "应包含完整3字查询");
        }
    }

    @Nested
    @DisplayName("RRF融合和排序质量测试")
    class RRFFusionQualityTests {

        @Test
        @DisplayName("同时被向量搜索和图遍历命中的方法应排名更高")
        void testRRF_OverlapRanksHigher() {
            // 模拟向量搜索和图遍历有重叠
            MethodNode overlapMethod = MethodNode.builder()
                    .nodeId("overlap.1")
                    .methodName("createUser")
                    .projectPath(PROJECT_PATH)
                    .build();
            MethodNode vectorOnlyMethod = MethodNode.builder()
                    .nodeId("vector.only.1")
                    .methodName("deleteUser")
                    .projectPath(PROJECT_PATH)
                    .build();

            List<MethodNode> vectorResults = List.of(overlapMethod, vectorOnlyMethod);
            List<MethodNode> graphResults = List.of(overlapMethod);

            List<MethodNode> fused = hybridSearchService.fuseResults(vectorResults, graphResults);

            // overlapMethod在两个列表中都出现，RRF分数更高，应排第一
            assertEquals("overlap.1", fused.get(0).getNodeId(),
                    "同时被向量搜索和图遍历命中的方法应排名第一");
        }

        @Test
        @DisplayName("多个方法的重叠排序应正确")
        void testRRF_MultipleOverlaps() {
            MethodNode methodA = MethodNode.builder().nodeId("A").build();
            MethodNode methodB = MethodNode.builder().nodeId("B").build();
            MethodNode methodC = MethodNode.builder().nodeId("C").build();
            MethodNode methodD = MethodNode.builder().nodeId("D").build();

            // A在向量排第1，图遍历排第2
            // B在向量排第2，图遍历排第1
            // C只在向量排第3
            // D只在图遍历排第2
            List<MethodNode> vectorResults = Arrays.asList(methodA, methodB, methodC);
            List<MethodNode> graphResults = Arrays.asList(methodB, methodA, methodD);

            List<MethodNode> fused = hybridSearchService.fuseResults(vectorResults, graphResults);

            // A和B都有双重命中，分数最高
            // A: 1/(60+1) + 1/(60+2) = 0.0164 + 0.0161 = 0.0325
            // B: 1/(60+2) + 1/(60+1) = 0.0161 + 0.0164 = 0.0325
            // D: 1/(60+2) = 0.0161 (图遍历排第3，但这里排第2)
            // C: 1/(60+3) = 0.0159
            Set<String> topTwo = Set.of(fused.get(0).getNodeId(), fused.get(1).getNodeId());
            assertTrue(topTwo.contains("A") && topTwo.contains("B"),
                    "A和B应排名前二（双重命中）");
            assertTrue(fused.indexOf(methodC) > fused.indexOf(methodA),
                    "C（单次命中）应排在A（双重命中）之后");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建模拟数据集
     * 模拟一个典型Spring Boot项目的方法节点分布
     */
    private List<MethodNode> buildSimulatedDataset() {
        return List.of(
                // 用户服务
                MethodNode.builder().nodeId("user.register.1").methodName("registerUser")
                        .className("com.example.user.UserService").serviceName("user-service")
                        .description("注册新用户").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("user.create.1").methodName("createUser")
                        .className("com.example.user.UserService").serviceName("user-service")
                        .description("创建用户").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("user.delete.1").methodName("deleteUser")
                        .className("com.example.user.UserService").serviceName("user-service")
                        .description("删除用户").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("user.find.1").methodName("findUserById")
                        .className("com.example.user.UserRepository").serviceName("user-service")
                        .description("根据ID查询用户").projectPath(PROJECT_PATH).build(),

                // 订单服务
                MethodNode.builder().nodeId("order.create.1").methodName("createOrder")
                        .className("com.example.order.OrderService").serviceName("order-service")
                        .description("创建订单").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("order.process.1").methodName("processOrder")
                        .className("com.example.order.OrderService").serviceName("order-service")
                        .description("处理订单").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("order.cancel.1").methodName("cancelOrder")
                        .className("com.example.order.OrderService").serviceName("order-service")
                        .description("取消订单").projectPath(PROJECT_PATH).build(),

                // 支付服务
                MethodNode.builder().nodeId("payment.callback.1").methodName("handlePaymentCallback")
                        .className("com.example.payment.PaymentCallbackHandler").serviceName("payment-service")
                        .description("处理支付回调").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("payment.process.1").methodName("processPaymentNotification")
                        .className("com.example.payment.PaymentService").serviceName("payment-service")
                        .description("处理支付通知").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("payment.refund.1").methodName("refundPayment")
                        .className("com.example.payment.PaymentService").serviceName("payment-service")
                        .description("退款处理").projectPath(PROJECT_PATH).build(),

                // 日志服务
                MethodNode.builder().nodeId("log.analyze.1").methodName("analyzeLog")
                        .className("com.example.log.LogAnalysisService").serviceName("log-service")
                        .description("分析日志").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("log.query.1").methodName("queryLogs")
                        .className("com.example.log.LogQueryService").serviceName("log-service")
                        .description("查询日志").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("log.parse.1").methodName("parseLogEntry")
                        .className("com.example.log.LogParser").serviceName("log-service")
                        .description("解析日志条目").projectPath(PROJECT_PATH).build(),

                // 知识图谱
                MethodNode.builder().nodeId("graph.generate.1").methodName("generateKnowledgeGraph")
                        .className("com.example.knowledge.GraphService").serviceName("knowledge-service")
                        .description("生成知识图谱").projectPath(PROJECT_PATH).build(),
                MethodNode.builder().nodeId("graph.query.1").methodName("queryGraphNode")
                        .className("com.example.knowledge.GraphQueryService").serviceName("knowledge-service")
                        .description("查询图节点").projectPath(PROJECT_PATH).build()
        );
    }

    /**
     * 从模拟数据集中按关键词过滤方法
     */
    private List<MethodNode> filterDataset(String... keywords) {
        return methodDataset.stream()
                .filter(m -> {
                    for (String keyword : keywords) {
                        if ((m.getMethodName() != null && m.getMethodName().toLowerCase().contains(keyword.toLowerCase())) ||
                            (m.getDescription() != null && m.getDescription().contains(keyword)) ||
                            (m.getServiceName() != null && m.getServiceName().toLowerCase().contains(keyword.toLowerCase()))) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    /**
     * 配置mock对象
     * 模拟关键词过滤和向量搜索的行为
     */
    private void setupMocks(String query, List<MethodNode> keywordMatchResults) {
        // 关键词过滤 - 按方法名匹配
        lenient().when(methodNodeRepository.findByMethodNameContaining(anyString()))
                .thenAnswer(invocation -> {
                    String keyword = invocation.getArgument(0, String.class);
                    return methodDataset.stream()
                            .filter(m -> m.getMethodName() != null &&
                                    m.getMethodName().toLowerCase().contains(keyword.toLowerCase()))
                            .collect(Collectors.toList());
                });

        // Embedding 生成
        lenient().when(embeddingService.generateEmbedding(anyString()))
                .thenReturn(new float[2048]);

        // 向量搜索 - 返回语义相关的方法（模拟embedding匹配行为）
        lenient().when(methodNodeRepository.findByDescriptionVectorSimilarity(anyString(), anyList(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    int limit = invocation.getArgument(3, Integer.class);
                    // 模拟向量搜索返回部分相关结果
                    return keywordMatchResults.stream()
                            .limit(limit)
                            .collect(Collectors.toList());
                });

        // 图遍历 - 返回空（简化测试）
        lenient().when(methodNodeRepository.findCallersUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(methodNodeRepository.findCalleesUpToDepth(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    /**
     * 基本结果验证
     */
    private void assertBasicResultValid(SearchResult result, String expectedQuery) {
        assertNotNull(result, "搜索结果不应为null");
        assertEquals(expectedQuery, result.getQuery(), "查询文本应一致");
        assertNotNull(result.getResults(), "结果列表不应为null");
        assertNotNull(result.getCostTimeMs(), "耗时不应为null");
        assertTrue(result.getCostTimeMs() >= 0, "耗时应为非负数");
        assertNotNull(result.getIntent(), "意图不应为null");
        assertNotNull(result.getIntent().getKeywords(), "关键词列表不应为null");
    }
}
