package com.huawei.hisi.ram.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdempotencyGuard unit tests")
class IdempotencyGuardTest {

    @Test
    @DisplayName("executeOnce runs work once and caches result")
    void executeOnce_runsWorkOnceAndCachesResult() {
        IdempotencyGuard guard = new IdempotencyGuard();
        AtomicInteger counter = new AtomicInteger();

        String first = guard.executeOnce("k1", () -> {
            counter.incrementAndGet();
            return "result";
        });
        String second = guard.executeOnce("k1", () -> {
            counter.incrementAndGet();
            return "different";
        });

        assertThat(first).isEqualTo("result");
        assertThat(second).isEqualTo("result");
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("executeOnce runs supplier per distinct key")
    void executeOnce_runsTwiceForDifferentKeys() {
        IdempotencyGuard guard = new IdempotencyGuard();
        AtomicInteger counter = new AtomicInteger();

        guard.executeOnce("k1", counter::incrementAndGet);
        guard.executeOnce("k2", counter::incrementAndGet);

        assertThat(counter.get()).isEqualTo(2);
    }
}
