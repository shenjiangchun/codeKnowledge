package com.huawei.hisi.ram.repository;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AgentEventRepository}.
 *
 * <p>Verifies idempotent append, per-session seq uniqueness and basic lookups.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/ram-test.db",
        "spring.datasource.hikari.maximum-pool-size=1"
})
@ActiveProfiles("test")
@DisplayName("AgentEventRepository integration tests")
class AgentEventRepositoryTest {

    @Autowired
    private AgentEventRepository eventRepo;

    @Autowired
    private AgentSessionRepository sessionRepo;

    @Test
    @DisplayName("append is idempotent on duplicate idempotency key")
    void append_isIdempotent_onDuplicateKey() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-1", SessionType.DEMAND));
        String key = "k-" + System.nanoTime();

        AgentEvent first = eventRepo.append(AgentEvent.userMsg(s.getId(), 1, "hello", key));
        AgentEvent second = eventRepo.append(AgentEvent.userMsg(s.getId(), 1, "hello", key));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(eventRepo.countBySessionId(s.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("append assigns auto-increment id and persists all fields")
    void append_assignsAutoIncrementIdAndPersistsAllFields() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-2", SessionType.DEMAND));
        String key = "k-detail-" + System.nanoTime();

        AgentEvent event = AgentEvent.toolUse(
                s.getId(), 1, "tool-use-123", "{\"name\":\"Bash\"}", key);
        AgentEvent saved = eventRepo.append(event);

        assertThat(saved.getId()).isNotNull().isPositive();
        assertThat(saved.getSessionId()).isEqualTo(s.getId());
        assertThat(saved.getSeq()).isEqualTo(1L);
        assertThat(saved.getToolUseId()).isEqualTo("tool-use-123");
        assertThat(saved.getPayload()).isEqualTo("{\"name\":\"Bash\"}");
        assertThat(saved.getCircuitState()).isEqualTo("OK");
        assertThat(saved.getValidatorStatus()).isEqualTo("OK");

        AgentEvent fetched = eventRepo.findByIdempotencyKey(key).orElseThrow();
        assertThat(fetched.getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findBySessionId returns events in seq order (server-assigned)")
    void findBySessionId_returnsEventsInSeqOrder() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-3", SessionType.DEMAND));
        long sid = s.getId();
        long nano = System.nanoTime();

        // seq is server-assigned in insertion order; the caller-supplied seq
        // value in the factory method is ignored by append().
        eventRepo.append(AgentEvent.userMsg(sid, 0, "a", "k-a-" + nano));
        eventRepo.append(AgentEvent.userMsg(sid, 0, "b", "k-b-" + nano));
        eventRepo.append(AgentEvent.userMsg(sid, 0, "c", "k-c-" + nano));

        List<AgentEvent> events = eventRepo.findBySessionId(sid);

        assertThat(events).hasSize(3);
        assertThat(events).extracting(AgentEvent::getSeq).containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(AgentEvent::getPayload).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("findMaxSeq returns zero when session has no events, and tracks server-assigned seq")
    void findMaxSeq_returnsZero_whenSessionHasNoEvents() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-4", SessionType.DEMAND));

        assertThat(eventRepo.findMaxSeq(s.getId())).isEqualTo(0L);

        eventRepo.append(AgentEvent.userMsg(s.getId(), 0, "x", "k-x-" + System.nanoTime()));
        eventRepo.append(AgentEvent.userMsg(s.getId(), 0, "y", "k-y-" + System.nanoTime()));

        // append assigns seq atomically: 1, then 2
        assertThat(eventRepo.findMaxSeq(s.getId())).isEqualTo(2L);
    }
}
