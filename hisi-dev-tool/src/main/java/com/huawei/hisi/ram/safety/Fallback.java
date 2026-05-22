package com.huawei.hisi.ram.safety;

/**
 * Fallback action recommended by the {@link CircuitBreaker} when a session
 * exceeds (or approaches) a safety threshold.
 */
public enum Fallback {
    NONE,
    RETRY_WITH_BACKOFF,
    FALLBACK_MODEL,
    HUMAN_TAKEOVER,
    ABORT
}
