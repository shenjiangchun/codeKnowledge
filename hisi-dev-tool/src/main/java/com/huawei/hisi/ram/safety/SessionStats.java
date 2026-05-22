package com.huawei.hisi.ram.safety;

/**
 * Runtime statistics for a single RAM session, sampled by the
 * {@link CircuitBreaker} on each checkpoint.
 */
public record SessionStats(
        long cumulativeTokens,
        int durationMinutes,
        int clarifyRounds,
        int retriesThisNode) {

    public static SessionStats of(long t, int min, int cr) {
        return new SessionStats(t, min, cr, 0);
    }
}
