package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 阶段 6 · 用例回归：执行 Spec 阶段确定的 repro / unit 用例所在的测试类。
 *
 * <p>用例清单取自 Spec 产物（按名取，不依赖上一个阶段）。Spec 里没有可执行的回归用例时明确失败——
 * 没跑任何测试不能算通过。Java 项目按用例的落点找到测试类，跑这些类；找不到就跑全量。
 * 结果解析 surefire 报告；只要退出码非 0、有失败/错误、或一个用例都没执行到，就算未通过。
 */
@Slf4j
@Component
public class TestsStage implements StageHandler {

    private final CommandRunner runner = new CommandRunner();

    /**
     * 回归阶段的测试 JVM 是否使用独立的 user.home（与 E2E 服务启动共用一个开关）。
     * 被测项目的测试依赖真实 home 下的凭据或配置时可以关掉。
     */
    @Value("${workbench.fix.e2e.isolate-home:true}")
    private boolean isolateHome = true;

    @Override
    public String key() {
        return "tests";
    }

    @Override
    public StageOutcome execute(StageContext ctx) {
        Path worktree = Path.of(ctx.run().getWorktreePath());
        List<Map<String, Object>> cases = testCases(ctx.artifact("spec"), "repro", "unit");
        if (cases.isEmpty()) {
            ctx.publish("tests", Map.of("passed", false, "reason", "Spec 未定义 repro/unit 用例"));
            ctx.log("Spec 未定义 repro/unit 用例，回归无从执行");
            return StageOutcome.failed("Spec 未定义 repro/unit 用例，回归无从执行");
        }
        ctx.log("执行 Spec 确定的回归用例：" + cases.size() + " 条（repro/unit）");

        Map<String, Object> output = new LinkedHashMap<>();
        boolean allPassed;
        if (CommandRunner.isMavenProject(worktree)) {
            List<String> classes = cases.stream()
                    .map(c -> String.valueOf(c.getOrDefault("target", "")))
                    .map(TestsStage::classNameOf)
                    .filter(t -> t.contains("."))
                    .distinct()
                    .toList();
            List<Path> testFiles = new ArrayList<>();
            List<String> missingClasses = new ArrayList<>();
            for (String className : classes) {
                Path file = CommandRunner.resolveTestSource(worktree, className);
                if (file != null) {
                    testFiles.add(file);
                } else {
                    missingClasses.add(className);
                }
            }
            List<String> command = new ArrayList<>(CommandRunner.mavenCommand(worktree));
            command.add("-q");
            command.add("test");
            String module = null;
            String described;
            if (!classes.isEmpty() && testFiles.size() == classes.size()) {
                module = moduleOfAll(worktree, testFiles);
                if (module != null && !module.isBlank()) {
                    command.add("-pl");
                    command.add(module);
                }
                // 跑整个测试类：方法名以 Spec 为准未必和生成的一致，类级通过/失败足够判定
                command.add("-Dtest=" + String.join(",", simpleNames(classes)));
                described = "mvn test -Dtest=" + String.join(",", simpleNames(classes)) + (module == null ? "" : " -pl " + module);
            } else {
                described = "mvn test（用例的测试类未能全部定位：" + testFiles.size() + "/" + classes.size() + "，跑全量）";
            }
            // surefire fork 出来的测试 JVM 用独立的 user.home：有的项目（比如本工具自己的旧版本）的测试会启动完整的
            // 应用上下文，连的是 ~/.xxx 下开发者的真实库和定时任务；Maven 本身不受影响，仍用真实的 ~/.m2
            String isolatedHome = isolateHome ? isolatedHomeOrNull(worktree, ctx) : null;
            if (isolatedHome != null) {
                command.add("-DargLine=-Duser.home=" + isolatedHome);
                described += "（测试 JVM 使用独立的 user.home，不读写开发者的真实数据）";
            }
            ctx.log("运行：" + described);
            // 统计是读 surefire 报告文件得出的：先清掉上一轮留下的报告，否则旧结果会混进这一轮
            deleteSurefireReports(worktree);
            CommandRunner.Result result = runner.run(worktree, command, ctx::log, 20, TimeUnit.MINUTES, null, null);

            Map<String, Object> surefire = CommandRunner.surefireSummary(worktree);
            int tests = ((Number) surefire.getOrDefault("tests", 0)).intValue();
            int failures = ((Number) surefire.getOrDefault("failures", 0)).intValue();
            int errors = ((Number) surefire.getOrDefault("errors", 0)).intValue();
            // Spec 用例的测试类没被写出来，等于那些用例没执行：即使现有的都通过也不能算通过
            allPassed = result.ok() && tests > 0 && failures == 0 && errors == 0 && missingClasses.isEmpty();

            output.put("runner", "maven");
            output.put("command", described);
            output.put("testClasses", Map.of("ran", testFiles.size(), "missing", missingClasses));
            output.put("surefire", surefire);
            output.put("exitCode", result.exitCode());
            output.put("timedOut", result.timedOut());
            // 回到开发时模型要看的失败现场
            output.put("outputTail", tail(result.output(), 6000));
            if (tests == 0) {
                output.put("reason", "没有执行到任何测试用例：Spec 用例的测试类没有被跑到。可能的原因：测试类文件不存在或类名与用例落点不一致；"
                        + "pom.xml 没有声明 maven-surefire-plugin 3.x（Maven 3.8 自带的 2.12.4 不支持 JUnit 5）；项目没有测试依赖");
            } else if (!missingClasses.isEmpty()) {
                output.put("reason", "Spec 用例的测试类没有创建出来（src/test/java 下找不到）：" + missingClasses
                        + "；请按 Spec 的落点新建这些测试类并写入对应用例");
            }
            ctx.log("用例 " + tests + "，失败 " + failures + "，错误 " + errors + "，退出码 " + result.exitCode());
        } else if (CommandRunner.isPythonProject(worktree)) {
            List<String> targets = cases.stream()
                    .map(c -> String.valueOf(c.getOrDefault("target", "")))
                    .map(TestsStage::classNameOf)
                    .filter(t -> !t.isBlank())
                    .distinct()
                    .toList();
            List<String> command = new ArrayList<>(List.of("python", "-m", "pytest", "-q"));
            command.addAll(targets);
            CommandRunner.Result result = runner.run(worktree, command, ctx::log, 20, TimeUnit.MINUTES, null, null);
            allPassed = result.ok();
            output.put("runner", "pytest");
            output.put("exitCode", result.exitCode());
            output.put("timedOut", result.timedOut());
            output.put("outputTail", tail(result.output(), 6000));
        } else {
            ctx.publish("tests", Map.of("passed", false, "reason", "不支持的构建工具"));
            ctx.log("项目语言没有对应的回归执行器");
            return StageOutcome.failed("不支持的构建工具，无法执行回归（只有 Maven 与 pytest 有回归执行器）");
        }

        output.put("passed", allPassed);
        ctx.publish("tests", output);
        if (allPassed) {
            ctx.log("回归通过");
            return StageOutcome.done(output);
        }
        String reason = String.valueOf(output.getOrDefault("reason", "用例回归未通过"));
        return StageOutcome.retry(output, reason);
    }

