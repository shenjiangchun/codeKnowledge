package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.fixengine.service.WorktreeService;
import com.huawei.hisi.piagent.FlowToolManifest;
import com.huawei.hisi.piagent.PiFlowClient;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段 5 · 迭代开发：pi 编码 agent 在 git worktree 里完成任务。
 *
 * <p>开发本身交给 pi 会话（清单 {@code workbench-dev}：worktree 内可读、可写、可执行命令，可查图谱的只读 MCP 工具）：
 * 它自己读代码、改代码、写测试、编译并修到通过。后端不再代模型应用编辑指令，只做确定性的事——
 * 用 git 看实际改了哪些文件、复核编译、守住边界（改动只在 worktree、不碰提交历史）。
 *
 * <p>pi 报告完成后后端复核 {@code test-compile}；不过就带着报错回到同一个会话让它接着修（上下文保留），
 * 最多 {@value #VERIFY_ROUNDS} 轮。回归或 E2E 失败后也会带着失败详情回到这里。
 * 每次运行用自己的分支和 worktree（名字里带运行 ID），失败运行的现场原样保留，互不干扰。
 */
@Slf4j
@Component
public class DevelopStage implements StageHandler {

    /** pi 报告完成后后端复核编译；不过就带着报错让它接着修，最多这么多轮。 */
    static final int VERIFY_ROUNDS = 2;

    private final WorktreeService worktreeService;
    private final PiFlowClient piClient;
    private final CommandRunner runner = new CommandRunner();

    public DevelopStage(WorktreeService worktreeService, PiFlowClient piClient) {
        this.worktreeService = worktreeService;
        this.piClient = piClient;
    }

    @Override
    public String key() {
        return "develop";
    }

    @Override
    public StageOutcome execute(StageContext ctx) {
        // 第一轮进来时创建 worktree（回到开发、重启后继续时复用）
        if (ctx.run().getWorktreePath() == null) {
            // 分支名带运行 ID：同一待办多次运行、失败重试，各自一个分支，不会撞上一轮留下的现场
            String branch = "fix/wb-r" + ctx.run().getId() + "-" + shortHash(ctx.item().getItemKey());
            String baseBranch = worktreeService.currentBranch(ctx.item().getProjectPath());
            if (baseBranch == null) {
                return StageOutcome.failed("项目不是 git 仓库或 HEAD 游离，无法创建修复分支");
            }
            String worktreePath;
            try {
                worktreePath = worktreeService.createWorktree(branch, ctx.item().getProjectPath(), baseBranch);
            } catch (Exception e) {
                return StageOutcome.failed("创建修复分支失败：" + e.getMessage());
            }
            ctx.run().setWorktreePath(worktreePath);
            ctx.run().setBranchName(branch);
            ctx.log("已创建 worktree：" + worktreePath + "（基于 " + baseBranch + "）");
        }
        Path worktree = Path.of(ctx.run().getWorktreePath());
        List<Map<String, Object>> tasks = mapsOf(ctx.artifact("tasks").get("tasks"));
        if (tasks.isEmpty()) {
            return StageOutcome.failed("任务清单为空");
        }
        Map<String, Object> spec = ctx.artifact("spec");
        String failure = failureSummary(ctx);
        if (!failure.isEmpty()) {
            ctx.log("带着上一轮验证的失败详情回到开发");
        }

        String headBefore = git(worktree, "rev-parse", "HEAD").trim();
        // 每次进入本阶段是一个新会话；阶段内的回修轮次沿用同一个会话，pi 记得自己改过什么
        String sessionId = "wb-dev-" + ctx.run().getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String message = userPrompt(ctx, worktree, tasks, spec, failure);

        ctx.log("pi 编码 agent 启动：在 worktree 内按任务实现、写测试并编译");
        PiFlowClient.PiResult result;
        try {
            result = session(ctx, sessionId, worktree, SYSTEM_PROMPT, message);
            Verification verify = null;
            for (int round = 0; ; round++) {
                if (ctx.cancelled()) {
                    return StageOutcome.failed("已取消");
                }
                verify = verify(ctx, worktree, headBefore);
                if (verify.outcome() != null) {
                    return verify.outcome();
                }
                if (round >= VERIFY_ROUNDS || result == null || result.timedOut()) {
                    break;
                }
                String problem = verify.changed().isEmpty()
                        ? "worktree 里没有任何文件被修改。请按任务清单实际修改文件（用 edit / write 工具），不要只描述做法。"
                        : "后端复核编译没有通过，请修到通过。编译输出：\n" + tail(verify.compile().output(), 4000);
                ctx.log((verify.changed().isEmpty() ? "pi 没有产生改动" : "后端复核编译未通过")
                        + "，第 " + (round + 1) + " 轮让它接着处理");
                result = session(ctx, sessionId, worktree, SYSTEM_PROMPT, problem);
            }
            if (result == null) {
                return StageOutcome.failed("已取消");
            }
            if (result.timedOut()) {
                return StageOutcome.failed("pi 编码会话超时，未完成开发");
            }
            String why = verify.changed().isEmpty() ? "pi 没有产生任何改动"
                    : "改动后仍编译不过。最后的编译输出：\n" + tail(verify.compile().output(), 1500);
            ctx.log("开发未完成：" + why);
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("worktree", ctx.run().getWorktreePath());
            partial.put("branch", ctx.run().getBranchName());
            partial.put("changedFiles", List.copyOf(verify.changed()));
            // 不直接判死：回到开发入口重来一轮（迭代上限由 runner 掌握）
            return StageOutcome.retry(partial, why);
        } catch (IllegalStateException e) {
            log.error("[WorkbenchFix] runId={} pi 编码会话无法进行: {}", ctx.run().getId(), e.getMessage());
            return StageOutcome.failed("pi 编码会话无法进行：" + e.getMessage());
        }
    }

    /** 一次复核的结果：{@code outcome} 非空表示可以就此收尾（完成，或越界被拒）。 */
    private record Verification(StageOutcome outcome, List<String> changed, CommandRunner.Result compile) {
    }

    /**
     * 每轮会话之后都复核：先看边界（提交历史不能动），再看有没有改动、编译过不过。
     * 边界检查要在改动清单之前——pi 若把改动提交了，相对 HEAD 就「没有改动」，不能因此把它当成没干活。
     */
    private Verification verify(StageContext ctx, Path worktree, String headBefore) {
        if (!headBefore.equals(git(worktree, "rev-parse", "HEAD").trim())) {
            // pi 的清单里没有 git 工具，但 bash 能跑 git：提交历史被动过就不认这次结果（提交推送只能由后端做）
            return new Verification(StageOutcome.failed("pi 会话改变了 worktree 的提交历史（HEAD 已变化），本次改动不采纳"),
                    List.of(), new CommandRunner.Result(0, "", false));
        }
        List<String> changed = changesIn(worktree);
        CommandRunner.Result compile = compile(worktree, touched(worktree, changed));
        if (!changed.isEmpty() && compile.ok()) {
            return new Verification(developed(ctx, changed), changed, compile);
        }
        return new Verification(null, changed, compile);
    }

    private StageOutcome developed(StageContext ctx, List<String> changed) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("worktree", ctx.run().getWorktreePath());
        output.put("branch", ctx.run().getBranchName());
        output.put("changedFiles", List.copyOf(changed));
        ctx.publish("develop", output);
        ctx.log("迭代开发完成：修改 " + changed.size() + " 个文件");
        return StageOutcome.done(output);
    }

    /** 跑一轮 pi 会话（同一 {@code sessionId} 即续聊）。被取消时返回 null；pi 未就绪等启动问题抛 {@link IllegalStateException}。 */
    private PiFlowClient.PiResult session(StageContext ctx, String sessionId, Path worktree, String system, String message) {
        PiFlowClient.PiResult result = piClient.run(FlowToolManifest.WORKBENCH_DEV, sessionId, worktree, null,
                system, message, event -> report(event, ctx), ctx::cancelled);
        return "cancelled".equals(result.stopReason()) || "aborted".equals(result.stopReason()) ? null : result;
    }

    /** pi 的工具调用写进进度（只记工具名与对象，不记内容）。 */
    static void report(Map<String, Object> event, StageContext ctx) {
        if (!"tool_execution_start".equals(String.valueOf(event.get("type")))) {
            return;
        }
        String tool = String.valueOf(event.getOrDefault("toolName", "?"));
        String target = "";
        if (event.get("args") instanceof Map<?, ?> args) {
            for (String key : List.of("path", "file_path", "command", "pattern", "query")) {
                if (args.get(key) != null) {
                    target = String.valueOf(args.get(key));
                    break;
                }
            }
        }
        String brief = target.replace('\n', ' ');
        ctx.log("pi " + tool + (brief.isEmpty() ? "" : " " + brief.substring(0, Math.min(90, brief.length()))));
    }

    // ──────────────────────────── 提示词 ────────────────────────────

    static final String SYSTEM_PROMPT = """
            你是资深工程师，在一个独立的 git worktree 里完成一项技术债修复的开发任务。工作目录就是 worktree 根，只能修改这个目录里的文件。

            工作方式：
            - 先读任务清单涉及的文件，弄清现状再动手；需要查调用关系可以用图谱工具。
            - 按任务清单逐项实现；改动尽量小，只改实现任务必需的部分，不重写无关代码。
            - Spec 的测试用例是验收标准：把它们落成自动化用例。不要为了用例通过去削弱断言——要修的是实现代码；
              测试类自身有问题（编译不过、setUp 报错、找不到字段）就修测试类，断言与 Spec 的通过标准保持一致。
            - 写测试类：只通过被测类的公共方法验证（直接 new 被测类，或 MockMvcBuilders.standaloneSetup）；
              不要用反射查找私有字段，不要假设类里有某个依赖字段，不要为了注入 mock 去改被测类的构造器或可见性。
              只使用项目里已有的测试依赖与版本，不要臆造类和包。
            - 每完成一部分就编译验证（命令见任务说明），编译不过就修到通过；也可以运行新增的测试类确认它们通过。
            - 不要执行任何 git 写操作（add / commit / push / checkout / reset / stash / branch 等）——提交与推送由后端完成；
              可以用 git status、git diff 查看自己的改动。
            - 不要启动服务，不要运行需要外部依赖（数据库、网络服务）的命令。

            全部完成后，最后一条消息只输出一个 JSON 对象（不要代码围栏、不要其它文字）：
            {"summary": "一句话说明做了什么", "tasks": [{"index": 1, "status": "done | skipped", "note": "说明"}], "compile": "passed | failed"}
            """;

    /** 任务说明：待办、任务清单、验收标准与测试用例、项目现状、上一轮没通过的原因。 */
    static String userPrompt(StageContext ctx, Path worktree, List<Map<String, Object>> tasks,
                             Map<String, Object> spec, String failure) {
        StringBuilder user = new StringBuilder();
        user.append("待办：").append(ctx.item().getTitle()).append('\n');
        if (ctx.item().getLocation() != null) {
            user.append("位置：").append(ctx.item().getLocation()).append('\n');
        }
        user.append("项目根（worktree，你的工作目录）：").append(worktree).append('\n');
        user.append("\n任务清单：\n");
        for (int i = 0; i < tasks.size(); i++) {
            Map<String, Object> task = tasks.get(i);
            user.append(i + 1).append(". ").append(task.get("title")).append('\n');
            if (task.get("file") != null) {
                user.append("   文件：").append(task.get("file")).append('\n');
            }
            if (task.get("anchor") != null && !String.valueOf(task.get("anchor")).isBlank()) {
                user.append("   涉及的类或方法：").append(task.get("anchor")).append('\n');
            }
            if (task.get("verify") != null) {
                user.append("   验证方式：").append(task.get("verify")).append('\n');
            }
        }
        user.append("\n验收标准：").append(spec.get("acceptanceCriteria")).append('\n');
        user.append("测试用例（Spec 阶段确定，必须落成自动化用例）：\n");
        for (Map<String, Object> testCase : mapsOf(spec.get("testCases"))) {
            user.append("  - [").append(testCase.get("type")).append("] ").append(testCase.get("name"))
                    .append(" | 落点 ").append(testCase.get("target"))
                    .append(" | 通过标准 ").append(testCase.get("verify")).append('\n');
        }
        if (spec.get("httpMappings") != null) {
            user.append("HTTP 接口清单：").append(spec.get("httpMappings")).append('\n');
        }
        String buildGaps = CommandRunner.testSetupGaps(worktree);
        if (!buildGaps.isEmpty()) {
            user.append("\n项目缺测试基础设施，改构建文件的任务必须补上这些缺口：\n").append(buildGaps)
                    .append("做法：依赖插在 </dependencies> 之前（pom 里没有 <dependencies> 就新建一个）；")
                    .append("插件放在 <build><plugins> 里，没有 <build> 就在 </project> 之前新建。\n");
        }
        String springNote = CommandRunner.springContextNote(worktree);
        if (!springNote.isEmpty()) {
            user.append("\n项目现状（测试类必须遵守）：\n").append(springNote);
        }
        user.append("\n编译验证命令（主代码和测试代码一起编译，完成后必须通过）：").append(compileCommand(worktree)).append('\n');
        if (!failure.isBlank()) {
            user.append("\n需要解决的问题（上一轮验证没有通过，请在现有改动的基础上继续修）：\n").append(limit(failure, 6000)).append('\n');
        }
        return user.toString();
    }

    /** 给 pi 的编译命令：与后端复核用的是同一条。 */
    static String compileCommand(Path worktree) {
        if (!CommandRunner.isMavenProject(worktree)) {
            return "该项目不是 Maven 项目，没有统一编译命令；按项目自身的构建方式验证";
        }
        List<String> command = CommandRunner.mavenCommand(worktree);
        String maven = command.get(command.size() - 1);
        boolean wrapper = maven.contains("mvnw");
        return (wrapper ? "./" + maven : "mvn") + " -q test-compile（Windows 下 mvn.cmd -q test-compile 亦可）";
    }

    // ──────────────────────────── 确定性检查 ────────────────────────────

    /**
     * worktree 里相对 HEAD 的改动（含未跟踪的新文件），路径用 {@code /} 分隔。
     * 以 git 为准而不是信模型的自述；构建产物目录不算。
     */
    static List<String> changesIn(Path worktree) {
        List<String> out = new ArrayList<>();
        for (String line : git(worktree, "-c", "core.quotepath=false", "status", "--porcelain", "--untracked-files=all").split("\\R")) {
            if (line.length() < 4) {
                continue;
            }
            String path = line.substring(3).trim();
            int arrow = path.indexOf(" -> ");
            if (arrow >= 0) {
                path = path.substring(arrow + 4);   // 改名取新路径
            }
            path = path.replace('\\', '/');
            if (path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1);
            }
            if (path.startsWith("target/") || out.contains(path)) {
                continue;
            }
            out.add(path);
        }
        return out;
    }

    static String git(Path worktree, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        CommandRunner.Result result = new CommandRunner().run(worktree, command, null, 2, TimeUnit.MINUTES, null, null);
        return result.output() == null ? "" : result.output();
    }

    /** Java 项目跑 mvn -q test-compile：主代码和测试代码一起编译，生成的用例有问题在这一步就暴露。 */
    CommandRunner.Result compile(Path worktree, Set<Path> touched) {
        if (!CommandRunner.isMavenProject(worktree)) {
            // 非 Java 项目：无统一编译命令，跳过并视为通过（由回归与 E2E 阶段兜底）
            return new CommandRunner.Result(0, "skip: non-Java project", false);
        }
        List<String> command = new ArrayList<>(CommandRunner.mavenCommand(worktree));
        command.add("-q");
        command.add("test-compile");
        CommandRunner.Result result = runner.run(worktree, command, null, 15, TimeUnit.MINUTES, null, null);
        if (!result.ok() && !result.timedOut() && errorsOnlyInUntouchedFiles(result.output(), touched)) {
            log.info("[WorkbenchFix] 编译失败但报错都在本次未改动的文件里，重试一次");
            result = runner.run(worktree, command, null, 15, TimeUnit.MINUTES, null, null);
        }
        return result;
    }

    /** 本次运行动过的文件（绝对路径），用来判断编译报错是否与改动有关。 */
    static Set<Path> touched(Path worktree, List<String> changedFiles) {
        Set<Path> out = new HashSet<>();
        for (String file : changedFiles) {
            out.add(worktree.resolve(file).toAbsolutePath().normalize());
        }
        return out;
    }

    /** Maven 在 Windows 上把位置打印成 {@code /C:/path/File.java:[行,列]}，盘符前多一个斜杠。 */
    private static final Pattern ERROR_LOCATION = Pattern.compile("]\\s+[/\\\\]?([A-Za-z]:[^\\r\\n]*?\\.java):\\[\\d+,\\d+\\]");

    /** 编译报错的位置是否全在 {@code touched} 之外的文件里；一条位置都解析不出来时按「有关」处理。 */
    static boolean errorsOnlyInUntouchedFiles(String output, Set<Path> touched) {
        Set<Path> reported = new HashSet<>();
        Matcher m = ERROR_LOCATION.matcher(output == null ? "" : output);
        while (m.find()) {
            reported.add(Path.of(m.group(1)).toAbsolutePath().normalize());
        }
        if (reported.isEmpty()) {
            return false;
        }
        for (Path path : reported) {
            if (touched.contains(path)) {
                return false;
            }
        }
        return true;
    }

    // ──────────────────────────── 工具 ────────────────────────────

    /** 回到开发时上一轮哪里没过：回归的失败用例与输出尾部、E2E 的失败步骤与用例。 */
    @SuppressWarnings("unchecked")
    static String failureSummary(StageContext ctx) {
        StringBuilder summary = new StringBuilder();
        Map<String, Object> failure = ctx.artifact("lastFailure");
        if (failure.get("message") != null) {
            summary.append(failure.get("message")).append('\n');
        }
        Map<String, Object> tests = ctx.artifact("tests");
        if (tests.get("surefire") instanceof Map<?, ?> surefire && surefire.get("failed") instanceof List<?> failed && !failed.isEmpty()) {
            summary.append("失败的测试：").append(failed).append('\n');
        }
        if (tests.get("outputTail") instanceof String tail && !tail.isBlank()) {
            summary.append("测试输出（尾部）：\n").append(tail).append('\n');
        }
        Map<String, Object> e2e = ctx.artifact("e2e");
        if (e2e.get("reason") instanceof String reason && !reason.isBlank()) {
            summary.append("E2E 未通过：").append(limit(reason, 1500)).append('\n');
        }
        if (e2e.get("serviceOutputTail") instanceof String serviceOutput && !serviceOutput.isBlank()) {
            summary.append("服务输出（尾部）：\n").append(limit(serviceOutput, 3000)).append('\n');
        }
        if (e2e.get("apiResults") instanceof List<?> results) {
            for (Object result : results) {
                if (result instanceof Map<?, ?> r && Boolean.FALSE.equals(r.get("passed"))) {
                    // 把期望、实际状态码和响应体都给出来：模型据此判断是改实现还是行为本来就如此
                    summary.append("E2E 接口用例未通过：").append(r.get("name")).append("（")
                            .append(r.get("request")).append("）期望 ").append(r.get("expected"));
                    if (r.containsKey("status")) {
                        summary.append("，实际 ").append(r.get("status"));
                    }
                    if (r.get("body") instanceof String body && !body.isBlank()) {
                        summary.append("，响应 ").append(limit(body, 200));
                    }
                    if (r.get("error") != null) {
                        summary.append("，").append(limit(String.valueOf(r.get("error")), 300));
                    }
                    summary.append('\n');
                }
            }
        }
        return summary.toString().trim();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapsOf(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n…（截断）";
    }

    private static String tail(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : "…（截断）\n" + text.substring(text.length() - max));
    }

    static String shortHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
