package com.huawei.hisi.ram.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.config.RamSchemaInitializer;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DagExecutor} wired against a real SQLite
 * database. Avoids {@code @SpringBootTest} to sidestep unrelated context
 * failures in the broader application.
 */
@DisplayName("DagExecutor integration tests")
class DagExecutorTest {

    private DagExecutor executor;
    private AgentSessionRepository sessionRepo;
    private AgentEventRepository eventRepo;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("ram-dag-test-", ".db");
        Files.deleteIfExists(dbFile);
        DataSource ds = new SingleConnectionDataSource(
                "jdbc:sqlite:" + dbFile.toAbsolutePath(), true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new RamSchemaInitializer(jdbc).initialize();

        sessionRepo = new JdbcAgentSessionRepository(jdbc);
        eventRepo = new JdbcAgentEventRepository(jdbc);
        executor = new DagExecutor(eventRepo, sessionRepo, new ObjectMapper());
    }

    @Test
    @DisplayName("runs all nodes and persists CHECKPOINT events")
    void executor_runsAllNodesAndPersistsCheckpoints() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-1"));
        long sid = s.getId();

        DagNode clarify = new FakeNode("clarify", "clarify-v1",
                input -> Map.of("intent", "X", "echo", input));
        DagNode impact = new FakeNode("impact", "impact-v1",
                input -> Map.of("involved", List.of("M1")));

        ExecutionResult result = executor.run(List.of(clarify, impact), sid, Map.of("q", "do X"));

        assertThat(result.status()).isEqualTo(SessionStatus.DONE);
        assertThat(result.executedNodes()).containsExactly("clarify", "impact");
        assertThat(result.skippedNodes()).isEmpty();

        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        long checkpoints = events.stream().filter(e -> e.getType() == EventType.CHECKPOINT).count();
        assertThat(checkpoints).isEqualTo(2L);
    }

    @Test
    @DisplayName("skips nodes whose inputs hash matches a prior checkpoint")
    void executor_skipsNodesWhoseInputsHashUnchanged() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-2"));
        long sid = s.getId();

        DagNode clarify = new FakeNode("clarify", "clarify-v1",
                input -> Map.of("intent", "X"));
        DagNode impact = new FakeNode("impact", "impact-v1",
                input -> Map.of("involved", List.of("M1")));

        Map<String, Object> initial = Map.of("q", "do X");
        executor.run(List.of(clarify, impact), sid, initial);

        ExecutionResult second = executor.run(List.of(clarify, impact), sid, initial);

        assertThat(second.status()).isEqualTo(SessionStatus.DONE);
        assertThat(second.skippedNodes()).containsExactly("clarify", "impact");
        assertThat(second.executedNodes()).isEmpty();
    }

    @Test
    @DisplayName("emits CLARIFY_REQ event when node throws ClarifyRequiredException")
    void executor_emitsClarifyEvent_whenNodeThrowsClarifyRequired() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-3"));
        long sid = s.getId();

        DagNode clarify = new FakeNode("clarify", "clarify-v1",
                input -> { throw new ClarifyRequiredException(List.of("Q1", "Q2")); });

        ExecutionResult result = executor.run(List.of(clarify), sid, Map.of("q", "vague"));

        assertThat(result.status()).isEqualTo(SessionStatus.WAITING_CLARIFY);
        assertThat(result.executedNodes()).isEmpty();

        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        AgentEvent clarifyReq = events.stream()
                .filter(e -> e.getType() == EventType.CLARIFY_REQ)
                .findFirst().orElseThrow();
        assertThat(clarifyReq.getPayload()).contains("Q1").contains("Q2");

        AgentSession reloaded = sessionRepo.findById(sid).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SessionStatus.WAITING_CLARIFY);
    }

    private record FakeNode(String name, String agentId,
                            Function<Map<String, Object>, Map<String, Object>> fn)
            implements DagNode {
        @Override
        public Map<String, Object> execute(Map<String, Object> input) {
            return fn.apply(input);
        }
    }
}
