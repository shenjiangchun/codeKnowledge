package com.huawei.hisi.ram.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreaker unit tests")
class CircuitBreakerTest {

    private final CircuitBreaker breaker = new CircuitBreaker();

    @Test
    @DisplayName("exactly-at-cap: 200_000 tokens does NOT trip")
    void circuit_exactlyAtTokenCap_doesNotTrip() {
        Decision d = breaker.check(new SessionStats(200_000L, 0.0, 10, 2, 0));
        assertThat(d.tripped()).isFalse();
        // 200_000 > 200_000 * 0.8 → soft warn fallback
        assertThat(d.fallback()).isEqualTo(Fallback.FALLBACK_MODEL);
        assertThat(d.reason()).contains("approaching");
    }

    @Test
    @DisplayName("over-cap: 200_001 tokens trips with HUMAN_TAKEOVER")
    void circuit_overTokenCap_trips() {
        Decision d = breaker.check(new SessionStats(200_001L, 0.0, 10, 2, 0));
        assertThat(d.tripped()).isTrue();
        assertThat(d.fallback()).isEqualTo(Fallback.HUMAN_TAKEOVER);
        assertThat(d.reason()).contains("token cap");
    }

    @Test
    @DisplayName("duration cap exceeded trips with HUMAN_TAKEOVER")
    void circuit_durationCap_trips() {
        Decision d = breaker.check(new SessionStats(1_000L, 0.0, 31, 1, 0));
        assertThat(d.tripped()).isTrue();
        assertThat(d.fallback()).isEqualTo(Fallback.HUMAN_TAKEOVER);
        assertThat(d.reason()).contains("duration");
    }

    @Test
    @DisplayName("clarify rounds cap trips with ABORT")
    void circuit_clarifyCap_trips() {
        Decision d = breaker.check(new SessionStats(10_000L, 0.0, 5, 6, 0));
        assertThat(d.tripped()).isTrue();
        assertThat(d.fallback()).isEqualTo(Fallback.ABORT);
        assertThat(d.reason()).contains("clarify");
    }

    @Test
    @DisplayName("retry cap returns FALLBACK_MODEL with tripped=false (soft degradation)")
    void circuit_retryCap_returnsFallbackModelNotTripped() {
        Decision d = breaker.check(new SessionStats(1_000L, 0.0, 1, 0, 4));
        assertThat(d.tripped()).isFalse();
        assertThat(d.fallback()).isEqualTo(Fallback.FALLBACK_MODEL);
        assertThat(d.reason()).contains("retry");
    }

    @Test
    @DisplayName("cost cap exceeded trips with ABORT")
    void circuit_costCap_trips() {
        Decision d = breaker.check(new SessionStats(1_000L, 3.5, 1, 0, 0));
        assertThat(d.tripped()).isTrue();
        assertThat(d.fallback()).isEqualTo(Fallback.ABORT);
        assertThat(d.reason()).contains("cost");
    }

    @Test
    @DisplayName("parallel sessions cap exceeded trips with ABORT")
    void circuit_parallelSessionsCap_trips() {
        Decision d = breaker.check(new SessionStats(1_000L, 0.0, 1, 0, 0), 21);
        assertThat(d.tripped()).isTrue();
        assertThat(d.fallback()).isEqualTo(Fallback.ABORT);
        assertThat(d.reason()).contains("parallel");
    }

    @Test
    @DisplayName("all stats under limits → ok")
    void circuit_underLimits_returnsOk() {
        Decision d = breaker.check(new SessionStats(1_000L, 0.1, 1, 0, 0), 1);
        assertThat(d.tripped()).isFalse();
        assertThat(d.fallback()).isEqualTo(Fallback.NONE);
        assertThat(d.reason()).isEqualTo("ok");
    }

    @Test
    @DisplayName("approaching token cap (>80%) returns FALLBACK_MODEL with tripped=false")
    void circuit_approachingTokenCap_returnsFallbackModel() {
        Decision d = breaker.check(new SessionStats(165_000L, 0.0, 5, 1, 0));
        assertThat(d.tripped()).isFalse();
        assertThat(d.fallback()).isEqualTo(Fallback.FALLBACK_MODEL);
        assertThat(d.reason()).contains("approaching");
    }
}
