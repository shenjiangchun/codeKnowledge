package com.huawei.hisi.neo4j.model;

/**
 * A sub-query tagged with an intent type and confidence score.
 *
 * <p>Used by {@link com.huawei.hisi.ram.nodes.impact.QueryDecomposer} to
 * decompose a complex requirement into focused, intent-aware sub-queries.
 * The intent type determines RRF weight and optional post-filter enhancements;
 * confidence controls dual-channel redundancy (low-confidence queries also
 * run through GENERAL channel as fallback).</p>
 *
 * @param query       the sub-query text
 * @param intentType  the detected intent type (never null)
 * @param confidence  0.0–1.0 confidence of the intent classification;
 *                    queries below the threshold trigger dual-channel search
 */
public record SubQuery(String query, IntentType intentType, double confidence) {

    /** Confidence threshold below which dual-channel search is triggered. */
    public static final double DUAL_CHANNEL_THRESHOLD = 0.7;

    public SubQuery {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (intentType == null) {
            intentType = IntentType.GENERAL;
        }
        if (confidence < 0.0) confidence = 0.0;
        if (confidence > 1.0) confidence = 1.0;
    }

    /** Factory for GENERAL sub-queries with full confidence. */
    public static SubQuery general(String query) {
        return new SubQuery(query, IntentType.GENERAL, 1.0);
    }

    /** Whether this sub-query should also run through GENERAL channel. */
    public boolean needsDualChannel() {
        return intentType != IntentType.GENERAL && confidence < DUAL_CHANNEL_THRESHOLD;
    }
}
