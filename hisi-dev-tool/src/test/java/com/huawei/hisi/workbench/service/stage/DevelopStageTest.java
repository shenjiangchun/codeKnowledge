package com.huawei.hisi.workbench.service.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.fixengine.service.WorktreeService;
import com.huawei.hisi.piagent.FlowToolManifest;
import com.huawei.hisi.piagent.PiFlowClient;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.service.InMemoryAgentEvents;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.WorkbenchEventSink;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import com.huawei.hisi.workbench.service.stage.StageHandler.StageOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开发阶段交给 pi 编码 agent 后，后端只做确定性的事：以 git 为准收集改动、复核编译、守住 worktree 与提交历史的边界，
 * 以及给 pi 的任务说明要带全验收标准、测试用例与上一轮的失败详情。
 * 编译复核在这里用替身（不在单测里跑 Maven），只验证「结果如何被使用」。
 */
@DisplayName("DevelopStage with the pi coding agent")
class DevelopStageTest {

    @TempDir
    Path repo;

    private final PiFlowClient pi = mock(PiFlowClient.class);
    /** 编译复核的替身：每个用例自己决定它返回什么。 */
    private CommandRunner.Result compileResult = new CommandRunner.Result(0, "", false);
    private final DevelopStage stage = new DevelopStage(mock(WorktreeService.class), pi) {
        @Override
        CommandRunner.Result compile(Path worktree, Set<Path> touched) {
            return compileResult;
        }
    };
    private InMemoryAgentEvents events;

    @BeforeEach
    void setUp() throws Exception {
        git(repo, "init", "-q");
        Files.createDirectories(repo.resolve("src/main/java/demo"));
        Files.writeString(repo.resolve("src/main/java/demo/A.java"), "class A {}\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "init");
        events = new InMemoryAgentEvents();
    }

    @Test
    @DisplayName("改动清单以 git 为准：修改、新目录里的新文件都算，构建产物不算")
    void changesComeFromGitNotFromTheModel() throws Exception {
        Files.writeString(repo.resolve("src/main/java/demo/A.java"), "class A { int x; }\n");
        Files.createDirectories(repo.resolve("src/test/java/demo"));
        Files.writeString(repo.resolve("src/test/java/demo/ATest.java"), "class ATest {}\n");
        Files.createDirectories(repo.resolve("target/classes"));
        Files.writeString(repo.resolve("target/classes/A.class"), "junk");

        assertThat(DevelopStage.changesIn(repo))
                .containsExactlyInAnyOrder("src/main/java/demo/A.java", "src/test/java/demo/ATest.java");
    }

    @Test
    @DisplayName("刚检出的 worktree 没有任何改动")
    void aFreshWorktreeHasNoChanges() {
        assertThat(DevelopStage.changesIn(repo)).isEmpty();
    }

    @Test
    @DisplayName("任务说明带全：待办、任务清单、验收标准、测试用例、编译命令、上一轮的失败详情")
    void theTaskBriefCarriesEverythingTheAgentNeeds() {
        StageContext ctx = context(Map.of(
                "tasks", Map.of("tasks", List.of(task("修复空指针", "src/main/java/demo/A.java", "A#go"))),
                "spec", Map.of("acceptanceCriteria", List.of("传入 null 不抛异常"),
                        "testCases", List.of(Map.of("type", "unit", "name", "null 入参不抛异常", "target", "ATest", "verify", "返回空列表"))),
                "lastFailure", Map.of("message", "回归 1 个用例失败")));

        String brief = DevelopStage.userPrompt(ctx, repo, DevelopStage.mapsOf(ctx.artifact("tasks").get("tasks")),
                ctx.artifact("spec"), DevelopStage.failureSummary(ctx));

        assertThat(brief).contains("待办：修复 NPE").contains("src/main/java/demo/A.java").contains("A#go")
                .contains("传入 null 不抛异常").contains("null 入参不抛异常 | 落点 ATest | 通过标准 返回空列表")
                .contains(DevelopStage.compileCommand(repo)).contains("回归 1 个用例失败");
    }

    @Test
    @DisplayName("pi 改好并通过复核：以 worktree 为工作目录、用开发清单跑，改动清单来自 git")
    void aProductiveSessionProducesTheDevelopArtifact() throws Exception {
        when(pi.run(eq(FlowToolManifest.WORKBENCH_DEV), anyString(), eq(repo), isNull(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    Files.writeString(repo.resolve("src/main/java/demo/A.java"), "class A { int fixed; }\n");
                    return settled("{\"summary\":\"ok\"}");
                });

        StageOutcome outcome = stage.execute(context(withTasks()));

        assertThat(outcome.kind()).isEqualTo(StageOutcome.DONE);
        assertThat(outcome.output()).containsEntry("changedFiles", List.of("src/main/java/demo/A.java"));
        verify(pi, times(1)).run(anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pi 只说不做（没有任何文件改动）：带话让它真改，轮次用尽后按未通过回到开发")
    void anIdleSessionIsNudgedThenRetried() {
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any()))
                .thenReturn(settled("{\"summary\":\"done\"}"));

        StageOutcome outcome = stage.execute(context(withTasks()));

        assertThat(outcome.kind()).isEqualTo(StageOutcome.RETRY);
        assertThat(outcome.message()).contains("没有产生任何改动");
        // 首次会话 + 2 轮催促；催促沿用同一个会话 id（续聊，pi 记得上下文）
        ArgumentCaptor<String> session = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(pi, times(3)).run(anyString(), session.capture(), any(), isNull(), anyString(), message.capture(), any(), any());
        assertThat(session.getAllValues()).containsOnly(session.getAllValues().get(0));
        assertThat(message.getAllValues().get(1)).contains("没有任何文件被修改");
    }

    @Test
    @DisplayName("改动破坏了编译：把编译输出带回同一个会话让它修")
    void aBrokenBuildIsFedBackIntoTheSameSession() throws Exception {
        compileResult = new CommandRunner.Result(1, "[ERROR] /C:/w/A.java:[3,1] 需要(';')", false);
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    Files.writeString(repo.resolve("src/main/java/demo/A.java"), "class A { int fixed; }\n");
                    return settled("{\"summary\":\"ok\"}");
                });

        StageOutcome outcome = stage.execute(context(withTasks()));

        assertThat(outcome.kind()).isEqualTo(StageOutcome.RETRY);
        assertThat(outcome.message()).contains("仍编译不过").contains("需要(';')");
        ArgumentCaptor<String> session = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(pi, times(3)).run(anyString(), session.capture(), any(), isNull(), anyString(), message.capture(), any(), any());
        assertThat(session.getAllValues()).containsOnly(session.getAllValues().get(0));
        assertThat(message.getAllValues().get(1)).contains("复核编译没有通过").contains("需要(';')");
    }

