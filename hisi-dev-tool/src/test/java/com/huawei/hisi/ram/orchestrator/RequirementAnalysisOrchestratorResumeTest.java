package com.huawei.hisi.ram.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.config.RamSchemaInitializer;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.registry.AgentRegistry;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.repository.impl.JdbcAgentEventRepository;
import com.huawei.hisi.ram.repository.impl.JdbcAgentSessionRepository;
import com.huawei.hisi.workflow.ClarifyRequiredException;
import com.huawei.hisi.workflow.DagExecutor;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.ExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 人工介入之后续跑：批准或编辑过的节点结果要原样沿用，澄清问答要带着之前查到的代码位置。
 * 真实 SQLite + 真实 {@link DagExecutor}，只把四个 LLM 节点换成计数用的假节点。
 */
@DisplayName("RequirementAnalysisOrchestrator resume after human input")
class RequirementAnalysisOrchestratorResumeTest {

    private RequirementAnalysisOrchestrator orchestrator;
    private AgentSessionRepository sessionRepo;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("ram-orchestrator-resume-", ".db");
        Files.deleteIfExists(dbFile);
        DataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + dbFile.toAbsolutePath(), true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new RamSchemaInitializer(jdbc).initialize();

        AgentSessionRepository sessions = new JdbcAgentSessionRepository(jdbc);
        AgentEventRepository events = new JdbcAgentEventRepository(jdbc);
        DagExecutor executor = new DagExecutor(events, sessions, new ObjectMapper());
        orchestrator = new RequirementAnalysisOrchestrator(
                executor, sessions, events, mock(AgentRegistry.class), mock(SchemaValidator.class),
                new ObjectMapper());
        sessionRepo = sessions;
    }

    @Test
    @DisplayName("批准之后已确认的澄清结果直接复用，不因续跑输入多出键而重算")
    void anApprovedNodeIsReusedInsteadOfBeingRecomputed() {
        long sid = sessionWithProject("/proj/a");
        AtomicInteger clarifyRuns = new AtomicInteger();
        DagNode clarify = counting(clarifyRuns, input -> Map.of(
                "intent", "给技能列表加 riskLevel 过滤",
                "target_methods", List.of("com.x.SkillMarketController#getSkillList")));
        DagNode impact = fake("impact", input -> Map.of("entries", List.of("GET /api/skill-market/list")));
        List<DagNode> nodes = List.of(clarify, impact);

        ExecutionResult first = orchestrator.start(sid, Map.of("userRequirement", "加 riskLevel"), nodes);
        assertThat(first.status()).isEqualTo(SessionStatus.WAITING_HITL);
        assertThat(clarifyRuns.get()).isEqualTo(1);

        ExecutionResult after = orchestrator.confirmAndResume(sid, "clarify", "approve", null, null, nodes);

        assertThat(after.status()).isEqualTo(SessionStatus.DONE);
        assertThat(clarifyRuns.get()).as("人刚批准过的结果不应重算").isEqualTo(1);
        assertThat(after.skippedNodes()).containsExactly("clarify");
        assertThat(after.executedNodes()).containsExactly("impact");
    }

    @Test
    @DisplayName("用户在确认弹窗里编辑过的输出才是下游拿到的输出")
    void anEditedOutputReplacesTheOriginalOneOnResume() {
        long sid = sessionWithProject("/proj/a");
        List<Map<String, Object>> seenByImpact = new ArrayList<>();
        DagNode clarify = fake("clarify", input -> Map.of("intent", "模型给的原始意图"));
        DagNode impact = fake("impact", input -> {
            seenByImpact.add(input);
            return Map.of("entries", List.of("GET /api/skill-market/list"));
        });
        List<DagNode> nodes = List.of(clarify, impact);
        orchestrator.start(sid, Map.of("userRequirement", "加 riskLevel"), nodes);

        ExecutionResult after = orchestrator.confirmAndResume(sid, "clarify", "edit", null,
                Map.of("intent", "用户改过的意图"), nodes);

        assertThat(after.status()).as("编辑并提交本身就是确认，不该再弹一次确认").isEqualTo(SessionStatus.DONE);
        assertThat(seenByImpact).hasSize(1);
        assertThat(seenByImpact.get(0).get("intent")).isEqualTo("用户改过的意图");
        assertThat(after.skippedNodes()).containsExactly("clarify");
    }

    @Test
    @DisplayName("澄清续跑时，之前提问那轮已经查到的代码位置随问答一起交给澄清节点")
    void clarifyHistoryCarriesWhatWasAlreadyFound() {
        long sid = sessionWithProject("/proj/a");
        List<Map<String, Object>> clarifyInputs = new ArrayList<>();
        Map<String, Object> partial = Map.of(
                "intent", "给技能列表加 riskLevel 过滤",
                "target_methods", List.of("com.x.SkillMarketController#getSkillList"));
        DagNode clarify = new DagNode() {
            @Override public String name() { return "clarify"; }
            @Override public String agentId() { return "clarify-v1"; }
            @Override public Map<String, Object> execute(Map<String, Object> input) {
                clarifyInputs.add(input);
                if (clarifyInputs.size() == 1) {
                    throw new ClarifyRequiredException(List.of("技能列表指哪个接口？"), partial);
                }
                return Map.of("intent", "加 riskLevel", "target_methods", List.of("com.x.SkillMarketController#getSkillList"));
            }
        };
        DagNode impact = fake("impact", input -> Map.of("entries", List.of()));
        List<DagNode> nodes = List.of(clarify, impact);

        ExecutionResult first = orchestrator.start(sid, Map.of("userRequirement", "加 riskLevel"), nodes);
        assertThat(first.status()).isEqualTo(SessionStatus.WAITING_CLARIFY);

        ExecutionResult second = orchestrator.resume(sid, Map.of("技能列表指哪个接口？", "SkillMarketController"), nodes);

        assertThat(clarifyInputs).hasSize(2);
        // 首次运行和续跑的输入键一致，续跑只多出澄清历史
        assertThat(clarifyInputs.get(1).keySet()).containsExactlyInAnyOrderElementsOf(
                Stream.concat(clarifyInputs.get(0).keySet().stream(), Stream.of("clarify_history")).toList());
        Object history = clarifyInputs.get(1).get("clarify_history");
        assertThat(history).isInstanceOf(List.class);
        List<?> rounds = (List<?>) history;
        assertThat(rounds).hasSize(1);
        assertThat(((Map<?, ?>) rounds.get(0)).get("questions")).isEqualTo(List.of("技能列表指哪个接口？"));
        assertThat(((Map<?, ?>) rounds.get(0)).get("answers"))
                .isEqualTo(Map.of("技能列表指哪个接口？", "SkillMarketController"));
        assertThat(((Map<?, ?>) rounds.get(0)).get("analysis")).isEqualTo(partial);
    }

    /** 预建会话并记下项目路径——REST 入口就是这样建会话的。 */
    private long sessionWithProject(String path) {
        AgentSession session = sessionRepo.save(AgentSession.newRunning("user-orchestrator", SessionType.DEMAND));
        session.setProjectPaths("[\"" + path + "\"]");
        assertThat(sessionRepo.update(session)).as("预建会话应能记下项目路径").isPresent();
        return session.getId();
    }

    private static DagNode counting(AtomicInteger counter, Function<Map<String, Object>, Map<String, Object>> fn) {
        return fake("clarify", input -> {
            counter.incrementAndGet();
            return fn.apply(input);
        });
    }

    private record FakeNode(String name, Function<Map<String, Object>, Map<String, Object>> fn)
            implements DagNode {
        @Override public String agentId() { return name + "-v1"; }
        @Override public Map<String, Object> execute(Map<String, Object> input) { return fn.apply(input); }
    }

    private static DagNode fake(String name, Function<Map<String, Object>, Map<String, Object>> fn) {
        return new FakeNode(name, fn);
    }
}
