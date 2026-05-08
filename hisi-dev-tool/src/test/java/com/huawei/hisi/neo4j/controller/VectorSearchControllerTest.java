package com.huawei.hisi.neo4j.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import com.huawei.hisi.config.GlobalExceptionHandler;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.QueryIntent;
import com.huawei.hisi.neo4j.model.QueryType;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.service.HybridSearchService;
import com.huawei.hisi.neo4j.service.Neo4jVectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * VectorSearchController 测试类
 */
@ExtendWith(MockitoExtension.class)
class VectorSearchControllerTest {

    @Mock
    private HybridSearchService hybridSearchService;

    @InjectMocks
    private VectorSearchController vectorSearchController;

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // 配置MockMvc使用全局异常处理器
        mockMvc = MockMvcBuilders.standaloneSetup(vectorSearchController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testSearch_WithValidRequest() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("查找用户创建方法");
        request.setProjectPath("/project/test");

        QueryIntent intent = QueryIntent.builder()
                .entity("UserService")
                .methodType("create")
                .build();

        MethodNode method1 = MethodNode.builder()
                .nodeId("method.1")
                .methodName("createUser")
                .className("com.example.UserService")
                .build();

        SearchResult searchResult = SearchResult.builder()
                .query("查找用户创建方法")
                .intent(intent)
                .queryType(QueryType.NATURAL_LANGUAGE)
                .results(Arrays.asList(method1))
                .totalCount(1)
                .costTimeMs(100L)
                .build();

        when(hybridSearchService.hybridSearch(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(searchResult);

        // Act & Assert
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value("查找用户创建方法"))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.costTimeMs").value(100))
                .andExpect(jsonPath("$.data.results[0].methodName").value("createUser"));

        verify(hybridSearchService).hybridSearch(eq("查找用户创建方法"), eq("/project/test"), any(), any(), any(), any());
    }

    @Test
    void testSearch_WithEmptyResults() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("不存在的功能");
        request.setProjectPath("/project/test");

        SearchResult searchResult = SearchResult.builder()
                .query("不存在的功能")
                .queryType(QueryType.NATURAL_LANGUAGE)
                .results(Collections.emptyList())
                .totalCount(0)
                .costTimeMs(50L)
                .build();

        when(hybridSearchService.hybridSearch(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(searchResult);

        // Act & Assert
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.results").isEmpty());

        verify(hybridSearchService).hybridSearch(eq("不存在的功能"), eq("/project/test"), any(), any(), any(), any());
    }

    @Test
    void testSearch_WithMissingQuery() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setProjectPath("/project/test");
        // query is null

        // Act & Assert
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(hybridSearchService, never()).hybridSearch(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void testSearch_WithMissingProjectPath() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("测试查询");
        // projectPath is null

        // Act & Assert
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(hybridSearchService, never()).hybridSearch(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void testSearch_WithEmptyQuery() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("");
        request.setProjectPath("/project/test");

        // Act & Assert - 应该返回 400 (QUERY_TOO_SHORT)
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testSearch_WithQueryTooShort() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("a"); // Only 1 character
        request.setProjectPath("/project/test");

        // Act & Assert - 应该返回 400 (QUERY_TOO_SHORT)
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("搜索内容过短，请输入至少 2 个字符"));
    }

    @Test
    void testSearch_WithComplexQuery() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("在订单微服务中查找处理支付回调的方法");
        request.setProjectPath("/project/order-service");

        QueryIntent intent = QueryIntent.builder()
                .entity("PaymentCallbackHandler")
                .methodType("handleCallback")
                .serviceName("order-service")
                .keywords(Arrays.asList("支付", "回调", "处理"))
                .build();

        MethodNode method1 = MethodNode.builder()
                .nodeId("payment.callback.1")
                .methodName("handlePaymentCallback")
                .className("com.order.PaymentCallbackHandler")
                .serviceName("order-service")
                .build();

        MethodNode method2 = MethodNode.builder()
                .nodeId("payment.callback.2")
                .methodName("processPaymentNotification")
                .className("com.order.PaymentService")
                .build();

        SearchResult searchResult = SearchResult.builder()
                .query("在订单微服务中查找处理支付回调的方法")
                .intent(intent)
                .queryType(QueryType.NATURAL_LANGUAGE)
                .results(Arrays.asList(method1, method2))
                .totalCount(2)
                .costTimeMs(150L)
                .build();

        when(hybridSearchService.hybridSearch(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(searchResult);

        // Act & Assert
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.intent.serviceName").value("order-service"))
                .andExpect(jsonPath("$.data.results.length()").value(2));
    }

    @Test
    void testSearch_WithIntentNull() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("模糊查询");
        request.setProjectPath("/project/test");

        SearchResult searchResult = SearchResult.builder()
                .query("模糊查询")
                .intent(null)
                .queryType(QueryType.NATURAL_LANGUAGE)
                .results(Collections.emptyList())
                .totalCount(0)
                .costTimeMs(20L)
                .build();

        when(hybridSearchService.hybridSearch(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(searchResult);

        // Act & Assert
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent").doesNotExist());
    }

    @Test
    void testSearch_VerifyResponseStructure() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("验证响应结构");
        request.setProjectPath("/project/test");

        QueryIntent intent = QueryIntent.builder()
                .entity("TestService")
                .methodType("verify")
                .keywords(Arrays.asList("验证", "结构"))
                .build();

        MethodNode method = MethodNode.builder()
                .nodeId("test.verify.1")
                .className("com.test.TestService")
                .methodName("verifyStructure")
                .signature("void verifyStructure()")
                .filePath("/src/test/TestService.java")
                .startLine(100)
                .endLine(120)
                .build();

        SearchResult searchResult = SearchResult.builder()
                .query("验证响应结构")
                .intent(intent)
                .queryType(QueryType.NATURAL_LANGUAGE)
                .results(Arrays.asList(method))
                .totalCount(1)
                .costTimeMs(80L)
                .build();

        when(hybridSearchService.hybridSearch(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(searchResult);

        // Act & Assert - 验证完整的响应结构
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").exists())
                .andExpect(jsonPath("$.data.intent").exists())
                .andExpect(jsonPath("$.data.intent.entity").exists())
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.totalCount").exists())
                .andExpect(jsonPath("$.data.costTimeMs").exists())
                .andExpect(jsonPath("$.data.results[0].nodeId").value("test.verify.1"))
                .andExpect(jsonPath("$.data.results[0].className").value("com.test.TestService"))
                .andExpect(jsonPath("$.data.results[0].methodName").value("verifyStructure"))
                .andExpect(jsonPath("$.data.results[0].filePath").value("/src/test/TestService.java"));
    }

    @Test
    void testSearch_WithServiceException() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("触发异常");
        request.setProjectPath("/project/test");

        when(hybridSearchService.hybridSearch(anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("服务异常"));

        // Act & Assert - 异常应被处理返回500或适当的错误响应
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void testSearch_WithLimitAndGraphDepth() throws Exception {
        // Arrange
        SearchRequest request = new SearchRequest();
        request.setQuery("带参数查询");
        request.setProjectPath("/project/test");
        request.setLimit(5);
        request.setGraphDepth(3);

        SearchResult searchResult = SearchResult.builder()
                .query("带参数查询")
                .queryType(QueryType.NATURAL_LANGUAGE)
                .results(Collections.emptyList())
                .totalCount(0)
                .costTimeMs(50L)
                .build();

        when(hybridSearchService.hybridSearch(anyString(), anyString(), any(), any(), eq(5), eq(3)))
                .thenReturn(searchResult);

        // Act & Assert
        mockMvc.perform(post("/api/vector-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(hybridSearchService).hybridSearch("带参数查询", "/project/test", List.of("/project/test"), null, 5, 3);
    }

    /**
     * SearchRequest 内部类用于测试
     */
    public static class SearchRequest {
        private String query;
        private String projectPath;
        private Integer limit;
        private Integer graphDepth;

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
    }
}
