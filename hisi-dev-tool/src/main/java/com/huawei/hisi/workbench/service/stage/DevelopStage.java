package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.fixengine.service.WorktreeService;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 阶段 5 · 迭代开发：在 git worktree 里按任务修改代码。
 *
 * <p>内置执行器的工作方式：模型对每个任务返回「文件 + 精确替换块」，后端应用后连测试代码一起编译；
 * 片段对不上或编译失败，都把真实文件内容和错误回给模型重出，最多 3 轮。路径限定在 worktree 内。
 * 每次运行用自己的分支和 worktree（名字里带运行 ID），失败运行的现场原样保留，互不干扰。
 *
 * <p>回归或 E2E 失败后会带着失败详情回到这里：已应用过的改动会被识别并跳过，模型据失败信息继续修。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevelopStage implements StageHandler {

    private static final int REPAIR_ROUNDS = 3;
    /** 同一个任务向模型要改动方案的尝试次数（输出被截断、解析不出 JSON 时原任务重试）。 */
    private static final int LLM_ATTEMPTS = 3;
    private static final int FILE_CONTENT_LIMIT = 8000;
    /** 判定「改动已应用」时 after 至少要有这么长，避免一小段通用代码误判。 */
    private static final int MIN_APPLIED_LENGTH = 16;

    private final WorktreeService worktreeService;
    private final AgentLlmClient llm;
    private final CommandRunner runner = new CommandRunner();

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
        // 项目缺测试基础设施时，改构建文件的任务要知道补什么；没有 Spring 启动类时，测试类不能依赖容器
        String buildGaps = CommandRunner.testSetupGaps(worktree);
        String springNote = CommandRunner.springContextNote(worktree);

        List<Map<String, Object>> taskResults = new ArrayList<>();
        // 回到开发时接着上一轮的改动清单：这样实现任务仍然能看到上一轮写好的测试类
        List<String> changedFiles = new ArrayList<>();
        if (ctx.artifact("develop").get("changedFiles") instanceof List<?> previous) {
            for (Object file : previous) {
                changedFiles.add(String.valueOf(file));
            }
        }
        for (int i = 0; i < tasks.size(); i++) {
            if (ctx.cancelled()) {
                return StageOutcome.failed("已取消");
            }
            Map<String, Object> task = tasks.get(i);
            Path target = resolveFile(worktree, String.valueOf(task.getOrDefault("file", "")));
            String shownPath = target == null ? String.valueOf(task.getOrDefault("file", "（未指定文件）"))
                    : worktree.relativize(target).toString().replace('\\', '/');
            ctx.log("任务 " + (i + 1) + "/" + tasks.size() + "：" + task.get("title") + "（" + shownPath + "）");
            // 这个任务动过的文件在第一次被改之前的样子：回修轮次用尽时据此还原
            Map<Path, String> backups = new LinkedHashMap<>();

            // feedback 非空时进入回修：编译错误或片段应用失败都反馈给模型重新生成
            String feedback = failure;
            boolean compileOk = false;
            for (int round = 0; round < REPAIR_ROUNDS && !compileOk; round++) {
                if (ctx.cancelled()) {
                    return StageOutcome.failed("已取消");
                }
                if (round > 0) {
                    ctx.log("第 " + round + " 次回修");
                }
                // 模型输出被截断、解析不出 JSON 是会发生的（生成长测试类时尤其如此）：原任务重试，提示它精简输出
                Map<String, Object> edits = null;
                String llmNote = null;
                for (int attempt = 0; attempt < LLM_ATTEMPTS && edits == null; attempt++) {
                    final String note = llmNote;
                    edits = planEdits(ctx, task, spec, target, shownPath, feedback, buildGaps, springNote,
                            writtenTests(worktree, changedFiles), note);
                    if (edits == null) {
                        llmNote = "上一次的输出不是完整、可解析的 JSON（多半是输出过长被截断）。请精简：只给完成这个任务必需的改动，"
                                + "测试代码保持紧凑，不要任何解释。";
                        ctx.log("改动方案第 " + (attempt + 1) + " 次没有得到可解析的 JSON，重试");
                    }
                }
                if (edits == null) {
                    Map<String, Object> partial = new LinkedHashMap<>();
                    partial.put("worktree", ctx.run().getWorktreePath());
                    partial.put("branch", ctx.run().getBranchName());
                    partial.put("changedFiles", List.copyOf(changedFiles));
                    partial.put("failedTask", Map.of("index", i + 1, "title", String.valueOf(task.get("title")), "file", shownPath));
                    // 不直接判死：带着已完成的改动回到开发入口再来一轮（迭代上限由 runner 掌握）
                    return StageOutcome.retry(partial, "任务「" + task.get("title") + "」连续 " + LLM_ATTEMPTS
                            + " 次没能生成可解析的改动方案（模型输出不完整），已完成的改动保留在 worktree 里");
                }
                if (edits.get("edits") instanceof List<?> planned && planned.isEmpty()) {
                    if (feedback == null || feedback.isBlank()) {
                        feedback = "必须给出至少一处改动（edits 不能为空）";
                        continue;
                    }
                    // 回到开发时模型判断这个任务已完成、不用再改：编译确认后进入下一个任务
                    ctx.log("模型判断该任务无需再改，编译确认");
                    CommandRunner.Result result = compile(worktree, touched(worktree, changedFiles));
                    if (result.ok()) {
                        compileOk = true;
                    } else {
                        feedback = compileFeedback(worktree, result);
                    }
                    continue;
                }
                for (Path planned : plannedFiles(worktree, edits)) {
                    backups.putIfAbsent(planned, Files.isRegularFile(planned) ? readFile(planned) : null);
                }
                try {
                    List<String> files = applyEdits(worktree, edits);
                    changedFiles.addAll(files.stream().filter(f -> !changedFiles.contains(f)).toList());
                } catch (EditException e) {
                    if (e.getMessage().contains("越出")) {
                        // 路径越界是安全边界，不重试
                        return StageOutcome.failed("任务 " + (i + 1) + " 改动路径越界：" + e.getMessage());
                    }
                    feedback = "改动没有应用：" + e.getMessage() + "。请照「文件内容」里的原文给出改动（行号以竖线左侧为准）。";
                    continue;
                }
                CommandRunner.Result result = compile(worktree, touched(worktree, changedFiles));
                compileOk = result.ok();
                feedback = result.ok() ? "" : compileFeedback(worktree, result);
                if (compileOk) {
                    ctx.log("编译通过（含测试代码）");
                }
            }
            if (!compileOk) {
                // 把这个任务动过的文件恢复原样：工作区回到最后一个能编译的状态，下一轮从这里重来
                for (Map.Entry<Path, String> backup : backups.entrySet()) {
                    restore(backup.getKey(), backup.getValue());
                    if (backup.getValue() == null) {
                        changedFiles.removeIf(file -> worktree.resolve(file).toAbsolutePath().normalize().equals(backup.getKey()));
                    }
                }
                Map<String, Object> partial = new LinkedHashMap<>();
                partial.put("worktree", ctx.run().getWorktreePath());
                partial.put("branch", ctx.run().getBranchName());
                partial.put("changedFiles", List.copyOf(changedFiles));
                partial.put("failedTask", Map.of("index", i + 1, "title", String.valueOf(task.get("title")), "file", shownPath));
                ctx.log("任务 " + (i + 1) + " 在 " + REPAIR_ROUNDS + " 轮内未通过编译，带着错误回到迭代开发");
                // 不直接判死：回到开发入口重来一轮（迭代上限由 runner 掌握）
                return StageOutcome.retry(partial, "任务「" + task.get("title") + "」在 " + REPAIR_ROUNDS
                        + " 轮内未通过编译，文件已还原。最后的编译错误：\n" + tail(feedback, 1500));
            }
            taskResults.add(Map.of("index", i + 1, "title", String.valueOf(task.get("title")), "file", shownPath, "compile", "passed"));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("worktree", ctx.run().getWorktreePath());
        output.put("branch", ctx.run().getBranchName());
        output.put("changedFiles", changedFiles);
        output.put("taskResults", taskResults);
        ctx.publish("develop", output);
        ctx.log("迭代开发完成：修改 " + changedFiles.size() + " 个文件");
        return StageOutcome.done(output);
    }

    // ──────────────────────────── 内置执行器 ────────────────────────────

    private static final String EDIT_SYSTEM = """
            你是执行精确代码修改的工程师。对给出的任务，返回 JSON，edits 里每个元素是下面两种之一：
            {"file": "相对项目根的文件路径", "before": "文件中的原文片段", "after": "替换后的内容"}
            {"file": "相对项目根的文件路径", "startLine": 120, "endLine": 180, "contextLine": "第 120 行的原文", "after": "替换后的完整内容"}
            硬性要求：
            - 方式一（before/after）：before 必须逐字符来自「文件内容」的原文（竖线右侧，缩进、空格、标点完全一致），并且在文件中只出现一次；
            - 方式二（行号区间）：把第 startLine 到 endLine 行（含首尾）整体换成 after；行号以竖线左侧的数字为准；
              contextLine 必须逐字符等于 startLine 那一行的原文，用来防止行号错位；整段重写、拆分或替换较长的方法用这种方式；
            - 新增方法、字段、类：把插入点那一行作为 startLine 和 endLine，after 写「原行 + 新增内容」；
            - 新文件用空 before 表示（before 为空字符串时创建文件，after 为完整内容）；
            - 每次修改尽量小：只改实现任务必需的部分，不得重写无关代码；
            - Spec 的测试用例是验收标准：不要为了让用例通过去削弱断言，要修的是实现代码；
              测试类自身有问题时（编译不过、setUp 报错、找不到字段）修测试类，但断言要和 Spec 的通过标准保持一致；
            - 写测试类：只通过被测类的公共方法验证 Spec 的通过标准——直接 new 被测类，或用 MockMvcBuilders.standaloneSetup；
              不要用反射查找私有字段，不要假设类里有某个依赖字段，不要为了注入 mock 去改被测类的构造器或可见性；
            - 如果这个任务已经完成、不需要再改，输出 {"edits": []}；
            - file 只能指向任务指定的文件；只输出 JSON 对象，不要代码围栏，不要解释文字。
            """;

    private Map<String, Object> planEdits(StageContext ctx, Map<String, Object> task, Map<String, Object> spec,
                                           Path target, String shownPath, String feedback, String buildGaps,
                                           String springNote, String writtenTests, String llmNote) {
        StringBuilder user = new StringBuilder();
        user.append("待办：").append(ctx.item().getTitle()).append('\n');
        if (ctx.item().getLocation() != null) {
            user.append("位置：").append(ctx.item().getLocation()).append('\n');
        }
        user.append("项目根（worktree）：").append(ctx.run().getWorktreePath()).append('\n');
        user.append("\n当前任务：").append(task.get("title")).append('\n');
        user.append("要改的文件（edits 里的 file 必须是它）：").append(shownPath).append('\n');
        if (task.get("anchor") != null && !String.valueOf(task.get("anchor")).isBlank()) {
            user.append("涉及的类或方法：").append(task.get("anchor")).append('\n');
        }
        if (task.get("verify") != null) {
            user.append("验证方式：").append(task.get("verify")).append('\n');
        }
        user.append("\n验收标准：").append(spec.get("acceptanceCriteria")).append('\n');
        user.append("Spec 里的测试用例：\n");
        for (Map<String, Object> testCase : mapsOf(spec.get("testCases"))) {
            user.append("  - [").append(testCase.get("type")).append("] ").append(testCase.get("name"))
                    .append(" | 落点 ").append(testCase.get("target"))
                    .append(" | 通过标准 ").append(testCase.get("verify")).append('\n');
        }
        if (spec.get("httpMappings") != null) {
            user.append("HTTP 接口清单：").append(spec.get("httpMappings")).append('\n');
        }
        if (target != null) {
            String content = readFile(target);
            if (content != null) {
                user.append("\n文件内容（").append(shownPath).append("）：\n")
                        .append(fileView(content, task.get("anchor") == null ? "" : String.valueOf(task.get("anchor")),
                                FILE_CONTENT_LIMIT * 2)).append('\n');
            }
        } else {
            user.append("\n文件现状：该文件还不存在，这是一个新建文件的任务。\n");
        }
        if (shownPath.endsWith("pom.xml") && buildGaps != null && !buildGaps.isEmpty()) {
            user.append("\n这是补齐构建文件的任务，下面列出的缺口必须全部补上：\n").append(buildGaps)
                    .append("做法：依赖插在 </dependencies> 之前（pom 里没有 <dependencies> 就新建一个）；")
                    .append("插件放在 <build><plugins> 里，没有 <build> 就在 </project> 之前新建。")
                    .append("可以给多个 edit，每个 edit 的 before 都取自上面的文件现状且只出现一次。\n");
        }
        if (shownPath.contains("src/test/") && springNote != null && !springNote.isEmpty()) {
            user.append("\n项目现状（测试类必须遵守）：\n").append(springNote);
        }
        if (!shownPath.contains("src/test/") && writtenTests != null && !writtenTests.isEmpty()) {
            user.append("\n前面任务已经写好的测试类（实现必须让它们通过，不得修改它们）：\n")
                    .append(limit(writtenTests, FILE_CONTENT_LIMIT)).append('\n');
        }
        if (feedback != null && !feedback.isBlank()) {
            user.append("\n需要解决的问题（上一轮没有通过）：\n").append(limit(feedback, 6000)).append('\n');
        }
        if (llmNote != null) {
            user.append("\n").append(llmNote).append('\n');
        }
        try {
            return llm.completeJson(EDIT_SYSTEM, user.toString(), 12000);
        } catch (Exception e) {
            log.warn("[WorkbenchFix] 改动方案生成失败 runId={}: {}", ctx.run().getId(), e.getMessage());
            return null;
        }
    }

    static class EditException extends Exception {
        EditException(String message) { super(message); }
    }

    /**
     * 应用一组精确替换。路径限定在 worktree 内；before 必须在文件中唯一出现。
     * 换行符按文件现状处理：Windows 检出的文件是 CRLF，模型输出的是 LF，匹配前统一、写回时还原。
     * before 不在而 after 已在文件里，说明这处改动上一轮已经应用过（回到开发、重启后继续），跳过即可。
     */
    @SuppressWarnings("unchecked")
    static List<String> applyEdits(Path worktree, Map<String, Object> plan) throws EditException {
        Object raw = plan.get("edits");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new EditException("模型没有给出改动");
        }
        List<String> changed = new ArrayList<>();
        Path root = worktree.toAbsolutePath().normalize();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> edit)) {
                continue;
            }
            String file = edit.get("file") == null ? "" : String.valueOf(edit.get("file")).trim();
            String before = edit.get("before") == null ? "" : String.valueOf(edit.get("before"));
            String after = edit.get("after") == null ? "" : String.valueOf(edit.get("after"));
            if (file.isEmpty()) {
                throw new EditException("改动缺少文件路径");
            }
            Path target = root.resolve(file).normalize();
            if (!target.startsWith(root)) {
                throw new EditException("文件路径越出 worktree：" + file);
            }
            try {
                if (edit.get("startLine") != null && edit.get("endLine") != null) {
                    // 行号区间替换：大文件里整段重写时，不必逐字符复述原文
                    applyLineRange(target, file, parseInt(edit.get("startLine")), parseInt(edit.get("endLine")),
                            edit.get("contextLine") == null ? null : String.valueOf(edit.get("contextLine")), after);
                    changed.add(file);
                    continue;
                }
                if (before.isEmpty()) {
                    if (Files.exists(target) && after.length() >= MIN_APPLIED_LENGTH && Files.readString(target, StandardCharsets.UTF_8).equals(after)) {
                        changed.add(file);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, after, StandardCharsets.UTF_8);
                    changed.add(file);
                    continue;
                }
                String rawContent = Files.readString(target, StandardCharsets.UTF_8);
                boolean crlf = rawContent.contains("\r\n");
                String content = crlf ? rawContent.replace("\r\n", "\n") : rawContent;
                String wantBefore = before.replace("\r\n", "\n");
                String wantAfter = after.replace("\r\n", "\n");

                int first = content.indexOf(wantBefore);
                if (first < 0) {
                    if (wantAfter.length() >= MIN_APPLIED_LENGTH && content.contains(wantAfter)) {
                        // 上一轮已经应用过
                        changed.add(file);
                        continue;
                    }
                    throw new EditException("文件 " + file + " 中找不到 before 片段");
                }
                if (content.indexOf(wantBefore, first + wantBefore.length()) >= 0) {
                    throw new EditException("文件 " + file + " 中 before 片段出现了多次，请给更长的上下文让它唯一");
                }
                String result = content.replace(wantBefore, wantAfter);
                Files.writeString(target, crlf ? result.replace("\n", "\r\n") : result, StandardCharsets.UTF_8);
                changed.add(file);
            } catch (EditException e) {
                throw e;
            } catch (IOException e) {
                throw new EditException("读写文件失败 " + file + "：" + e.getMessage());
            }
        }
        return changed;
    }

    /**
     * 按行号区间替换（1 起、含首尾）。{@code contextLine} 必须与 {@code startLine} 那一行一致（忽略行尾空白）：
     * 模型看到的行号一旦错位，在这里就被拦下，不会改坏别的代码。
     */
    static void applyLineRange(Path target, String file, int startLine, int endLine, String contextLine, String after)
            throws EditException {
        if (startLine < 1 || endLine < startLine) {
            throw new EditException("行号区间不合法：" + startLine + "–" + endLine + "（从 1 起、含首尾）");
        }
        try {
            String raw = Files.readString(target, StandardCharsets.UTF_8);
            boolean crlf = raw.contains("\r\n");
            String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
            if (endLine > lines.length) {
                throw new EditException("行号区间 " + startLine + "–" + endLine + " 超出文件范围（" + file + " 共 "
                        + lines.length + " 行）：请照最新的文件内容重新给出行号");
            }
            if (contextLine != null && !contextLine.isBlank()
                    && !lines[startLine - 1].stripTrailing().equals(contextLine.replace("\r", "").stripTrailing())) {
                throw new EditException("startLine=" + startLine + " 处的内容与 contextLine 不一致：该行实际是「"
                        + lines[startLine - 1].strip() + "」。请照文件内容重新给出行号和 contextLine");
            }
            List<String> replacement = new ArrayList<>();
            for (String line : after.replace("\r\n", "\n").split("\n", -1)) {
                replacement.add(line);
            }
            // after 末尾的换行不算一行
            if (replacement.size() > 1 && replacement.get(replacement.size() - 1).isEmpty()) {
                replacement.remove(replacement.size() - 1);
            }
            List<String> result = new ArrayList<>();
            result.addAll(List.of(lines).subList(0, startLine - 1));
            result.addAll(replacement);
            result.addAll(List.of(lines).subList(endLine, lines.length));
            String joined = String.join("\n", result);
            Files.writeString(target, crlf ? joined.replace("\n", "\r\n") : joined, StandardCharsets.UTF_8);
        } catch (EditException e) {
            throw e;
        } catch (IOException e) {
            throw new EditException("读写文件失败 " + file + "：" + e.getMessage());
        }
    }

    private static int parseInt(Object value) throws EditException {
        try {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new EditException("行号不是整数：" + value);
        }
    }

    /**
     * 给模型看的文件内容：每行带上文件里的真实行号。文件不长就给全文；长了只给 anchor（类或方法）附近的一段，
     * 并始终带上文件头（package 与 import）——改代码常常要补 import。
     */
    static String fileView(String rawContent, String anchor, int maxChars) {
        String content = rawContent.replace("\r\n", "\n");
        String[] lines = content.split("\n", -1);
        int width = Math.max(3, String.valueOf(lines.length).length());
        if (content.length() <= maxChars) {
            return numbered(lines, 1, lines.length, width);
        }
        StringBuilder out = new StringBuilder();
        out.append("文件共 ").append(lines.length).append(" 行，篇幅较长，下面只给出文件头和相关片段。")
                .append("竖线左侧是文件里的真实行号。\n");
        int headEnd = Math.min(lines.length, 60);
        out.append("—— 文件头（第 1–").append(headEnd).append(" 行）——\n").append(numbered(lines, 1, headEnd, width));
        int at = locate(lines, anchor);
        int from = Math.max(1, (at < 0 ? 0 : at) - 14);   // 行号从 1 起；锚点前留十几行上下文
        int to = Math.min(lines.length, from + 260 - 1);
        while (to > from + 20 && numbered(lines, from, to, width).length() > maxChars) {
            to -= 10;
        }
        out.append("—— 相关片段（第 ").append(from).append("–").append(to).append(" 行，")
                .append(at < 0 ? "未能按「" + anchor + "」定位，给出开头部分" : "按「" + anchor + "」定位").append("）——\n")
                .append(numbered(lines, from, to, width));
        return out.toString();
    }

    private static String numbered(String[] lines, int from, int to, int width) {
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(1, from); i <= Math.min(to, lines.length); i++) {
            out.append(String.format("%" + width + "d | %s\n", i, lines[i - 1]));
        }
        return out.toString();
    }

    /**
     * anchor 对应的声明行下标（0 起）；找不到返回 -1。
     * 接受任务清单里会出现的几种写法：{@code UserController#getUser:20}、{@code UserController#getUser}、
     * {@code UserController.getUser}、{@code getUser}、{@code UserController}。
     */
    static int locate(String[] lines, String anchor) {
        String name = anchor == null ? "" : anchor.trim().replaceAll(":\\d+$", "");
        if (name.isEmpty()) {
            return -1;
        }
        String type = "";
        String target = name;
        if (name.contains("#")) {
            type = name.substring(0, name.indexOf('#'));
            target = name.substring(name.indexOf('#') + 1);
        }
        if (type.contains(".")) {
            type = type.substring(type.lastIndexOf('.') + 1);
        }
        String simple = target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : target;
        if (!simple.isEmpty()) {
            Pattern declaration = Pattern.compile("^[^/]*\\b" + Pattern.quote(simple) + "\\s*\\(");
            for (int i = 0; i < lines.length; i++) {
                if (declaration.matcher(lines[i]).find() && !lines[i].trim().startsWith("//")
                        && !lines[i].contains("." + simple + "(")) {
                    return i;
                }
            }
        }
        String typeName = type.isEmpty() ? simple : type;
        if (!typeName.isEmpty()) {
            Pattern typeDeclaration = Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(typeName) + "\\b");
            for (int i = 0; i < lines.length; i++) {
                if (typeDeclaration.matcher(lines[i]).find()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 改动方案涉及的文件（worktree 内的绝对路径；越界的忽略，应用时会报错拦住）。 */
    @SuppressWarnings("unchecked")
    static List<Path> plannedFiles(Path worktree, Map<String, Object> edits) {
        List<Path> out = new ArrayList<>();
        Path root = worktree.toAbsolutePath().normalize();
        if (edits.get("edits") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> edit && edit.get("file") != null) {
                    Path path = root.resolve(String.valueOf(edit.get("file")).trim()).normalize();
                    if (path.startsWith(root) && !out.contains(path)) {
                        out.add(path);
                    }
                }
            }
        }
        return out;
    }

    /** 把任务动过的文件恢复成任务开始前的样子（原本不存在的文件删掉）。 */
    static void restore(Path target, String contentBefore) {
        if (target == null) {
            return;
        }
        try {
            if (contentBefore == null) {
                Files.deleteIfExists(target);
            } else {
                Files.writeString(target, contentBefore, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("[WorkbenchFix] 还原文件失败 {}: {}", target, e.getMessage());
        }
    }

    /** 本次运行里已经写出的测试类源码（改动文件里位于 src/test/ 的那些），拼成一段供实现任务对照。 */
    static String writtenTests(Path worktree, List<String> changedFiles) {
        StringBuilder out = new StringBuilder();
        for (String file : changedFiles) {
            if (!file.replace('\\', '/').contains("src/test/")) {
                continue;
            }
            String content = readFile(worktree.resolve(file));
            if (content == null) {
                continue;
            }
            out.append("// ---- ").append(file).append(" ----\n")
                    .append(limit(content.replace("\r\n", "\n"), FILE_CONTENT_LIMIT)).append('\n');
            if (out.length() > FILE_CONTENT_LIMIT * 2) {
                break;
            }
        }
        return out.toString();
    }

    /**
     * Java 项目跑 mvn -q test-compile：主代码和测试代码一起编译，生成的用例有问题在这一步就暴露。
     *
     * <p>首次失败且报错全在本次没动过的文件里时重试一次：真机上出现过前一个任务刚编译通过、没有任何改动，
     * 下一次编译却报 Lombok 生成的方法「找不到符号」——环境抖动，不是改动引起的，不该算到模型头上。
     */
    private CommandRunner.Result compile(Path worktree, Set<Path> touched) {
        if (!CommandRunner.isMavenProject(worktree)) {
            // 非 Java 项目：无统一编译命令，跳过并视为通过（由回归与 E2E 阶段兜底）
            return new CommandRunner.Result(0, "skip: non-Java project", false);
        }
        List<String> command = new ArrayList<>(CommandRunner.mavenCommand(worktree));
        command.add("-q");
        command.add("test-compile");
        CommandRunner.Result result = runner.run(worktree, command, null, 15, TimeUnit.MINUTES, null, null);
        if (!result.ok() && !result.timedOut() && errorsOnlyInUntouchedFiles(worktree, result.output(), touched)) {
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
    static boolean errorsOnlyInUntouchedFiles(Path worktree, String output, Set<Path> touched) {
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

    /** 给模型的编译失败反馈：报错原文 + 针对常见改不动的错误给出的确定性提示。 */
    static String compileFeedback(Path worktree, CommandRunner.Result result) {
        return "编译失败，错误输出：\n" + tail(result.output(), 4000) + compileHints(worktree, result.output());
    }

    private static final Pattern MISSING_TYPE = Pattern.compile("(?:符号|symbol)\\s*:\\s*(?:类|class|interface|enum|record)\\s+([A-Za-z_]\\w*)");

    /**
     * develop 阶段没有查代码的工具，模型只能凭记忆猜包名。真机上它连写三轮同一个不存在的 import，
     * 也直接调过被测类的 private 方法——这两类错误光靠报错原文它改不对，这里替它把事实查出来。
     */
    static String compileHints(Path worktree, String compilerOutput) {
        String output = compilerOutput == null ? "" : compilerOutput;
        StringBuilder hints = new StringBuilder();
        Set<String> missingTypes = new LinkedHashSet<>();
        Matcher m = MISSING_TYPE.matcher(output);
        while (m.find()) {
            missingTypes.add(m.group(1));
        }
        for (String type : missingTypes) {
            List<String> actual = findTypes(worktree, type, false);
            if (actual.isEmpty()) {
                List<String> near = findTypes(worktree, type, true);
                hints.append("\n【符号定位】工作区里没有名为 ").append(type).append(" 的类型，不要臆造；");
                hints.append(near.isEmpty() ? "改用被测类源码里实际使用的类型。"
                        : "名字相近的类型有：" + String.join("、", near) + "。");
            } else {
                hints.append("\n【符号定位】").append(type).append(" 实际位于 ").append(String.join("、", actual))
                        .append("（按这个全限定名 import）。");
            }
        }
        if (output.contains("private 访问控制") || output.contains("has private access")) {
            hints.append("\n【访问权限】报错的成员是 private：测试不要直接调用它，改测调用它的公开方法；确实需要时用反射。");
        }
        return hints.length() == 0 ? "" : "\n\n—— 编译提示 ——" + hints;
    }

    /** 源码里名为 {@code simpleName} 的类型（全限定名）；{@code prefix} 为真时找以它开头的其它类型。 */
    static List<String> findTypes(Path worktree, String simpleName, boolean prefix) {
        Path src = worktree.resolve("src");
        if (!Files.isDirectory(src)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        String exact = simpleName + ".java";
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String name = file.getFileName().toString();
                boolean match = prefix ? name.startsWith(simpleName) && !name.equals(exact) : name.equals(exact);
                if (match && found.size() < 6) {
                    String fqcn = qualifiedName(file, name.substring(0, name.length() - 5));
                    if (fqcn != null) {
                        found.add(fqcn);
                    }
                }
            }
        } catch (IOException |UncheckedIOException e) {
            return found;
        }
        return found;
    }

    private static String qualifiedName(Path file, String simpleName) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher m = PACKAGE_LINE.matcher(line);
                if (m.find()) {
                    return m.group(1) + "." + simpleName;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return simpleName;
    }

    private static final Pattern PACKAGE_LINE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    // ──────────────────────────── 工具 ────────────────────────────

    /** 任务给的路径就是它；只有文件名或路径不准时，在 worktree 的源码目录里按文件名找。 */
    static Path resolveFile(Path worktree, String file) {
        String name = file.replace('\\', '/').trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        Path direct = worktree.resolve(name).normalize();
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        String fileName = Path.of(name).getFileName() == null ? "" : Path.of(name).getFileName().toString();
        if (fileName.isBlank()) {
            return null;
        }
        try (Stream<Path> files = Files.walk(worktree, 12)) {
            return files.filter(p -> !p.toString().contains(File.separator + ".git" + File.separator)
                            && !p.toString().contains(File.separator + "target" + File.separator)
                            && !p.toString().contains(File.separator + "node_modules" + File.separator))
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().equals(fileName))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 回到开发时上一轮哪里没过：回归的失败用例与输出尾部、E2E 的失败步骤与用例。 */
    @SuppressWarnings("unchecked")
    private static String failureSummary(StageContext ctx) {
        StringBuilder summary = new StringBuilder();
        Object last = ctx.artifact("lastFailure").isEmpty() ? null : ctx.artifact("lastFailure");
        if (last instanceof Map<?, ?> failure && failure.get("message") != null) {
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
    private static List<Map<String, Object>> mapsOf(Object raw) {
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

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n…（截断）";
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : "…（截断）\n" + text.substring(text.length() - max);
    }

    private static String shortHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
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
