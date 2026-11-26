package com.huawei.hisi.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.model.WorkbenchStages;
import com.huawei.hisi.workbench.repository.WorkbenchFixRunRepository;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import com.huawei.hisi.workbench.service.stage.StageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 执行循环里阶段之间的数据流：阶段按名取上游产物、失败回到开发时产物还在、
 * 中途接手时从事件表恢复。每个用例对应一个真机上出过或差点出过的问题。
 */
class FixRunRunnerTest {

    private static final List<String> ARTIFACT_KEYS = List.of(
            "evidence", "proposal", "spec", "tasks", "develop", "tests", "e2e", "lastFailure");

    private WorkbenchRepository workbenchRepository;
    private WorkbenchFixRunRepository runRepository;
    private AgentSessionRepository sessionRepository;
    private InMemoryAgentEvents eventRepository;
    private AgentFlowCancelRegistry cancelRegistry;
    private FixRunRunner runner;
    /** 每次落库时的运行状态，按时间顺序。 */
    private final List<String> savedStatuses = new CopyOnWriteArrayList<>();
    /** 阶段键 → 每次执行时它看到的各产物。 */
    private final Map<String, List<Map<String, Object>>> seen = new LinkedHashMap<>();
    private final Map<String, Function<StageContext, StageHandler.StageOutcome>> scripts = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        runRepository = mock(WorkbenchFixRunRepository.class);
        sessionRepository = mock(AgentSessionRepository.class);
        eventRepository = new InMemoryAgentEvents();
        cancelRegistry = new AgentFlowCancelRegistry();
        WorkbenchItem item = WorkbenchItem.builder().id(11L).itemKey("k1").projectPath("P").title("t").build();
        when(workbenchRepository.findByKey("k1")).thenReturn(item);
        when(runRepository.save(any())).thenAnswer(inv -> {
            WorkbenchFixRun saved = inv.getArgument(0);
            savedStatuses.add(saved.getStatus());
            return saved;
        });
        when(runRepository.findById(anyLong())).thenAnswer(inv -> Optional.empty());
        new AgentFlowCancelHolder(cancelRegistry);
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.shutdown();
        }
    }

    private static List<Map<String, Object>> anchors(int count) {
        List<Map<String, Object>> anchors = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            anchors.add(Map.of("id", "E" + i, "text", "证据" + i));
        }
        return anchors;
    }

    private static Map<String, Object> spec() {
        return Map.of("goal", "修复空指针", "acceptanceCriteria", List.of("不再抛 NPE"),
                "testCases", List.of(Map.of("type", "unit", "name", "getUser_nullService",
                        "target", "com.a.UserControllerTest#getUser_nullService")));
    }

    /** 按脚本造一组阶段：每个阶段记下自己看到的各产物，然后返回脚本给的结果。 */
    private List<StageHandler> handlers() {
        List<StageHandler> handlers = new ArrayList<>();
        for (WorkbenchStages.Stage stage : WorkbenchStages.ALL) {
            Function<StageContext, StageHandler.StageOutcome> behavior = scripts.getOrDefault(stage.key(),
                    ctx -> StageHandler.StageOutcome.done(Map.of("stage", stage.key())));
            handlers.add(new StageHandler() {
                @Override
                public String key() {
                    return stage.key();
                }

                @Override
                public StageOutcome execute(StageContext ctx) {
                    Map<String, Object> visible = new LinkedHashMap<>();
                    for (String key : ARTIFACT_KEYS) {
                        visible.put(key, ctx.artifact(key));
                    }
                    seen.computeIfAbsent(stage.key(), k -> new CopyOnWriteArrayList<>()).add(visible);
                    return behavior.apply(ctx);
                }
            });
        }
        return handlers;
    }

    private WorkbenchFixRun run(int stageIndex) {
        return WorkbenchFixRun.builder().id(9L).itemKey("k1").sessionUuid("u-9").sessionId(100L)
                .projectPath("P").status(WorkbenchFixRun.STATUS_RUNNING).stageIndex(stageIndex).iteration(1).build();
    }

    /** 脚本就位后再建 runner 并提交：阶段处理器在构造时取脚本。 */
    private void start(int stageIndex) {
        runner = new FixRunRunner(workbenchRepository, runRepository, sessionRepository, eventRepository,
                cancelRegistry, new WorkbenchEventSink(eventRepository, new ObjectMapper()), new ConfirmGate(),
                handlers(), new ObjectMapper(), 2);
        runner.submit(run(stageIndex));
    }

    /** 等运行走到终态（DONE / FAILED / ABORTED），返回那个状态。 */
    private String awaitTerminalStatus() {
        for (int i = 0; i < 80; i++) {
            String status = savedStatuses.stream().filter(s -> WorkbenchFixRun.STATUS_DONE.equals(s)
                    || WorkbenchFixRun.STATUS_FAILED.equals(s) || WorkbenchFixRun.STATUS_ABORTED.equals(s))
                    .reduce((first, second) -> second).orElse(null);
            if (status != null) {
                return status;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return "未到终态，落库过的状态：" + savedStatuses;
    }

    @Test
    void regressionAndE2eReadTheSpecNotThePreviousStageOutput() {
        scripts.put("evidence", ctx -> StageHandler.StageOutcome.done(Map.of("anchors", anchors(3))));
        scripts.put("proposal", ctx -> StageHandler.StageOutcome.done(Map.of("options", List.of(Map.of("id", "A")))));
        scripts.put("spec", ctx -> StageHandler.StageOutcome.done(spec()));
        scripts.put("tasks", ctx -> StageHandler.StageOutcome.done(
                Map.of("tasks", List.of(Map.of("title", "补用例", "file", "src/test/java/A.java")))));
        scripts.put("develop", ctx -> StageHandler.StageOutcome.done(Map.of("changedFiles", List.of("A.java"))));
        scripts.put("tests", ctx -> StageHandler.StageOutcome.done(Map.of("passed", true)));
        scripts.put("e2e", ctx -> StageHandler.StageOutcome.done(Map.of("passed", true)));

        start(0);

        assertEquals(WorkbenchFixRun.STATUS_DONE, awaitTerminalStatus());
        // 回归阶段看到的 Spec 里确实有用例，同时也能看到开发阶段改了什么
        Map<String, Object> testsSaw = seen.get("tests").get(0);
        assertEquals(1, ((List<?>) ((Map<?, ?>) testsSaw.get("spec")).get("testCases")).size());
        assertEquals(List.of("A.java"), ((Map<?, ?>) testsSaw.get("develop")).get("changedFiles"));
        // E2E 同样读到 Spec；开发阶段读到任务清单
        assertEquals("修复空指针", ((Map<?, ?>) seen.get("e2e").get(0).get("spec")).get("goal"));
        assertEquals(1, ((List<?>) ((Map<?, ?>) seen.get("develop").get(0).get("tasks")).get("tasks")).size());
    }

    @Test
    void failedRegressionGoesBackToDevelopWithTasksAndFailureIntact() {
        int[] testRuns = {0};
        scripts.put("evidence", ctx -> StageHandler.StageOutcome.done(Map.of("anchors", anchors(2))));
        scripts.put("spec", ctx -> StageHandler.StageOutcome.done(spec()));
        scripts.put("tasks", ctx -> StageHandler.StageOutcome.done(
                Map.of("tasks", List.of(Map.of("title", "补用例")))));
        scripts.put("tests", ctx -> {
            testRuns[0]++;
            return testRuns[0] == 1
                    ? StageHandler.StageOutcome.retry(Map.of("passed", false, "surefire", "surefire 摘要"), "用例回归未通过")
                    : StageHandler.StageOutcome.done(Map.of("passed", true));
        });

        start(0);

        assertEquals(WorkbenchFixRun.STATUS_DONE, awaitTerminalStatus());
        assertEquals(2, seen.get("develop").size(), "开发阶段应被执行两次");
        Map<String, Object> secondDevelop = seen.get("develop").get(1);
        // 第二次进开发：任务清单与 Spec 都还在，并且知道上一轮哪里没过、回归结果是什么
        assertEquals(1, ((List<?>) ((Map<?, ?>) secondDevelop.get("tasks")).get("tasks")).size());
        assertEquals("修复空指针", ((Map<?, ?>) secondDevelop.get("spec")).get("goal"));
        assertEquals("用例回归未通过", ((Map<?, ?>) secondDevelop.get("lastFailure")).get("message"));
        assertEquals("tests", ((Map<?, ?>) secondDevelop.get("lastFailure")).get("stage"));
        assertEquals("surefire 摘要", ((Map<?, ?>) secondDevelop.get("tests")).get("surefire"));
        assertEquals(2, seen.get("tests").size());
    }

    @Test
    void resumingMidRunRestoresArtifactsFromStoredEvents() {
        eventRepository.append(checkpoint("e1", "evidence", "{\"anchors\":[]}"));
        eventRepository.append(checkpoint("e2", "spec", "{\"goal\":\"旧版本\"}"));
        // 同一阶段后来重出过一次：恢复时要取最后一版
        eventRepository.append(checkpoint("e3", "spec", "{\"goal\":\"修复空指针\",\"testCases\":[{\"type\":\"unit\"}]}"));
        // 日志类事件不是产物
        eventRepository.append(AgentEvent.builder().sessionId(100L).type(EventType.NODE_DELTA)
                .payload("{\"nodeName\":\"spec\",\"message\":\"日志不是产物\"}").idempotencyKey("e4").build());
        scripts.put("develop", ctx -> StageHandler.StageOutcome.failed("到开发为止"));

        start(4);

        assertEquals(WorkbenchFixRun.STATUS_FAILED, awaitTerminalStatus());
        Map<String, Object> developSaw = seen.get("develop").get(0);
        assertEquals("修复空指针", ((Map<?, ?>) developSaw.get("spec")).get("goal"));
        assertEquals(1, ((List<?>) ((Map<?, ?>) developSaw.get("spec")).get("testCases")).size());
        assertTrue(((Map<?, ?>) developSaw.get("evidence")).containsKey("anchors"));
    }

    @Test
    void artifactsOfTakesTheLatestCheckpointAndIgnoresMalformedEvents() {
        List<AgentEvent> events = List.of(
                checkpoint("a", "spec", "{\"goal\":\"v1\"}"),
                checkpoint("b", "spec", "{\"goal\":\"v2\"}"),
                checkpoint("c", "tasks", "不是 JSON"),
                checkpoint("d", "tasks", "一个字符串而不是对象"),
                AgentEvent.builder().sessionId(1L).type(EventType.NODE_START)
                        .payload("{\"nodeName\":\"spec\",\"output\":{\"goal\":\"不是产物\"}}").build());

        Map<String, Object> artifacts = FixRunRunner.artifactsOf(events, new ObjectMapper());

        assertEquals("v2", ((Map<?, ?>) artifacts.get("spec")).get("goal"));
        assertNull(artifacts.get("tasks"));
    }

    private static AgentEvent checkpoint(String id, String stage, String output) {
        // 输出原样拼进 payload：是合法 JSON 就当产物，不是则应被忽略
        return AgentEvent.builder().id((long) id.charAt(0)).sessionId(100L).seq(id.charAt(0)).type(EventType.CHECKPOINT)
                .payload("{\"nodeName\":\"" + stage + "\",\"output\":" + output + "}").idempotencyKey(id).build();
    }
}
