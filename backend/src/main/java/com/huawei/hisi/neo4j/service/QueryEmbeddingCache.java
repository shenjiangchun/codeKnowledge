package com.huawei.hisi.neo4j.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 查询向量缓存
 * 使用 Caffeine 缓存查询文本的嵌入向量，避免重复调用 Embedding API
 *
 * 缓存配置：
 * - 最大条目数：1000
 * - 过期时间：1小时
 * - key：查询文本的 MD5 哈希
 * - value：嵌入向量 float[]
 */
@Component
@Slf4j
public class QueryEmbeddingCache {

    private final Cache<String, float[]> cache;

    public QueryEmbeddingCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    /**
     * 获取缓存的向量，如果不存在则通过 embeddingService 生成
     *
     * @param queryText       查询文本
     * @param embeddingService 嵌入向量服务
     * @return 查询文本的嵌入向量
     */
    public float[] getOrGenerate(String queryText, EmbeddingService embeddingService) {
        String key = md5Hash(queryText);
        float[] cached = cache.getIfPresent(key);
        if (cached != null) {
            log.debug("[QueryEmbeddingCache] 命中缓存: queryText='{}...'", truncate(queryText, 20));
            return cached;
        }

        log.debug("[QueryEmbeddingCache] 缓存未命中，生成向量: queryText='{}...'", truncate(queryText, 20));
        float[] embedding = embeddingService.generateEmbedding(queryText);
        cache.put(key, embedding);
        return embedding;
    }

    /**
     * 手动放入缓存
     *
     * @param queryText 查询文本
     * @param embedding 嵌入向量
     */
    public void put(String queryText, float[] embedding) {
        String key = md5Hash(queryText);
        cache.put(key, embedding);
    }

    /**
     * 检查缓存中是否存在
     *
     * @param queryText 查询文本
     * @return 是否存在缓存
     */
    public boolean contains(String queryText) {
        String key = md5Hash(queryText);
        return cache.getIfPresent(key) != null;
    }

    /**
     * 清空缓存
     */
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("[QueryEmbeddingCache] 缓存已清空");
    }

    /**
     * 获取缓存统计信息
     *
     * @return 缓存条目数
     */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * 计算文本的 MD5 哈希
     *
     * @param text 输入文本
     * @return MD5 哈希的十六进制字符串
     */
    private static String md5Hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // MD5 算法一定存在，这里不应该发生
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }

    /**
     * 截断文本用于日志输出
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
