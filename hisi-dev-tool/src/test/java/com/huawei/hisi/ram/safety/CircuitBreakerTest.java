package com.huawei.hisi.ram.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreaker unit tests")
class CircuitBreakerTest {

    private final CircuitBreaker breaker = new CircuitBreaker();

    @Test
    @DisplayName("trips when cumulative tokens exceed 200k cap")
    void circuit_trips_whenCumulativeTokensExceeds200k() {
        Decision d = breaker.check(new SessionStats(250_000L, 10, 2, 0));
        assertThat(d.tripped()).isTrue();
        assertThat(d.fallback()).isEqualTo(Fallback.HUMAN_TAKEOVER);
        assertThat(d.reason()).contains("token cap");
    }

    @Test
    @DisplayName("recommends fallback model when approaching token limit (>80%)")
    void circuit_recommendsFallbackModel_whenApproachingTokenLimit() {
        Decision d = breaker.check(new SessionStats(165_000L, 5, 1, 0));
        assertThat(d.tripped()).isFalse();
        assertThat(d.fallback()).isEqualTo(Fallback.FALLBACK_MODEL);
        assertThat(d.reason()).contains("approaching");
    }

    @Test
    @DisplayName("trips when clarify rounds exceeded")
    void circuit_trips_whenClarifyRoundsExceeded() {
        Decision d = breaker.check(new SessionStats(10_000L, 5, 6, 0));
        assertThat(d.tripped()).isTrue();
        assertThat(d.fallback()).isEqualTo(Fallback.ABORT);
        assertThat(d.reason()).contains("clarify");
    }

    @Test
    @DisplayName("passes when all stats under limits")
    void circuit_passesWhenAllUnderLimits() {
        Decision d = breaker.check(SessionStats.of(1_000L, 2, 1));
        assertThat(d.tripped()).isFalse();
        assertThat(d.fallback()).isEqualTo(Fallback.NONE);
        assertThat(d.reason()).isEqualTo("ok");
    }
}
