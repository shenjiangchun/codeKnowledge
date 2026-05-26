package com.huawei.hisi.ram.safety;

/**
 * Immutable runtime statistics for a single RAM session, sampled by the
 * {@link CircuitBreaker} on each checkpoint. All accumulator helpers return
 * a new record instance instead of mutating in place.
 */
public record SessionStats(
        long cumulativeTokens,
        double costUsd,
        long durationMinutes,
        int clarifyRounds,
        int retriesThisNode) {

    public static SessionStats empty() {
        return new SessionStats(0L, 0.0, 0L, 0, 0);
    }

    /** Back-compat factory used by older callers — costUsd defaults to 0. */
    public static SessionStats of(long tokens, long durationMinutes, int clarifyRounds) {
        return new SessionStats(tokens, 0.0, durationMinutes, clarifyRounds, 0);
    }

    public SessionStats withTokensAdded(long delta) {
        return new SessionStats(cumulativeTokens + delta, costUsd, durationMinutes, clarifyRounds, retriesThisNode);
    }

    public SessionStats withCostAdded(double delta) {
        return new SessionStats(cumulativeTokens, costUsd + delta, durationMinutes, clarifyRounds, retriesThisNode);
    }

    public SessionStats withClarifyRound() {
        return new SessionStats(cumulativeTokens, costUsd, durationMinutes, clarifyRounds + 1, retriesThisNode);
    }

    public SessionStats withRetry() {
        return new SessionStats(cumulativeTokens, costUsd, durationMinutes, clarifyRounds, retriesThisNode + 1);
    }

    public SessionStats withDurationMinutes(long minutes) {
        return new SessionStats(cumulativeTokens, costUsd, minutes, clarifyRounds, retriesThisNode);
    }
}
