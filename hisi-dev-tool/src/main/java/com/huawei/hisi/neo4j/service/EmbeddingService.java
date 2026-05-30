package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.service.UnifiedEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 嵌入向量服务（门面层）
 * 委托 UnifiedEmbeddingService 完成实际向量生成。
 * 保留此门面是为了不改变下游消费者（HybridSearchService, VectorWriter 等）的注入点。
 */
@Service
@Slf4j
public class EmbeddingService {

    private final UnifiedEmbeddingService embeddingService;

    /**
     * 当前实际使用的 embedding 维度
     */
    private final int embeddingDimension;

    public EmbeddingService(UnifiedEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;

        if (embeddingService.isAvailable()) {
            this.embeddingDimension = embeddingService.getEmbeddingDimension();
            log.info("[EmbeddingService] 统一向量服务就绪: dimension={}", embeddingDimension);
        } else {
            this.embeddingDimension = 4096;
            log.warn("[EmbeddingService] 向量服务不可用（API Key 未配置），默认维度 {}", embeddingDimension);
        }
    }

    /**
     * 生成文本的嵌入向量
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.isEmpty()) {
            return createZeroVector();
        }

        float[] embedding = embeddingService.generateEmbedding(text);
        log.info("[EMBEDDING-API] dim={}, text='{}'", embedding.length, truncateText(text, 30));
        return embedding;
    }

    /**
     * 获取当前使用的嵌入维度
     */
    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    /**
     * 检查嵌入服务是否可用
     */
    public boolean isEmbeddingAvailable() {
        return embeddingService.isAvailable();
    }

    /**
     * 创建零向量（默认向量，第一个分量为 1）
     */
    private float[] createZeroVector() {
        float[] zero = new float[embeddingDimension];
        zero[0] = 1.0f;
        return zero;
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    /**
     * 批量生成嵌入向量
     */
    public List<float[]> batchGenerateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(generateEmbedding(text));
        }
        return results;
    }

    /**
     * 计算两个向量的余弦相似度
     */
    public static float cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
            return 0;
        }
        float dotProduct = 0, norm1 = 0, norm2 = 0;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        if (norm1 == 0 || norm2 == 0) return 0;
        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