    /** 用例落点 {@code com.example.FooTest#method} 取类名部分；只有名字没有包的写法不当成类。 */
    static String classNameOf(String target) {
        String text = target.trim();
        int method = text.indexOf('#');
        if (method >= 0) {
            text = text.substring(0, method);
        }
        return text.replace('/', '.').replace("\\", ".");
    }

    /** Spec 里指定类型的用例；字段缺失或类型不符的条目不算。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> testCases(Map<String, Object> spec, String... types) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(spec.get("testCases") instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String type = String.valueOf(map.get("type"));
            for (String wanted : types) {
                if (wanted.equalsIgnoreCase(type)) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    /** 测试 JVM 的独立 user.home（放在构建产物目录下，不进版本库）；建不出来就返回 null（不隔离，并留日志）。 */
    private static String isolatedHomeOrNull(Path worktree, StageContext ctx) {
        try {
            return E2eStage.isolatedHome(worktree).toAbsolutePath().normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            ctx.log("无法为测试 JVM 创建独立的 user.home，本次回归不隔离：" + e.getMessage());
            return null;
        }
    }

    /** 删掉 worktree 里所有 surefire-reports 目录下的文件（只清报告，不动编译产物）。 */
    static void deleteSurefireReports(Path root) {
        try (var dirs = java.nio.file.Files.walk(root, 6)) {
            for (Path dir : dirs.filter(p -> "surefire-reports".equals(String.valueOf(p.getFileName())))
                    .filter(java.nio.file.Files::isDirectory).toList()) {
                try (var files = java.nio.file.Files.list(dir)) {
                    for (Path file : files.toList()) {
                        java.nio.file.Files.deleteIfExists(file);
                    }
                }
            }
        } catch (Exception e) {
            // 清不掉时旧报告会被算进这一轮的统计，留一条日志备查
            log.warn("[WorkbenchFix] 清理 surefire 旧报告失败 {}: {}", root, e.getMessage());
        }
    }

    /** 全部测试文件在同一个模块时返回模块相对路径，否则返回 null（在根上跑）。 */
    private static String moduleOfAll(Path worktree, List<Path> testFiles) {
        String module = null;
        for (Path file : testFiles) {
            String current = CommandRunner.moduleOf(worktree, file);
            if (module == null) {
                module = current;
            } else if (!java.util.Objects.equals(module, current)) {
                return null;
            }
        }
        return module;
    }

    private static List<String> simpleNames(List<String> classes) {
        List<String> out = new ArrayList<>();
        for (String cls : classes) {
            int lastDot = cls.lastIndexOf('.');
            out.add(lastDot < 0 ? cls : cls.substring(lastDot + 1));
        }
        return out;
    }

    private static String tail(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : text.substring(text.length() - max));
    }
}
