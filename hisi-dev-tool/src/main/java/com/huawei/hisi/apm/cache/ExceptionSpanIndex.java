package com.huawei.hisi.apm.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huawei.hisi.apm.model.ApmSpanEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * In-memory index of exception/error spans keyed by traceId.
 * <p>
 * Backed by a Caffeine cache with a 2-hour TTL and a 10 000-entry cap.
 * Supports append semantics: calling {@link #index(ApmSpanEntity)} for the
 * same traceId adds to the existing list. Thread-safe for concurrent ingestion.
 * <p>
 * Micrometer gauges are registered automatically so that hit/miss/eviction
 * rates are observable via Actuator.
 *
 * @author HiSi DevTool Team
 */
@Component
public class ExceptionSpanIndex {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionSpanIndex.class);

    private static final long MAX_SIZE = 10_000L;
    private static final long EXPIRE_HOURS = 2L;
    private static final String CACHE_NAME = "apm.exception.span.index";

    private final Cache<String, List<ApmSpanEntity>> cache;

    /**
     * Construct the index and wire Caffeine stats to Micrometer.
     *
     * @param meterRegistry the Micrometer registry for metrics export
     */
    public ExceptionSpanIndex(MeterRegistry meterRegistry) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(EXPIRE_HOURS, TimeUnit.HOURS)
                .recordStats()
                .build();

        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME);
        LOG.info("ExceptionSpanIndex initialised — maxSize={}, expireAfterWrite={}h",
                MAX_SIZE, EXPIRE_HOURS);
    }

    /**
     * Index a single exception/error span. Appends to the existing list if
     * the traceId is already present.
     * <p>
     * A span is eligible for indexing when its {@code statusCode} equals
     * {@code "ERROR"} <em>or</em> its attributes contain the key
     * {@code "exception.type"}. All other spans are silently skipped.
     *
     * @param span the span to index
     */
    public void index(ApmSpanEntity span) {
        if (!isExceptionSpan(span)) {
            return;
        }

        cache.asMap().compute(span.getTraceId(), (traceId, existing) -> {
            List<ApmSpanEntity> list = (existing != null)
                    ? new ArrayList<>(existing)
                    : new ArrayList<>();
            list.add(span);
            return Collections.unmodifiableList(list);
        });
    }

    /**
     * Retrieve all indexed exception spans for a given traceId.
     *
     * @param traceId the W3C trace-id
     * @return an unmodifiable list; empty if no spans are indexed (never null)
     */
    public List<ApmSpanEntity> getByTraceId(String traceId) {
        List<ApmSpanEntity> result = cache.getIfPresent(traceId);
        return (result != null) ? List.copyOf(result) : List.of();
    }

    /**
     * Check whether any exception spans have been indexed for a traceId.
     *
     * @param traceId the W3C trace-id
     * @return {@code true} if at least one exception span is present
     */
    public boolean hasExceptionSpans(String traceId) {
        List<ApmSpanEntity> result = cache.getIfPresent(traceId);
        return result != null && !result.isEmpty();
    }

    /**
     * Number of distinct traceIds currently indexed.
     *
     * @return the entry count
     */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * Evict all entries for a given traceId.
     * Typically called after failure diagnosis completes.
     *
     * @param traceId the W3C trace-id to remove
     */
    public void evict(String traceId) {
        cache.invalidate(traceId);
    }

    /**
     * Determine whether a span qualifies as an exception/error span.
     */
    private boolean isExceptionSpan(ApmSpanEntity span) {
        if ("ERROR".equals(span.getStatusCode())) {
            return true;
        }
        return span.getAttributes() != null
                && span.getAttributes().containsKey("exception.type");
    }
}
