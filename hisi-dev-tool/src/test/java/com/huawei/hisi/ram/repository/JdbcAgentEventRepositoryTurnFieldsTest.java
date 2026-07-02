package com.huawei.hisi.ram.repository;

import com.huawei.hisi.ram.config.RamSchemaInitializer;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.impl.JdbcAgentEventRepository;
import com.huawei.hisi.ram.repository.impl.JdbcAgentSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@code interrupted} / {@code turn_id} columns
 * added to {@code agent_event} (T11). Runs against a real SQLite database via
 * {@link SingleConnectionDataSource} without booting the full Spring context —
 * mirrors {@code DagExecutorTest} setup.
 */
@DisplayName("JdbcAgentEventRepository interrupted/turn_id column tests")
class JdbcAgentEventRepositoryTurnFieldsTest {

    private JdbcTemplate jdbc;
    private AgentSessionRepository sessionRepo;
    private AgentEventRepository eventRepo;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("ram-agent-event-turn-", ".db");
        Files.deleteIfExists(dbFile);
        DataSource ds = new SingleConnectionDataSource(
                "jdbc:sqlite:" + dbFile.toAbsolutePath(), true);
        jdbc = new JdbcTemplate(ds);
        new RamSchemaInitializer(jdbc).initialize();

        sessionRepo = new JdbcAgentSessionRepository(jdbc);
        eventRepo = new JdbcAgentEventRepository(jdbc);
    }

    @Test
    @DisplayName("addColumnIfNotExists is idempotent when initialize runs twice")
    void initialize_isIdempotent_forNewColumns() {
        // Second invocation on the already-initialized database must not throw.
        new RamSchemaInitializer(jdbc).initialize();

        List<Map<String, Object>> cols = jdbc.queryForList("PRAGMA table_info(agent_event)");
        assertThat(cols).extracting(row -> (String) row.get("name"))
                .contains("interrupted", "turn_id");

        List<Map<String, Object>> indexes = jdbc.queryForList("PRAGMA index_list(agent_event)");
        assertThat(indexes).extracting(row -> (String) row.get("name"))
                .contains("idx_agent_event_turn_id");
    }

    @Test
    @DisplayName("round-trip: turnInterrupted persists interrupted=true and turnId")
    void turnInterrupted_roundTrip() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-turn-1", SessionType.DEMAND));
        String key = "idem-turn-" + System.nanoTime();
        String turnId = "turn-abc-123";

        AgentEvent saved = eventRepo.append(
                AgentEvent.turnInterrupted(s.getId(), 0L, turnId, "{\"partialText\":\"hi\"}", key));

        assertThat(saved.isInterrupted()).isTrue();
        assertThat(saved.getTurnId()).isEqualTo(turnId);

        AgentEvent fetched = eventRepo.findByIdempotencyKey(key).orElseThrow();
        assertThat(fetched.isInterrupted()).isTrue();
        assertThat(fetched.getTurnId()).isEqualTo(turnId);
    }

    @Test
    @DisplayName("defaults: non-interrupt events have interrupted=false and turnId=null")
    void nonInterruptEvent_hasDefaults() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-turn-2", SessionType.DEMAND));
        String key = "idem-user-" + System.nanoTime();

        eventRepo.append(AgentEvent.userMsg(s.getId(), 0L, "hello", key));

        AgentEvent fetched = eventRepo.findByIdempotencyKey(key).orElseThrow();
        assertThat(fetched.isInterrupted()).isFalse();
        assertThat(fetched.getTurnId()).isNull();
    }
}
