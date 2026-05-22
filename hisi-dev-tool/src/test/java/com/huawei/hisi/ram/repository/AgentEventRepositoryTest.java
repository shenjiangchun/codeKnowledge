package com.huawei.hisi.ram.repository;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
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
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-1"));
        String key = "k-" + System.nanoTime();

        AgentEvent first = eventRepo.append(AgentEvent.userMsg(s.getId(), 1, "hello", key));
        AgentEvent second = eventRepo.append(AgentEvent.userMsg(s.getId(), 1, "hello", key));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(eventRepo.countBySessionId(s.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("append assigns auto-increment id and persists all fields")
    void append_assignsAutoIncrementIdAndPersistsAllFields() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-2"));
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
    @DisplayName("findBySessionId returns events in seq order")
    void findBySessionId_returnsEventsInSeqOrder() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-3"));
        long sid = s.getId();
        long nano = System.nanoTime();

        eventRepo.append(AgentEvent.userMsg(sid, 3, "c", "k-c-" + nano));
        eventRepo.append(AgentEvent.userMsg(sid, 1, "a", "k-a-" + nano));
        eventRepo.append(AgentEvent.userMsg(sid, 2, "b", "k-b-" + nano));

        List<AgentEvent> events = eventRepo.findBySessionId(sid);

        assertThat(events).hasSize(3);
        assertThat(events).extracting(AgentEvent::getSeq).containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(AgentEvent::getPayload).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("findMaxSeq returns zero when session has no events")
    void findMaxSeq_returnsZero_whenSessionHasNoEvents() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-4"));

        assertThat(eventRepo.findMaxSeq(s.getId())).isEqualTo(0L);

        eventRepo.append(AgentEvent.userMsg(s.getId(), 1, "x", "k-x-" + System.nanoTime()));
        eventRepo.append(AgentEvent.userMsg(s.getId(), 5, "y", "k-y-" + System.nanoTime()));

        assertThat(eventRepo.findMaxSeq(s.getId())).isEqualTo(5L);
    }
}
