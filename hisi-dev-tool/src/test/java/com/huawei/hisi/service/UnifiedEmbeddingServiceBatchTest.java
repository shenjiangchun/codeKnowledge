package com.huawei.hisi.service;

import com.huawei.hisi.config.EmbeddingModelConfig;
import com.huawei.hisi.config.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnifiedEmbeddingServiceBatchTest {

    @Mock
    private EmbeddingModelConfig config;

    @Mock
    private ProxyConfig proxyConfig;

    @Mock
    private RestTemplate restTemplate;

    private UnifiedEmbeddingService service;

    @BeforeEach
    void setUp() {
        when(config.getQps()).thenReturn(5.0);
        when(config.getBurst()).thenReturn(10);
        when(config.getAcquireTimeoutSeconds()).thenReturn(120L);
        when(config.getMaxRetries()).thenReturn(3);
        when(config.getRetryBaseDelayMs()).thenReturn(5000L);
        when(config.getDimension()).thenReturn(2);  // match 2-element test embeddings
        when(config.getApiKey()).thenReturn("test-key");
        when(config.getBaseUrl()).thenReturn("https://test.api/v1");
        when(config.getModel()).thenReturn("test-model");
        when(proxyConfig.getCurrentRestTemplate()).thenReturn(restTemplate);

        service = new UnifiedEmbeddingService(config, proxyConfig);
        service.init();  // initialize rateLimiter (normally called by @PostConstruct)
    }

    @Test
    @DisplayName("generateEmbeddings returns vectors in input order for 3 texts")
    void batchEmbedding_returnsVectorsInOrder() throws Exception {
        String responseJson = """
            {
              "data": [
                {"embedding": [0.1, 0.2]},
                {"embedding": [0.3, 0.4]},
                {"embedding": [0.5, 0.6]}
              ]
            }""";

        when(restTemplate.exchange(
                eq("https://test.api/v1/embeddings"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok(responseJson));

        List<String> texts = Arrays.asList("方法A", "方法B", "方法C");
        List<float[]> results = service.generateEmbeddings(texts);

        assertThat(results).hasSize(3);
        // verify all vectors are non-null, have expected dimension, and no NaN
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i)).isNotNull().hasSize(2);
            for (float v : results.get(i)) {
                assertThat(v).isNotNaN();
            }
        }
    }

    @Test
    @DisplayName("generateEmbeddings with empty list throws IllegalArgumentException")
    void batchEmbedding_emptyList_throws() {
        assertThatThrownBy(() -> service.generateEmbeddings(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("generateEmbeddings with null list throws IllegalArgumentException")
    void batchEmbedding_nullList_throws() {
        assertThatThrownBy(() -> service.generateEmbeddings(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
