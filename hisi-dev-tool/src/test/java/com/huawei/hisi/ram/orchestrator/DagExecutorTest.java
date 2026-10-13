package com.huawei.hisi.ram.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.config.RamSchemaInitializer;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.repository.impl.JdbcAgentEventRepository;
import com.huawei.hisi.ram.repository.impl.JdbcAgentSessionRepository;
import com.huawei.hisi.workflow.ClarifyRequiredException;
import com.huawei.hisi.workflow.DagExecutor;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.ExecutionPolicy;
import com.huawei.hisi.workflow.ExecutionResult;
import com.huawei.hisi.workflow.NodeEventSink;
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
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-1", SessionType.DEMAND));
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
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-2", SessionType.DEMAND));
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
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-3", SessionType.DEMAND));
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

    @Test
    @DisplayName("emits NODE_START/CHECKPOINT/NODE_COMPLETE in order when policy enables node events")
    void executor_emitsNodeEvents_whenPolicyEnabled() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-4", SessionType.DEMAND));
        long sid = s.getId();

        DagNode node = new FakeNode("arch_evidence_collect", "arch-evidence-v1",
                input -> Map.of("evidencePack", Map.of("kpi", "ok")));

        ExecutionPolicy policy = ExecutionPolicy.fromMetadata(
                Map.of("skipHitl", true, "emitNodeEvents", true));
        executor.run(List.of(node), sid, Map.of("projectPaths", List.of("p1")), policy);

        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        List<EventType> types = events.stream().map(AgentEvent::getType).toList();

        assertThat(types).contains(EventType.NODE_START, EventType.NODE_COMPLETE);

        int start = types.indexOf(EventType.NODE_START);
        int checkpoint = types.indexOf(EventType.CHECKPOINT);
        int complete = types.indexOf(EventType.NODE_COMPLETE);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(start).isLessThan(checkpoint);
        assertThat(checkpoint).isLessThan(complete);

        // Both lifecycle events carry the executed node name.
        ObjectMapper mapper = new ObjectMapper();
        List<String> nodeNames = events.stream()
                .filter(e -> e.getType() == EventType.NODE_START || e.getType() == EventType.NODE_COMPLETE)
                .map(e -> {
                    try {
                        return (String) mapper.readValue(e.getPayload(), Map.class).get("nodeName");
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .toList();
        assertThat(nodeNames).containsExactly("arch_evidence_collect", "arch_evidence_collect");
    }

    @Test
    @DisplayName("default policy does NOT emit NODE_START/NODE_COMPLETE (RAM zero regression)")
    void executor_doesNotEmitNodeEvents_withDefaultPolicy() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-5", SessionType.DEMAND));
        long sid = s.getId();

        DagNode clarify = new FakeNode("clarify", "clarify-v1",
                input -> Map.of("intent", "X"));
        DagNode impact = new FakeNode("impact", "impact-v1",
                input -> Map.of("involved", List.of("M1")));

        executor.run(List.of(clarify, impact), sid, Map.of("q", "do X"));

        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        assertThat(events.stream().map(AgentEvent::getType))
                .doesNotContain(EventType.NODE_START, EventType.NODE_COMPLETE);
    }

    @Test
    @DisplayName("emits NODE_DELTA events when a node reports progress via sink")
    void executor_emitsNodeDeltaEvents_whenNodeReportsProgress() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-6", SessionType.DEMAND));
        long sid = s.getId();

        DagNode node = new DagNode() {
            @Override public String name() { return "arch_root_cause"; }
            @Override public String agentId() { return "arch-root-cause-v1"; }
            @Override public Map<String, Object> execute(Map<String, Object> input) { return Map.of(); }
            @Override public Map<String, Object> execute(Map<String, Object> input, NodeEventSink sink) {
                sink.emit("正在调用 LLM 根因分析…");
                sink.emit("根因分析完成");
                return execute(input);
            }
        };

        ExecutionPolicy policy = ExecutionPolicy.fromMetadata(
                Map.of("skipHitl", true, "emitNodeEvents", true));
        executor.run(List.of(node), sid, Map.of("projectPaths", List.of("p1")), policy);

        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        List<String> deltas = events.stream()
                .filter(e -> e.getType() == EventType.NODE_DELTA)
                .map(AgentEvent::getPayload)
                .toList();
        assertThat(deltas).hasSize(2);
        assertThat(deltas.get(0)).contains("正在调用 LLM 根因分析").contains("arch_root_cause");
        assertThat(deltas.get(1)).contains("根因分析完成");
    }

    @Test
    @DisplayName("does NOT emit NODE_DELTA with default policy (RAM zero regression)")
    void executor_doesNotEmitNodeDelta_withDefaultPolicy() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-7", SessionType.DEMAND));
        long sid = s.getId();

        DagNode node = new DagNode() {
            @Override public String name() { return "clarify"; }
            @Override public String agentId() { return "clarify-v1"; }
            @Override public Map<String, Object> execute(Map<String, Object> input) { return Map.of(); }
            @Override public Map<String, Object> execute(Map<String, Object> input, NodeEventSink sink) {
                sink.emit("should-not-be-persisted");
                return execute(input);
            }
        };

        executor.run(List.of(node), sid, Map.of("q", "do X"));

        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        assertThat(events.stream().map(AgentEvent::getType))
                .doesNotContain(EventType.NODE_DELTA);
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
