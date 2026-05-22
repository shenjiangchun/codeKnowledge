package com.huawei.hisi.ram.safety;

/**
 * Static safety caps consulted by {@link CircuitBreaker}.
 */
public record CircuitPolicy(
        int maxTokensGlobal,
        int maxDurationMinutes,
        int maxClarifyRounds,
        int maxRetriesPerNode,
        int maxSessionsPerDayPerUser,
        int maxConcurrentPerUser) {

    public static CircuitPolicy defaults() {
        return new CircuitPolicy(200_000, 30, 5, 3, 20, 3);
    }
}
