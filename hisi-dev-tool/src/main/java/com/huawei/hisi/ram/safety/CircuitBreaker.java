package com.huawei.hisi.ram.safety;

import org.springframework.stereotype.Component;

/**
 * Evaluates {@link SessionStats} against a {@link CircuitPolicy} and returns
 * a {@link Decision}. {@code tripped=true} is reserved for hard ABORT/HUMAN
 * cases; soft degradations (e.g. retry-cap → fallback model, approaching
 * token cap) return {@code tripped=false} with a non-NONE fallback.
 */
@Component
public class CircuitBreaker {

    private final CircuitPolicy policy;

    public CircuitBreaker() {
        this(CircuitPolicy.defaults());
    }

    public CircuitBreaker(CircuitPolicy policy) {
        this.policy = policy;
    }

    public CircuitPolicy policy() {
        return policy;
    }

    public Decision check(SessionStats stats) {
        return check(stats, 0);
    }

    public Decision check(SessionStats stats, int currentParallelSessions) {
        // Hard caps → tripped=true
        if (stats.cumulativeTokens() > policy.maxTokensGlobal()) {
            return new Decision(true, Fallback.HUMAN_TAKEOVER, "token cap exceeded");
        }
        if (stats.durationMinutes() > policy.maxDurationMinutes()) {
            return new Decision(true, Fallback.HUMAN_TAKEOVER, "duration cap exceeded");
        }
        if (stats.clarifyRounds() > policy.maxClarifyRounds()) {
            return new Decision(true, Fallback.ABORT, "too many clarify rounds");
        }
        if (stats.costUsd() > policy.maxCostUsd()) {
            return new Decision(true, Fallback.ABORT, "cost cap exceeded");
        }
        if (currentParallelSessions > policy.maxParallelSessions()) {
            return new Decision(true, Fallback.ABORT, "parallel sessions cap exceeded");
        }

        // Soft degradations → tripped=false
        if (stats.retriesThisNode() > policy.maxRetriesPerNode()) {
            return new Decision(false, Fallback.FALLBACK_MODEL, "node retry cap");
        }
        if (stats.cumulativeTokens() > policy.maxTokensGlobal() * policy.warnThresholdRatio()) {
            return new Decision(false, Fallback.FALLBACK_MODEL, "approaching token cap");
        }
        if (stats.costUsd() > policy.maxCostUsd() * policy.warnThresholdRatio()) {
            return new Decision(false, Fallback.FALLBACK_MODEL, "approaching cost cap");
        }
        return new Decision(false, Fallback.NONE, "ok");
    }
}
