package com.huawei.hisi.neo4j.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.neo4j.config.RerankProperties;
import com.huawei.hisi.neo4j.model.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 rerank 精排服务。
 *
 * <p>对多路 RRF 融合后的候选做相关性精排：POST OpenAI 兼容 /rerank 端点，
 * 返回每个候选的 relevance_score。任何异常（网络/超时/非 200/解析失败）均降级为
 * 空 Map，由调用方按 RRF 原序返回，不中断检索。</p>
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RerankProperties properties;
    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RerankService(RerankProperties properties, ProxyConfig proxyConfig) {
        this.properties = properties;
        this.proxyConfig = proxyConfig;
    }

    /**
     * 对候选方法做 rerank 精排。
     *
     * @param query      原始自然语言查询
     * @param candidates 待重排的候选方法（已按 RRF 降序）
     * @return nodeId → relevance_score（rerank 分）；异常时返回空 Map
     */
    public Map<String, Double> rerank(String query, List<MethodNode> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                || properties.getModel() == null || properties.getModel().isBlank()) {
            log.warn("[Rerank] base-url/model 未配置，跳过 rerank");
            return Map.of();
        }

        try {
            long start = System.currentTimeMillis();

            List<String> documents = candidates.stream()
                    .map(this::documentText)
                    .collect(java.util.stream.Collectors.toList());

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", properties.getModel());
            requestBody.put("query", query);
            requestBody.putArray("documents").addAll(
                    documents.stream().map(objectMapper.getNodeFactory()::textNode)
                            .collect(java.util.stream.Collectors.toList()));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                headers.setBearerAuth(properties.getApiKey());
            }

            String url = properties.getBaseUrl() + "/rerank";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            RestTemplate rt = proxyConfig.getCurrentRestTemplate();
            ResponseEntity<String> response = rt.exchange(url, HttpMethod.POST, entity, String.class);

            Map<String, Double> scores = parseScores(response.getBody(), candidates);
            long costMs = System.currentTimeMillis() - start;
            log.info("[Rerank] enabled, model={}, topK={}, cost={}ms", properties.getModel(), candidates.size(), costMs);
            return scores;
        } catch (Exception e) {
            // 降级：rerank 失败不影响检索主流程，按 RRF 原序返回
            log.warn("[Rerank] 调用失败，降级为不重排: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 构建候选文档文本：description 优先，null 时降级为 className.methodName(signature)。 */
    private String documentText(MethodNode node) {
        if (node.getDescription() != null && !node.getDescription().isBlank()) {
            return node.getDescription();
        }
        String signature = node.getSignature() != null ? node.getSignature() : "";
        return (node.getClassName() != null ? node.getClassName() : "")
                + "." + (node.getMethodName() != null ? node.getMethodName() : "")
                + (signature.isEmpty() ? "" : "(" + signature + ")");
    }

    /** 解析 rerank 响应 {results:[{index, relevance_score}]}，按 index 映射回候选 nodeId。 */
    private Map<String, Double> parseScores(String body, List<MethodNode> candidates) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return scores;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                log.warn("[Rerank] 响应缺少 results 数组，降级不重排");
                return scores;
            }
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                double score = r.path("relevance_score").asDouble(Double.NaN);
                if (index >= 0 && index < candidates.size() && !Double.isNaN(score)) {
                    MethodNode node = candidates.get(index);
                    if (node.getNodeId() != null) {
                        scores.put(node.getNodeId(), score);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Rerank] 响应解析失败: {}", e.getMessage());
            return Map.of();
        }
        return scores;
    }
}