    @Test
    @DisplayName("编译在第二轮修好：通过，且改动清单来自 git")
    void aBuildFixedOnTheSecondRoundPasses() throws Exception {
        CommandRunner.Result[] results = {new CommandRunner.Result(1, "boom", false), new CommandRunner.Result(0, "", false)};
        int[] calls = {0};
        DevelopStage flakyBuild = new DevelopStage(mock(WorktreeService.class), pi) {
            @Override
            CommandRunner.Result compile(Path worktree, Set<Path> touched) {
                return results[Math.min(calls[0]++, 1)];
            }
        };
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    Files.writeString(repo.resolve("src/main/java/demo/A.java"), "class A { int fixed; }\n");
                    return settled("{\"summary\":\"ok\"}");
                });

        StageOutcome outcome = flakyBuild.execute(context(withTasks()));

        assertThat(outcome.kind()).isEqualTo(StageOutcome.DONE);
        verify(pi, times(2)).run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("pi 会话动了提交历史：这次改动不采纳")
    void aSessionThatMovedHeadIsRejected() throws Exception {
        int[] sneaky = {0};
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    if (sneaky[0]++ > 0) {
                        return settled("{\"summary\":\"ok\"}");
                    }
                    Files.writeString(repo.resolve("src/main/java/demo/A.java"), "class A { int fixed; }\n");
                    git(repo, "add", "-A");
                    git(repo, "commit", "-q", "-m", "sneaky");
                    return settled("{\"summary\":\"ok\"}");
                });

        StageOutcome outcome = stage.execute(context(withTasks()));

        assertThat(outcome.kind()).isEqualTo(StageOutcome.FAILED);
        assertThat(outcome.message()).contains("提交历史");
        // 提交之后相对 HEAD 就「没有改动」：边界检查必须排在改动清单前面，不能把它当成没干活
        verify(pi, times(1)).run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("pi 未就绪等启动问题：直接失败并说明原因，不空转")
    void aSessionThatCannotStartFailsImmediately() {
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("pi 未就绪（安装失败或未启用）"));

        StageOutcome outcome = stage.execute(context(withTasks()));

        assertThat(outcome.kind()).isEqualTo(StageOutcome.FAILED);
        assertThat(outcome.message()).contains("pi 未就绪");
        verify(pi, times(1)).run(anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("会话超时或被取消：失败收尾，不再进入复核")
    void aTimedOutOrCancelledSessionStops() {
        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any()))
                .thenReturn(new PiFlowClient.PiResult("", List.of(), "timeout", true, null));
        assertThat(stage.execute(context(withTasks())).message()).contains("超时");

        when(pi.run(anyString(), anyString(), any(), isNull(), anyString(), anyString(), any(), any()))
                .thenReturn(new PiFlowClient.PiResult("", List.of(), "cancelled", false, null));
        assertThat(stage.execute(context(withTasks())).message()).isEqualTo("已取消");
    }

    @Test
    @DisplayName("工具调用写进进度：只记工具名与对象")
    void toolCallsAreReportedWithoutTheirContent() {
        WorkbenchEventSink sink = new WorkbenchEventSink(events, new ObjectMapper());
        StageContext ctx = new StageContext(
                WorkbenchFixRun.builder().id(5L).itemKey("k1").sessionId(77L).currentStage("develop").build(),
                WorkbenchItem.builder().itemKey("k1").projectPath("/project").title("修复 NPE").build(),
                sink, new HashMap<>(), null);

        DevelopStage.report(Map.of("type", "tool_execution_start", "toolName", "edit", "args",
                Map.of("path", "src/A.java", "oldText", "secret-ish body")), ctx);
        DevelopStage.report(Map.of("type", "tool_execution_start", "toolName", "bash", "args",
                Map.of("command", "mvn -q test-compile\n&& echo done")), ctx);
        DevelopStage.report(Map.of("type", "message_update"), ctx);

        sink.flush(77L);   // 运行日志按合并窗口批量落库：这里是一条合并后的记录
        List<String> logs = new ArrayList<>();
        events.findBySessionId(77L).forEach(e -> logs.add(String.valueOf(e.getPayload())));
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0)).contains("pi edit src/A.java").contains("pi bash mvn -q test-compile")
                .doesNotContain("secret-ish body");
    }

    @Test
    @DisplayName("编译报错全在没动过的文件里才算与改动无关")
    void onlyErrorsInUntouchedFilesCountAsUnrelated() {
        Set<Path> touched = DevelopStage.touched(repo, List.of("src/main/java/demo/A.java"));

        assertThat(DevelopStage.errorsOnlyInUntouchedFiles(
                "[ERROR] /C:/x/src/main/java/Other.java:[312,9] 找不到符号\n[ERROR]   符号:   变量 log\n", touched)).isTrue();
        assertThat(DevelopStage.errorsOnlyInUntouchedFiles(
                "[ERROR] " + repo.resolve("src/main/java/demo/A.java") + ":[312,9] 找不到符号\n", touched)).isFalse();
        assertThat(DevelopStage.errorsOnlyInUntouchedFiles("[ERROR] Failed to execute goal … Compilation failure", touched)).isFalse();
        assertThat(DevelopStage.errorsOnlyInUntouchedFiles(null, touched)).isFalse();
    }

    private static Map<String, Object> withTasks() {
        return Map.of(
                "tasks", Map.of("tasks", List.of(task("修复空指针", "src/main/java/demo/A.java", null))),
                "spec", Map.of("acceptanceCriteria", List.of("不抛异常"), "testCases", List.of()));
    }

    private StageContext context(Map<String, Object> artifacts) {
        WorkbenchFixRun run = WorkbenchFixRun.builder().id(5L).itemKey("k1").sessionId(77L)
                .worktreePath(repo.toString()).branchName("fix/wb-r5-k1").currentStage("develop").build();
        WorkbenchItem item = WorkbenchItem.builder().itemKey("k1").projectPath("/project").title("修复 NPE")
                .location("A.java:12").build();
        return new StageContext(run, item, new WorkbenchEventSink(events, new ObjectMapper()), new HashMap<>(artifacts), null);
    }

    private static Map<String, Object> task(String title, String file, String anchor) {
        Map<String, Object> task = new HashMap<>();
        task.put("title", title);
        task.put("file", file);
        task.put("anchor", anchor);
        task.put("verify", "编译通过");
        return task;
    }

    private static PiFlowClient.PiResult settled(String text) {
        return new PiFlowClient.PiResult(text, List.of(), PiFlowClient.PiResult.SETTLED, false, null);
    }

    private static void git(Path dir, String... args) {
        List<String> full = new ArrayList<>();
        full.add("git");
        full.add("-c");
        full.add("user.name=t");
        full.add("-c");
        full.add("user.email=t@example.com");
        full.addAll(List.of(args));
        CommandRunner.Result result = new CommandRunner().run(dir, full, null, 2, TimeUnit.MINUTES, null, null);
        assertThat(result.exitCode()).as("git " + String.join(" ", args) + " 失败: " + result.output()).isZero();
    }
}
