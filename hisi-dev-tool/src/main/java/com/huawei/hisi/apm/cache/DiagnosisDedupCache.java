package com.huawei.hisi.apm.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * In-memory dedup cache for concurrent diagnose pipelines.
 * <p>
 * Keys are {@code traceId + "|" + projectPath}; values are the {@code reportId}
 * of the diagnose currently in flight (or recently completed) for that pair.
 * Used to coalesce concurrent diagnose requests so that two callers asking to
 * diagnose the same {@code (traceId, projectPath)} share a single pipeline.
 * <p>
 * Backed by a Caffeine cache with {@code maximumSize(2_000)} and
 * {@code expireAfterWrite(30 minutes)}. Atomic dedup is provided by
 * {@link java.util.concurrent.ConcurrentMap#computeIfAbsent}.
 *
 * @author HiSi DevTool Team
 */
@Component
public class DiagnosisDedupCache {

    private static final Logger LOG = LoggerFactory.getLogger(DiagnosisDedupCache.class);

    private static final long MAX_SIZE = 2_000L;
    private static final long EXPIRE_MINUTES = 30L;
    private static final String CACHE_NAME = "apm.diagnose.dedup";

    private final Cache<String, String> cache;

    /**
     * Construct the dedup cache and wire Caffeine stats to Micrometer.
     *
     * @param meterRegistry the Micrometer registry for metrics export
     */
    public DiagnosisDedupCache(MeterRegistry meterRegistry) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(EXPIRE_MINUTES, TimeUnit.MINUTES)
                .recordStats()
                .build();

        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME);
        LOG.info("DiagnosisDedupCache initialised — maxSize={}, expireAfterWrite={}m",
                MAX_SIZE, EXPIRE_MINUTES);
    }

    /**
     * Look up the existing reportId for a (traceId, projectPath) pair.
     *
     * @param traceId     the W3C trace-id under diagnosis
     * @param projectPath absolute project path
     * @return the existing reportId wrapped in an {@link Optional}, empty if absent
     */
    public Optional<String> getExistingReportId(String traceId, String projectPath) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(projectPath, "projectPath");
        return Optional.ofNullable(cache.getIfPresent(dedupKey(traceId, projectPath)));
    }

    /**
     * Atomically register {@code newReportId} for the pair if absent, or return
     * the existing reportId otherwise. Callers compare the returned value
     * against {@code newReportId} to decide whether they actually started the
     * pipeline (returned == newReportId) or are sharing an in-flight one.
     *
     * @param traceId     the W3C trace-id under diagnosis
     * @param projectPath absolute project path
     * @param newReportId candidate reportId to install if no entry exists
     * @return the reportId now associated with the pair (either {@code newReportId}
     *         or the pre-existing one)
     */
    public String registerOrGet(String traceId, String projectPath, String newReportId) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(newReportId, "newReportId");
        String key = dedupKey(traceId, projectPath);
        return cache.asMap().computeIfAbsent(key, k -> newReportId);
    }

    /**
     * Explicitly invalidate the dedup entry for a pair. Used when callers
     * request {@code forceRefresh=true} or when a pipeline ends in a state
     * that should not be deduplicated against.
     *
     * @param traceId     the W3C trace-id
     * @param projectPath absolute project path
     */
    public void invalidate(String traceId, String projectPath) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(projectPath, "projectPath");
        cache.invalidate(dedupKey(traceId, projectPath));
    }

    /**
     * Number of dedup entries currently cached.
     *
     * @return the entry count
     */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * Build the composite cache key for a (traceId, projectPath) pair.
     *
     * @param traceId     the W3C trace-id
     * @param projectPath absolute project path
     * @return the composite key {@code traceId + "|" + projectPath}
     */
    private String dedupKey(String traceId, String projectPath) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(projectPath, "projectPath");
        return traceId + "|" + projectPath;
    }
}
