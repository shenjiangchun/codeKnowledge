package com.huawei.hisi.ram.safety;

import org.springframework.stereotype.Component;

/**
 * Evaluates {@link SessionStats} against a {@link CircuitPolicy} and returns
 * a {@link Decision} that callers use to either continue, degrade gracefully,
 * or hand control back to a human.
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
        if (stats.cumulativeTokens() > policy.maxTokensGlobal()) {
            return new Decision(true, Fallback.HUMAN_TAKEOVER, "token cap exceeded");
        }
        if (stats.durationMinutes() > policy.maxDurationMinutes()) {
            return new Decision(true, Fallback.HUMAN_TAKEOVER, "duration cap exceeded");
        }
        if (stats.clarifyRounds() > policy.maxClarifyRounds()) {
            return new Decision(true, Fallback.ABORT, "too many clarify rounds");
        }
        if (stats.retriesThisNode() > policy.maxRetriesPerNode()) {
            return new Decision(true, Fallback.FALLBACK_MODEL, "node retry cap");
        }
        if (stats.cumulativeTokens() > policy.maxTokensGlobal() * 0.8) {
            return new Decision(false, Fallback.FALLBACK_MODEL, "approaching token cap");
        }
        return new Decision(false, Fallback.NONE, "ok");
    }
}
