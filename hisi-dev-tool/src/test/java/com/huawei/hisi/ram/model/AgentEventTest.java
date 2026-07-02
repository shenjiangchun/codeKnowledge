package com.huawei.hisi.ram.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventTest {

    @Test
    @DisplayName("turnInterrupted factory builds event with TURN_INTERRUPTED type")
    void turnInterrupted_buildsEvent() {
        var payload = "{\"turnId\":\"T1\",\"partialText\":\"abc\"}";
        var evt = AgentEvent.turnInterrupted(1L, 42L, "T1", payload, "idem-1");

        assertThat(evt.getType()).isEqualTo(EventType.TURN_INTERRUPTED);
        assertThat(evt.getSessionId()).isEqualTo(1L);
        assertThat(evt.getSeq()).isEqualTo(42L);
        assertThat(evt.getPayload()).isEqualTo(payload);
        assertThat(evt.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(evt.getCircuitState()).isEqualTo("OK");
        assertThat(evt.getValidatorStatus()).isEqualTo("OK");
        assertThat(evt.isInterrupted()).isTrue();
        assertThat(evt.getTurnId()).isEqualTo("T1");
        assertThat(evt.getCreatedAt()).isGreaterThan(0L);
    }
}
