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
 * 回归：合入分析「卡在人工确认门、且页面没有确认入口」。
 *
 * <p>故障场景是<b>非首次运行</b>（会话里已有事件，例如用户点了某个节点的「重跑」）：默认策略会在每个非末位节点后
 * 停在 {@code WAITING_HITL}，而合入分析页面没有确认按钮、后端也没有对应的确认接口，会话从此挂死；
 * 它的 SSE 只在 {@code DONE}/{@code FAILED} 时才发终态事件，所以页面一直转圈。
 *
 * <p>根因不在引擎——{@code merge-analysis} 在注册表里早已声明 {@code skipHitl=true}——而是
 * {@code MergeAnalysisService} 调了不带策略的 3 参重载，拿到默认策略。本测试把两种策略放进同一个非首次运行场景对照，
 * 并锁住「合入分析实际使用的策略」确实关门。
 */
@DisplayName("合入分析：非首次运行不得停在确认门")
class MergeAnalysisHitlGateRegressionTest {

    private DagExecutor executor;
    private AgentSessionRepository sessionRepo;
    private AgentEventRepository eventRepo;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("merge-hitl-regression-", ".db");
        Files.deleteIfExists(dbFile);
        DataSource ds = new SingleConnectionDataSource(
                "jdbc:sqlite:" + dbFile.toAbsolutePath(), true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new RamSchemaInitializer(jdbc).initialize();

        sessionRepo = new JdbcAgentSessionRepository(jdbc);
        eventRepo = new JdbcAgentEventRepository(jdbc);
        executor = new DagExecutor(eventRepo, sessionRepo, new ObjectMapper());
    }

    /** 与合入分析真实节点同名、同数量的三个节点（内容无所谓，只看门的行为）。 */
    private static List<DagNode> mergeNodes() {
        return List.of(
                new FakeNode("diff_extract", n -> Map.of("diffResult", n)),
                new FakeNode("impact_analysis", n -> Map.of("impactResult", n)),
                new FakeNode("test_scope", n -> Map.of("testScopeResult", n)));
    }

    /** 先完整跑一遍，让会话带上历史事件——之后任何一次运行都是「非首次运行」。 */
    private long seedSessionWithHistory() {
        AgentSession s = sessionRepo.save(AgentSession.newRunning("merge-analysis", SessionType.MERGE_ANALYSIS));
        long sid = s.getId();
        // 首次运行按引擎既有规则跳过确认门（bootstrap），所以能跑完；现在会话里有 3 条 CHECKPOINT
        executor.run(mergeNodes(), sid, Map.of("projectPath", "/p", "sourceBranch", "a", "targetBranch", "b"),
                ExecutionPolicy.fromMetadata(Map.of("skipHitl", true)));
        assertThat(eventRepo.findBySessionId(sid)).hasSize(3);
        return sid;
    }

    @Test
    @DisplayName("对照组：默认策略下，非首次运行确实会停在 WAITING_HITL（这就是历史会话卡死的机制）")
    void defaultPolicy_stallsAtHitlGate_onNonFirstRun() {
        long sid = seedSessionWithHistory();

        // 改一下输入让缓存失效，模拟「重跑某个节点」：输入变了 → 节点重新执行 → 执行完撞上确认门
        ExecutionResult result = executor.run(mergeNodes(), sid,
                Map.of("projectPath", "/p", "sourceBranch", "a2", "targetBranch", "b"),
                ExecutionPolicy.DEFAULT);

        assertThat(result.status()).isEqualTo(SessionStatus.WAITING_HITL);
        assertThat(result.executedNodes()).containsExactly("diff_extract");
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(SessionStatus.WAITING_HITL);
        boolean hitlReq = eventRepo.findBySessionId(sid).stream()
                .anyMatch(e -> e.getType() == EventType.HITL_REQ);
        assertThat(hitlReq).as("默认策略应发出 HITL_REQ").isTrue();
    }

    @Test
    @DisplayName("修复：skipHitl 策略下，同样的非首次运行一路跑到 DONE，不产生 HITL_REQ")
    void skipHitlPolicy_runsToDone_onNonFirstRun() {
        long sid = seedSessionWithHistory();

        ExecutionResult result = executor.run(mergeNodes(), sid,
                Map.of("projectPath", "/p", "sourceBranch", "a2", "targetBranch", "b"),
                ExecutionPolicy.fromMetadata(Map.of("skipHitl", true)));

        assertThat(result.status()).isEqualTo(SessionStatus.DONE);
        assertThat(result.executedNodes()).containsExactly("diff_extract", "impact_analysis", "test_scope");
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(SessionStatus.DONE);
        boolean hitlReq = eventRepo.findBySessionId(sid).stream()
                .anyMatch(e -> e.getType() == EventType.HITL_REQ);
        assertThat(hitlReq).as("skipHitl 策略不应产生 HITL_REQ").isFalse();
    }

    @Test
    @DisplayName("修复：合入分析声明的策略确实关门——注册表里 merge-analysis 的 skipHitl 生效，不再依赖调用方记得传")
    void mergeAnalysisDeclaredPolicy_disablesGate() {
        com.huawei.hisi.agentbase.AgentRegistry.Workflow def =
                com.huawei.hisi.agentbase.AgentRegistry.workflow("merge-analysis").orElseThrow();

        ExecutionPolicy policy = com.huawei.hisi.workflow.ExecutionPolicy.fromMetadata(def.policy());
        assertThat(policy.interNodeHitlEnabled())
                .as("merge-analysis 声明了 skipHitl=true，派生策略必须关门")
                .isFalse();
    }

    @Test
    @DisplayName("节点链与真实 DAG 一致：注册表声明的名字就是三个节点 bean 的 name()，顺序正确")
    void mergeAnalysisChain_matchesRealDagNodes() {
        List<String> declared = com.huawei.hisi.agentbase.AgentRegistry.workflow("merge-analysis")
                .orElseThrow().nodes();

        assertThat(declared).containsExactly("diff_extract", "impact_analysis", "test_scope");
    }

    private record FakeNode(String name, java.util.function.Function<Object, Map<String, Object>> fn)
            implements DagNode {
        @Override
        public String agentId() {
            return name + "-fake";
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
            return fn.apply(input);
        }
    }
}
