package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.neo4j.config.RerankProperties;
import com.huawei.hisi.neo4j.model.MethodNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RerankService 单元测试。
 * 覆盖：正常重排、空/未配置降级、异常降级、响应解析。
 */
class RerankServiceTest {

    private RerankProperties properties;
    private ProxyConfig proxyConfig;
    private RestTemplate restTemplate;
    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        properties = new RerankProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://rerank.local");
        properties.setModel("bge-reranker-v2-m3");
        properties.setApiKey("test-key");

        restTemplate = Mockito.mock(RestTemplate.class);
        proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getCurrentRestTemplate()).thenReturn(restTemplate);

        rerankService = new RerankService(properties, proxyConfig);
    }

    private MethodNode node(String id, String desc) {
        return MethodNode.builder().nodeId(id).description(desc).build();
    }

    @Test
    @DisplayName("正常 rerank：解析 relevance_score 并映射回 nodeId")
    void rerank_returnsScores() {
        List<MethodNode> candidates = List.of(node("n1", "支付回调处理"), node("n2", "订单状态更新"));
        String body = "{\"results\":[{\"index\":1,\"relevance_score\":0.98},{\"index\":0,\"relevance_score\":0.61}]}";
        Mockito.when(restTemplate.exchange(
                        Mockito.anyString(),
                        Mockito.eq(org.springframework.http.HttpMethod.POST),
                        Mockito.any(),
                        Mockito.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok(body));

        Map<String, Double> scores = rerankService.rerank("支付回调", candidates);

        assertEquals(2, scores.size());
        assertEquals(0.98, scores.get("n2"), 1e-9);
        assertEquals(0.61, scores.get("n1"), 1e-9);
    }

    @Test
    @DisplayName("降级：base-url 未配置时返回空 Map")
    void rerank_noBaseUrl_returnsEmpty() {
        properties.setBaseUrl("");
        Map<String, Double> scores = rerankService.rerank("q", List.of(node("n1", "d")));
        assertTrue(scores.isEmpty());
    }

    @Test
    @DisplayName("降级：HTTP 异常时返回空 Map，不抛")
    void rerank_httpError_returnsEmpty() {
        Mockito.when(restTemplate.exchange(
                        Mockito.anyString(),
                        Mockito.eq(org.springframework.http.HttpMethod.POST),
                        Mockito.any(),
                        Mockito.<Class<String>>any()))
                .thenThrow(new RuntimeException("connection refused"));
        Map<String, Double> scores = rerankService.rerank("q", List.of(node("n1", "d")));
        assertTrue(scores.isEmpty());
    }

    @Test
    @DisplayName("降级：响应缺少 results 字段返回空 Map")
    void rerank_malformedResponse_returnsEmpty() {
        Mockito.when(restTemplate.exchange(
                        Mockito.anyString(),
                        Mockito.eq(org.springframework.http.HttpMethod.POST),
                        Mockito.any(),
                        Mockito.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok("{\"foo\":1}"));
        Map<String, Double> scores = rerankService.rerank("q", List.of(node("n1", "d")));
        assertTrue(scores.isEmpty());
    }

    @Test
    @DisplayName("空候选列表返回空 Map")
    void rerank_emptyCandidates_returnsEmpty() {
        Map<String, Double> scores = rerankService.rerank("q", List.of());
        assertTrue(scores.isEmpty());
    }

    @Test
    @DisplayName("documentText：description 为 null 时降级为 className.methodName(signature)")
    void rerank_documentFallback_usesSignature() {
        MethodNode noDesc = MethodNode.builder()
                .nodeId("n1")
                .className("com.example.OrderService")
                .methodName("updateStatus")
                .signature("String")
                .build();
        // 通过正常响应路径间接验证 documentText 不抛异常
        String body = "{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}";
        Mockito.when(restTemplate.exchange(
                        Mockito.anyString(),
                        Mockito.eq(org.springframework.http.HttpMethod.POST),
                        Mockito.any(),
                        Mockito.<Class<String>>any()))
                .thenReturn(ResponseEntity.ok(body));

        Map<String, Double> scores = rerankService.rerank("q", List.of(noDesc));
        assertEquals(1, scores.size());
        assertEquals(0.5, scores.get("n1"), 1e-9);
    }
}
