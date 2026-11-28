package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 4 · 任务拆分：基于 Spec 拆出有序的开发任务。每个任务只涉及一个文件，
 * {@code file} 是相对项目根的完整路径（新文件也写完整路径），{@code verify} 引用 Spec 里的用例名。
 * 第一个任务固定是补上 Spec 里的复现 / 单元用例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TasksStage implements StageHandler {

    private static final List<String> BUILD_FILES = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "pyproject.toml", "requirements.txt");

    private final AgentLlmClient llm;

    @Override
    public String key() {
        return "tasks";
    }

    @Override
    public StageOutcome execute(StageContext ctx) {
        ctx.log("把 Spec 拆成可独立验证的任务");

        Map<String, Object> spec = ctx.artifact("spec");
        Map<String, Object> proposal = ctx.artifact("proposal");
        String chosen = ctx.run().getChosenOption();

        String system = """
                你是任务拆解工程师。把修复 Spec 拆成有序的开发任务：
                - 3 到 6 个任务，按执行顺序排列，每个任务只涉及一个文件；需要改多个文件就拆成多个任务；
                - 如果「项目现状的缺口」不为空，第一个任务必须按它补齐构建文件（file 写 pom.xml），这是后面一切的前提；
                - Spec 用例落点上的每个测试类都必须有一个任务把它写出来（file 写 src/test/java/<包路径>/<类名>.java），
                  回归阶段只跑这些类，缺了任务用例就没法执行；
                - 测试类任务放在它依赖的实现任务之后：每个任务做完都会连测试代码一起编译，
                  测试代码引用尚不存在的类或方法会编译失败；
                - file 是相对项目根的完整路径，例如 src/main/java/com/example/controller/UserController.java；
                  新文件同样要写出完整路径，不要只写文件名；
                - verify 写这一步怎么验证，引用 Spec 里的用例名；
                - anchor 写这一步涉及的类或方法（如 KnowledgeGraphBuilder.resolveStaticMethodCall）：真实项目的文件往往很长，
                  开发阶段按它截取相关代码片段，没有 anchor 就只能看到文件开头；
                - 只输出下面这个结构的 JSON 对象：顶层必须是对象，不要代码围栏，不要解释文字。
                {"tasks":[{"title":"这一步做什么","file":"相对项目根的完整路径","anchor":"涉及的类或方法","verify":"引用 Spec 里的用例名"}]}
                """;
        // 项目跑不起测试用例的缺口（没有测试依赖、surefire 太旧）：任务清单必须先把它补上
        String gaps = CommandRunner.testSetupGaps(Path.of(ctx.item().getProjectPath()));
        StringBuilder user = new StringBuilder();
        user.append("待办：").append(ctx.item().getTitle()).append('\n');
        if (ctx.item().getLocation() != null) {
            user.append("位置：").append(ctx.item().getLocation()).append('\n');
        }
        user.append("选定的方案：").append(chosen).append('\n');
        for (Map<String, Object> option : optionsOf(proposal)) {
            if (chosen != null && chosen.equals(String.valueOf(option.get("id")))) {
                user.append("方案说明：").append(option.get("summary")).append('\n');
            }
        }
        user.append("\nSpec：\n");
        user.append("goal: ").append(spec.get("goal")).append('\n');
        user.append("acceptanceCriteria: ").append(spec.get("acceptanceCriteria")).append('\n');
        user.append("testCases:\n");
        for (Map<String, Object> testCase : testCasesOf(spec)) {
            user.append("  - [").append(testCase.get("type")).append("] ").append(testCase.get("name"))
                    .append(" | 落点 ").append(testCase.get("target"))
                    .append(" | 通过标准 ").append(testCase.get("verify")).append('\n');
        }
        if (spec.get("httpMappings") != null) {
            user.append("HTTP 接口清单: ").append(spec.get("httpMappings")).append('\n');
        }
        user.append("\n项目现状的缺口").append(gaps.isEmpty() ? "：无" : "（第一个任务必须补齐）：\n" + gaps).append('\n');
        String projectNote = CommandRunner.springContextNote(Path.of(ctx.item().getProjectPath()));
        if (!projectNote.isEmpty()) {
            user.append("\n项目现状（测试类任务必须遵守）：\n").append(projectNote);
        }
        List<String> testClasses = testClassesOf(spec);
        if (!testClasses.isEmpty()) {
            user.append("\n回归阶段要跑的测试类（每一个都必须有对应的任务）：").append(String.join("、", testClasses)).append('\n');
        }

        Map<String, Object> payload = null;
        List<String> problems = List.of("尚未生成");
        for (int attempt = 0; attempt < 3; attempt++) {
            String prompt = user.toString();
            if (attempt > 0) {
                prompt = prompt + "\n\n上一次输出不符合要求：" + problems
                        + "。请严格按约定的 JSON 对象结构重新输出。";
            }
            try {
                payload = llm.completeJson(system, prompt, 6000);
            } catch (Exception e) {
                log.warn("[WorkbenchFix] 任务拆分失败 runId={} attempt={}: {}", ctx.run().getId(), attempt, e.getMessage());
                payload = null;
                continue;
            }
            problems = problemsOf(payload, !gaps.isEmpty(), testClasses);
            if (problems.isEmpty()) {
                break;
            }
            log.warn("[WorkbenchFix] 任务清单结构不符 runId={} attempt={}: {}", ctx.run().getId(), attempt, problems);
        }
        if (payload == null || !problems.isEmpty()) {
            // 模型连续不配合时不卡死整个修复流：按 Spec 用例与待办位置确定性生成任务清单。
            // 每条测试类落点一个任务 + 一个主文件修复任务，后续开发阶段仍按 Spec 全量实现与验证。
            ctx.log("任务拆分降级：模型输出多次不符合契约（" + problems
                    + "），按 Spec 用例与待办位置确定性生成任务清单");
            payload = fallbackTasks(spec, testClasses, ctx.item().getLocation());
        }

        // 把 Spec 的接口清单带下去：开发阶段据此理解接口约束
        Map<String, Object> output = new LinkedHashMap<>(payload);
        if (spec.get("httpMappings") != null) {
            output.put("httpMappings", spec.get("httpMappings"));
        }
        // OpenSpec 的 tasks.md 由任务清单确定性转换；DevelopStage 与 proposal/spec 增量一起落盘
        output.put("tasksMd", com.huawei.hisi.workbench.service.OpenSpecDocs.tasksMd(
                output.get("tasks") instanceof List<?> list
                        ? list.stream().filter(Map.class::isInstance).map(t -> (Map<String, Object>) t).toList()
                        : List.of()));
        ctx.publish("tasks", output);
        ctx.log("任务拆分完成：" + (output.get("tasks") instanceof List<?> list ? list.size() : 0) + " 个任务");
        return StageOutcome.done(output);
    }

    /**
     * 结构检查：任务非空，每个任务都有标题和完整文件路径；
     * {@code buildFileFirst} 为真（项目缺测试基础设施）时第一个任务必须是 pom.xml，且清单里要有测试类任务；
     * Spec 落点上的每个测试类都要有任务覆盖。
     */
    static List<String> problemsOf(Map<String, Object> payload, boolean buildFileFirst) {
        return problemsOf(payload, buildFileFirst, List.of());
    }

    @SuppressWarnings("unchecked")
    static List<String> problemsOf(Map<String, Object> payload, boolean buildFileFirst, List<String> testClasses) {
        List<String> problems = new ArrayList<>();
        if (payload == null) {
            problems.add("没有返回内容");
            return problems;
        }
        if (!(payload.get("tasks") instanceof List<?> tasks) || tasks.isEmpty()) {
            problems.add("缺少非空的 tasks 数组");
            return problems;
        }
        boolean hasTestClass = false;
        for (int i = 0; i < tasks.size(); i++) {
            if (!(tasks.get(i) instanceof Map<?, ?> task)) {
                problems.add("tasks[" + i + "] 不是对象");
                continue;
            }
            if (task.get("title") == null || String.valueOf(task.get("title")).isBlank()) {
                problems.add("tasks[" + i + "] 缺少 title");
            }
            String file = task.get("file") == null ? "" : String.valueOf(task.get("file")).trim().replace('\\', '/');
            if (file.isEmpty()) {
                problems.add("tasks[" + i + "] 缺少 file");
            } else if (!file.contains("/") && !BUILD_FILES.contains(file)) {
                // 根目录的构建文件本身就没有目录部分，其余文件必须写全路径
                problems.add("tasks[" + i + "] 的 file 必须是相对项目根的完整路径，而不是只有文件名 " + file);
            }
            hasTestClass |= file.contains("src/test/");
            if (i == 0 && buildFileFirst && !file.endsWith("pom.xml")) {
                problems.add("tasks[0] 必须是补齐构建文件的任务（file 为 pom.xml）：项目现状的缺口不补，后面的用例无法执行");
            }
        }
        if (buildFileFirst && !hasTestClass) {
            problems.add("缺少新建测试类（src/test/ 下）的任务");
        }
        List<String> taskFiles = tasks.stream().filter(Map.class::isInstance).map(task -> {
            Object file = ((Map<?, ?>) task).get("file");
            return file == null ? "" : String.valueOf(file).trim().replace('\\', '/');
        }).toList();
        for (String testClass : testClasses) {
            String expected = "src/test/java/" + testClass.replace('.', '/') + ".java";
            if (taskFiles.stream().noneMatch(file -> file.endsWith(expected))) {
                problems.add("缺少测试类 " + testClass + " 的任务（file 写 " + expected + "）");
            }
        }
        return problems;
    }

    /** Spec 的 repro / unit 用例落点上的测试类（全限定名，去重）：回归阶段只跑这些类，所以每个都要有任务把它写出来。 */
    static List<String> testClassesOf(Map<String, Object> spec) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Map<String, Object> testCase : mapsOf(spec.get("testCases"))) {
            String type = String.valueOf(testCase.get("type"));
            if (type.equalsIgnoreCase("repro") || type.equalsIgnoreCase("unit")) {
                String className = TestsStage.classNameOf(String.valueOf(testCase.getOrDefault("target", "")));
                if (className.contains(".")) {
                    out.add(className);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 确定性降级：模型输出多次不符合契约时不再卡死修复流，按 Spec 的测试用例落点与待办位置
     * 机械生成任务清单——每个 repro/unit 用例落点一个测试类任务，再加一个主文件修复任务。
     * 开发阶段仍按 Spec 全量实现与验证，任务清单只是执行顺序的骨架。
     */
    private static Map<String, Object> fallbackTasks(Map<String, Object> spec, List<String> testClasses, String location) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> specTestCases = testCasesOf(spec);
        String verifyName = specTestCases.stream()
                .map(tc -> String.valueOf(tc.get("type")))
                .filter(type -> type.equalsIgnoreCase("repro") || type.equalsIgnoreCase("unit"))
                .findFirst().isEmpty() ? null : specTestCases.stream()
                .filter(tc -> {
                    String type = String.valueOf(tc.get("type"));
                    return type.equalsIgnoreCase("repro") || type.equalsIgnoreCase("unit");
                })
                .map(tc -> String.valueOf(tc.get("name")))
                .findFirst().orElse(null);
        for (String testClass : testClasses) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("title", "编写测试类 " + testClass.substring(testClass.lastIndexOf('.') + 1));
            task.put("file", "src/test/java/" + testClass.replace('.', '/') + ".java");
            task.put("anchor", testClass);
            task.put("verify", verifyName == null ? "对应 Spec 用例" : verifyName);
            tasks.add(task);
        }
        if (location != null && !location.isBlank() && location.contains(".")) {
            String className = TestsStage.classNameOf(location);
            if (className.contains(".")) {
                Map<String, Object> task = new LinkedHashMap<>();
                task.put("title", "按 Spec 修复 " + location);
                task.put("file", "src/main/java/" + className.replace('.', '/') + ".java");
                task.put("anchor", location);
                task.put("verify", "编译通过且 Spec 用例全部通过");
                tasks.add(task);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tasks", tasks);
        return payload;
    }

    private static List<Map<String, Object>> optionsOf(Map<String, Object> proposal) {
        return mapsOf(proposal.get("options"));
    }

    private static List<Map<String, Object>> testCasesOf(Map<String, Object> spec) {
        return mapsOf(spec.get("testCases"));
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
}
