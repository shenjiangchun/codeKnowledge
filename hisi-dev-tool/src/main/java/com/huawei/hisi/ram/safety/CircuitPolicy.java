package com.huawei.hisi.ram.safety;

/**
 * Static safety caps consulted by {@link CircuitBreaker}. Encodes the six
 * RAM spec dimensions plus a warning threshold ratio used to flag
 * approaching-cap conditions before tripping the breaker.
 *
 * @param maxTokensGlobal      global cumulative token cap per session
 * @param maxDurationMinutes   wall-clock duration cap per session, minutes
 * @param maxClarifyRounds     maximum clarify rounds per session
 * @param maxCostUsd           cumulative USD cost cap per session
 * @param maxParallelSessions  global concurrent session cap
 * @param maxRetriesPerNode    retries allowed for a single node before fallback
 * @param warnThresholdRatio   ratio of a cap at which a warning is raised (e.g. 0.8 = 80%)
 */
public record CircuitPolicy(
        long maxTokensGlobal,
        long maxDurationMinutes,
        int maxClarifyRounds,
        double maxCostUsd,
        int maxParallelSessions,
        int maxRetriesPerNode,
        double warnThresholdRatio) {

    public CircuitPolicy {
        if (maxTokensGlobal <= 0) {
            throw new IllegalArgumentException("maxTokensGlobal must be > 0");
        }
        if (maxDurationMinutes <= 0) {
            throw new IllegalArgumentException("maxDurationMinutes must be > 0");
        }
        if (maxClarifyRounds <= 0) {
            throw new IllegalArgumentException("maxClarifyRounds must be > 0");
        }
        if (maxCostUsd <= 0) {
            throw new IllegalArgumentException("maxCostUsd must be > 0");
        }
        if (maxParallelSessions <= 0) {
            throw new IllegalArgumentException("maxParallelSessions must be > 0");
        }
        if (maxRetriesPerNode <= 0) {
            throw new IllegalArgumentException("maxRetriesPerNode must be > 0");
        }
        if (warnThresholdRatio <= 0.0 || warnThresholdRatio >= 1.0) {
            throw new IllegalArgumentException("warnThresholdRatio must be in (0, 1)");
        }
    }

    public static CircuitPolicy defaults() {
        return new CircuitPolicy(200_000L, 30L, 5, 3.0, 20, 3, 0.8);
    }
}
