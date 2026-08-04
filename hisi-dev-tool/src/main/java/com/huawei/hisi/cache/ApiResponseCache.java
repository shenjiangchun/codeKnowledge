package com.huawei.hisi.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * API Response Cache using Caffeine for frequently called API responses.
 * Provides configurable TTL and maximum size for cached entries.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Component
public class ApiResponseCache {

    private static final Logger LOG = LoggerFactory.getLogger(ApiResponseCache.class);

    /**
     * Default cache with 5-minute TTL and max 1000 entries.
     */
    private final Cache<String, Object> defaultCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .recordStats()
        .build();

    /**
     * Short-lived cache with 3-minute TTL for frequently changing data.
     */
    private final Cache<String, Object> shortLivedCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(3, TimeUnit.MINUTES)
        .recordStats()
        .build();

    /**
     * Long-lived cache with 10-minute TTL for relatively stable data.
     */
    private final Cache<String, Object> longLivedCache = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .recordStats()
        .build();

    /**
     * Get a value from the default cache (5-minute TTL).
     *
     * @param key  the cache key
     * @param type the expected type of the cached value
     * @param <T>  the type parameter
     * @return the cached value or null if not found
     */
    public <T> T get(String key, Class<T> type) {
        return getFromCache(defaultCache, key, type);
    }

    /**
     * Get a value from the cache, computing it if absent using the provided loader.
     *
     * @param key    the cache key
     * @param type   the expected type of the cached value
     * @param loader the function to compute the value if absent
     * @param <T>    the type parameter
     * @return the cached or computed value
     */
    public <T> T get(String key, Class<T> type, Function<String, T> loader) {
        Object value = defaultCache.get(key, k -> {
            LOG.debug("Cache miss for key: {}, computing value", k);
            return loader.apply(k);
        });
        return value != null ? type.cast(value) : null;
    }

    /**
     * Put a value into the default cache (5-minute TTL).
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    public void put(String key, Object value) {
        defaultCache.put(key, value);
        LOG.debug("Cached value for key: {}", key);
    }

    /**
     * Get a value from the short-lived cache (3-minute TTL).
     *
     * @param key  the cache key
     * @param type the expected type of the cached value
     * @param <T>  the type parameter
     * @return the cached value or null if not found
     */
    public <T> T getShortLived(String key, Class<T> type) {
        return getFromCache(shortLivedCache, key, type);
    }

    /**
     * Get a value from the short-lived cache, computing it if absent.
     *
     * @param key    the cache key
     * @param type   the expected type of the cached value
     * @param loader the function to compute the value if absent
     * @param <T>    the type parameter
     * @return the cached or computed value
     */
    public <T> T getShortLived(String key, Class<T> type, Function<String, T> loader) {
        Object value = shortLivedCache.get(key, k -> {
            LOG.debug("Short-lived cache miss for key: {}, computing value", k);
            return loader.apply(k);
        });
        return value != null ? type.cast(value) : null;
    }

    /**
     * Put a value into the short-lived cache (3-minute TTL).
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    public void putShortLived(String key, Object value) {
        shortLivedCache.put(key, value);
        LOG.debug("Cached value in short-lived cache for key: {}", key);
    }

    /**
     * Get a value from the long-lived cache (10-minute TTL).
     *
     * @param key  the cache key
     * @param type the expected type of the cached value
     * @param <T>  the type parameter
     * @return the cached value or null if not found
     */
    public <T> T getLongLived(String key, Class<T> type) {
        return getFromCache(longLivedCache, key, type);
    }

    /**
     * Get a value from the long-lived cache, computing it if absent.
     *
     * @param key    the cache key
     * @param type   the expected type of the cached value
     * @param loader the function to compute the value if absent
     * @param <T>    the type parameter
     * @return the cached or computed value
     */
    public <T> T getLongLived(String key, Class<T> type, Function<String, T> loader) {
        Object value = longLivedCache.get(key, k -> {
            LOG.debug("Long-lived cache miss for key: {}, computing value", k);
            return loader.apply(k);
        });
        return value != null ? type.cast(value) : null;
    }

    /**
     * Put a value into the long-lived cache (10-minute TTL).
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    public void putLongLived(String key, Object value) {
        longLivedCache.put(key, value);
        LOG.debug("Cached value in long-lived cache for key: {}", key);
    }

    /**
     * Evict a specific key from all caches.
     *
     * @param key the cache key to evict
     */
    public void evict(String key) {
        defaultCache.invalidate(key);
        shortLivedCache.invalidate(key);
        longLivedCache.invalidate(key);
        LOG.debug("Evicted key from all caches: {}", key);
    }

    /**
     * Clear all caches.
     */
    public void clear() {
        defaultCache.invalidateAll();
        shortLivedCache.invalidateAll();
        longLivedCache.invalidateAll();
        LOG.info("Cleared all caches");
    }

    /**
     * Get cache statistics for monitoring.
     *
     * @return a string representation of cache statistics
     */
    public String getStats() {
        return String.format(
            "Default Cache: %s, Short-lived Cache: %s, Long-lived Cache: %s",
            defaultCache.stats().toString(),
            shortLivedCache.stats().toString(),
            longLivedCache.stats().toString()
        );
    }

    /**
     * Helper method to get a value from a specific cache.
     */
    @SuppressWarnings("unchecked")
    private <T> T getFromCache(Cache<String, Object> cache, String key, Class<T> type) {
        Object value = cache.getIfPresent(key);
        if (value != null) {
            LOG.debug("Cache hit for key: {}", key);
            return type.cast(value);
        }
        LOG.debug("Cache miss for key: {}", key);
        return null;
    }
}