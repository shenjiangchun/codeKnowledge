package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.apm.model.TargetProcessInfo;
import com.huawei.hisi.apm.service.TargetProcessManager;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import com.huawei.hisi.workbench.service.exec.HttpMappingScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 阶段 7 · 全链路 E2E：构建 → 本地启动 → HTTP 健康检查 → 逐条执行 Spec 里的 api 用例 →
 * 项目带前端套件时跑它的 Playwright。任一步不过都回到开发（retry）。
 *
 * <p>用例取自 Spec 产物。Spec 里没有任何 api / e2e 用例时明确失败——只启动不验证不能算通过。
 * 每条 api 用例的路径先和代码里的接口映射核对：路径写错导致的 404 不是通过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class E2eStage implements StageHandler {

    private static final int HTTP_READY_WAIT_SECONDS = 60;
    private static final Pattern STATUS_IN_TEXT = Pattern.compile("\\b([2-5]\\d{2})\\b");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{[^/}]+}");

    private final TargetProcessManager processManager;
    private final CommandRunner runner = new CommandRunner();

    /**
     * 被测服务用独立的 user.home。很多应用把数据库、日志写在 ~/.xxx 下——被测的甚至可能就是本工具自己
     * 的另一个版本，共用一个库会直接读写开发者的真实数据。项目启动依赖真实 home 下的凭据时可以关掉。
     */
    @Value("${workbench.fix.e2e.isolate-home:true}")
    private boolean isolateHome = true;

    /**
     * 全链路 E2E 是否启动被测服务。默认启动（构建 → 启动 → 健康检查 → 接口用例）；
     * 被分析的项目不适合在本机拉起来时关掉，这一阶段退为「构建 + 前端 E2E」，并在产物里写明没有启动服务。
     */
    @Value("${workbench.fix.e2e.start-service:true}")
    boolean serviceE2e = true;

    @Override
    public String key() {
        return "e2e";
    }

    @Override
    public StageOutcome execute(StageContext ctx) {
        Path worktree = Path.of(ctx.run().getWorktreePath());
        Map<String, Object> spec = ctx.artifact("spec");
        List<Map<String, Object>> apiCases = TestsStage.testCases(spec, "api");
        List<Map<String, Object>> e2eCases = TestsStage.testCases(spec, "e2e");
        // 项目里没有 @SpringBootApplication 启动类时，「启动服务做接口验证」物理上无法执行：
        // 与配置里关掉 start-service 等同，这一阶段只验证构建。不是失败——Spec 提示词同样告知过模型
        // 「没有启动类、不要依赖容器」，Spec 不写 api/e2e 用例是符合约定的。
        boolean projectStartable = CommandRunner.hasSpringBootApplication(worktree);
        if (!projectStartable) {
            serviceE2e = false;
            ctx.log("项目没有 @SpringBootApplication 启动类，E2E 阶段只验证构建，不启动服务");
        }
        // 关闭了服务启动时 Spec 本来就不要求 api/e2e 用例（见 SpecStage），这一阶段只验证构建
        if (serviceE2e && apiCases.isEmpty() && e2eCases.isEmpty()) {
            ctx.publish("e2e", Map.of("passed", false, "steps", List.of("Spec 未定义 api/e2e 用例")));
            ctx.log("Spec 未定义 api/e2e 用例，全链路 E2E 无从执行");
            return StageOutcome.failed("Spec 未定义 api/e2e 用例，全链路 E2E 无从执行");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        List<String> steps = new ArrayList<>();
        steps.add("用例：api " + apiCases.size() + " 条，e2e " + e2eCases.size() + " 条");

        // 1. 构建
        CommandRunner.Result build = build(worktree, ctx);
        steps.add("构建：" + (build.ok() ? "通过" : "失败"));
        if (!build.ok()) {
            output.put("steps", steps);
            output.put("passed", false);
            output.put("buildTail", tail(build.output(), 4000));
            ctx.publish("e2e", output);
            return StageOutcome.retry(output, "构建失败");
        }
        ctx.log("构建通过");

        if (!serviceE2e) {
            // 不启动服务做接口验证（配置关闭，或项目没有 @SpringBootApplication 启动类）：这一阶段只验证构建与前端 E2E，
            // 并如实写明没有启动服务
            steps.add(projectStartable
                    ? "启动服务：已在配置中关闭（workbench.fix.e2e.start-service=false），未执行接口用例"
                    : "启动服务：项目没有 @SpringBootApplication 启动类，无法启动，未执行接口用例");
            ctx.log(steps.get(steps.size() - 1));
            Map<String, Object> frontend = e2eCases.isEmpty() ? null : runFrontendE2e(worktree, e2eCases, ctx);
            boolean frontendOk = frontend == null || !Boolean.FALSE.equals(frontend.get("passed"));
            if (frontend != null) {
                steps.add("前端 E2E：" + frontend.get("summary"));
                output.put("frontendE2e", frontend);
            }
            output.put("steps", steps);
            output.put("serviceStarted", false);
            output.put("passed", frontendOk);
            if (!frontendOk) {
                output.put("reason", "前端 E2E 存在失败用例");
            }
            ctx.publish("e2e", output);
            return frontendOk ? StageOutcome.done(output) : StageOutcome.retry(output, "前端 E2E 存在失败用例");
        }
        output.put("serviceStarted", true);

        // 2–5. 启动 → 健康检查 → api 用例 → 前端 E2E，全程保持服务运行，最后停掉
        String sessionId = "wb-e2e-" + ctx.run().getId();
        AtomicReference<TargetProcessInfo> infoRef = new AtomicReference<>();
        AtomicReference<String> statusRef = new AtomicReference<>("LAUNCHING");
        boolean allPassed = false;
        String failure = "服务启动失败";
        try {
            String startFailure = startService(worktree, sessionId, infoRef, statusRef, ctx);
            if (startFailure != null) {
                steps.add("启动：失败");
                return retryAfter(ctx, output, steps, startFailure);
            }
            int port = infoRef.get().getTargetPort();
            steps.add("启动：端口 " + port);
            ctx.log("服务已启动，端口 " + port);

            String health = healthCheck(port);
            if (health == null) {
                steps.add("健康检查：" + HTTP_READY_WAIT_SECONDS + " 秒内服务没有响应 HTTP");
                return retryAfter(ctx, output, steps, "服务启动后 " + HTTP_READY_WAIT_SECONDS
                        + " 秒内没有响应 HTTP 请求（端口在监听，但服务没有就绪）\n" + serviceTail(sessionId));
            }
            steps.add("健康检查：" + health);
            ctx.log(steps.get(steps.size() - 1));

            List<Map<String, Object>> apiResults = runApiCases(apiCases, port, worktree, ctx);
            long apiPassed = apiResults.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
            boolean apiOk = apiPassed == apiResults.size();
            steps.add("API 用例：" + apiResults.size() + " 条，通过 " + apiPassed);
            output.put("apiResults", apiResults);

            Map<String, Object> frontend = runFrontendE2e(worktree, e2eCases, ctx);
            boolean frontendOk;
            if (frontend == null) {
                // 没跑就是没跑：Spec 定义了前端用例而项目没有 Playwright 套件时，不能算通过
                frontendOk = e2eCases.isEmpty();
                if (!frontendOk) {
                    steps.add("前端 E2E：项目未配置 @playwright/test，Spec 的 e2e 用例无法执行");
                    ctx.log(steps.get(steps.size() - 1));
                }
            } else {
                frontendOk = !Boolean.FALSE.equals(frontend.get("passed"));
                steps.add("前端 E2E：" + frontend.get("summary"));
                output.put("frontendE2e", frontend);
            }

            allPassed = apiOk && frontendOk;
            failure = describeFailures(apiResults, frontendOk);
        } finally {
            processManager.shutdown(sessionId);
            steps.add("停止服务");
        }

        output.put("steps", steps);
        output.put("passed", allPassed);
        if (!allPassed) {
            output.put("reason", failure);
        }
        ctx.publish("e2e", output);
        if (allPassed) {
            ctx.log("全链路 E2E 通过");
            return StageOutcome.done(output);
        }
        ctx.log("E2E 未通过：" + failure);
        return StageOutcome.retry(output, failure);
    }

    private StageHandler.StageOutcome retryAfter(StageContext ctx, Map<String, Object> output,
                                                 List<String> steps, String reason) {
        output.put("steps", steps);
        output.put("passed", false);
        output.put("reason", reason);
        ctx.publish("e2e", output);
        ctx.log("E2E 未通过：" + reason);
        return StageOutcome.retry(output, reason);
    }

    // ──────────────────────────── 各步实现 ────────────────────────────

    private CommandRunner.Result build(Path worktree, StageContext ctx) {
        List<String> command = new ArrayList<>();
        if (CommandRunner.isMavenProject(worktree)) {
            command.addAll(CommandRunner.mavenCommand(worktree));
            command.add("-q");
            command.add("package");
            command.add("-DskipTests");
        } else if (CommandRunner.isNodeProject(worktree)) {
            command.add(CommandRunner.npm());
            command.add("run");
            command.add("build");
        } else {
            // Python 等解释型项目没有构建步骤
            return new CommandRunner.Result(0, "skip: interpreted project", false);
        }
        return runner.run(worktree, command, ctx::log, 30, TimeUnit.MINUTES, null, null);
    }

    /** 启动方案：命令、工作目录、给人看的说明。 */
    record Launch(Path workDir, List<String> command, String how) {}

    private static final String BOOT_APP = "Lorg/springframework/boot/autoconfigure/SpringBootApplication;";
    private static final String WEB_CONTROLLER = "Lorg/springframework/web/bind/annotation/RestController;";
    private static final String WEB_CONTROLLER_ANNOTATED = "Lorg/springframework/web/bind/annotation/Controller;";
    private static final String HARNESS_MAIN = "wb.WbE2eApplication";

    /** 准备并启动服务。返回 null 表示已就绪；否则是失败原因，带上服务输出尾部。 */
    private String startService(Path worktree, String sessionId,
                                AtomicReference<TargetProcessInfo> infoRef,
                                AtomicReference<String> statusRef, StageContext ctx) {
        Launch launch;
        try {
            // 被测服务用独立的 user.home：很多应用把数据库、日志写在 ~/.xxx 下，E2E 不能读写开发者的真实数据
            Path home = isolateHome ? isolatedHome(worktree) : null;
            launch = launchPlanOf(worktree, home, ctx::log, runner);
        } catch (IOException e) {
            ctx.log("准备启动失败：" + e.getMessage());
            return "准备启动失败：" + e.getMessage();
        }
        if (launch == null) {
            ctx.log("项目没有可启动的服务：没有可执行 jar，编译产物里也没有启动入口或 Web 控制器");
            return "项目没有可启动的服务：没有可执行 jar，编译产物里也没有启动入口或 Web 控制器";
        }
        ctx.log("启动方式：" + launch.how());
        ctx.log("启动命令：" + String.join(" ", launch.command()));
        try {
            processManager.launchPlain(sessionId, launch.workDir().toString(), "wb-e2e", 0, launch.command(),
                    info -> {
                        infoRef.set(info);
                        statusRef.set(info.getStatus());
                    }, ctx::log);
        } catch (Exception e) {
            ctx.log("启动失败：" + e.getMessage());
            return "启动命令执行失败：" + e.getMessage();
        }
        // 端口就绪由 TargetProcessManager 轮询；这里等它的 READY 回调
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if ("READY".equals(statusRef.get())) {
                return null;
            }
            if ("ERROR".equals(statusRef.get()) || "STOPPED".equals(statusRef.get())) {
                ctx.log("启动状态异常：" + statusRef.get());
                return "服务进程在端口就绪前退出（状态 " + statusRef.get() + "）\n" + serviceTail(sessionId);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "等待服务启动时被中断";
            }
        }
        ctx.log("等待服务就绪超时");
        return "等待服务就绪超时（120 秒）\n" + serviceTail(sessionId);
    }

    private String serviceTail(String sessionId) {
        return "服务输出（尾部）：\n" + tail(String.join("\n", processManager.getOutputLines(sessionId, 60)), 3000);
    }

    /**
     * 怎么把待验证的服务拉起来，按项目现状逐级降级：
     * ① 构建出的 Spring Boot 可执行 jar；
     * ② 声明了 spring-boot 插件的项目用 spring-boot:run；
     * ③ Maven 项目的编译产物 + 依赖 classpath——项目自己没有启动入口时，由 E2E 夹具临时提供一个入口类
     *    （放在构建产物目录里，只负责拉起项目的 Web 层，不进入项目代码）；
     * ④ Node 项目的 npm start。都不适用就返回 null。
     */
    static Launch launchPlanOf(Path worktree) throws IOException {
        return launchPlanOf(worktree, null, line -> { }, new CommandRunner());
    }

    static Launch launchPlanOf(Path worktree, java.util.function.Consumer<String> log, CommandRunner runner)
            throws IOException {
        return launchPlanOf(worktree, null, log, runner);
    }

    /**
     * @param isolatedHome 被测服务的 user.home（为 null 表示不隔离）；放在构建产物目录下，不进入版本库
     */
    static Launch launchPlanOf(Path worktree, Path isolatedHome, java.util.function.Consumer<String> log,
                               CommandRunner runner) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path jar = findBootJar(worktree);
        if (jar != null) {
            // 工作目录是 worktree 根，jar 路径要写成相对它的路径
            String jarArg = worktree.toAbsolutePath().normalize().relativize(jar.toAbsolutePath()).toString().replace('\\', '/');
            String homeOption = isolatedHome == null ? "" : " \"-Duser.home=" + slash(isolatedHome) + "\"";
            if (windows) {
                // 参数逐个交给 ProcessBuilder（带空格的值由它加引号），不自己拼命令行
                List<String> command = new ArrayList<>(List.of("cmd", "/c", "java"));
                if (isolatedHome != null) {
                    command.add("-Duser.home=" + slash(isolatedHome));
                }
                command.add("-jar");
                command.add(jarArg);
                return new Launch(worktree, command, "Spring Boot 可执行 jar " + jarArg);
            }
            return new Launch(worktree, List.of("sh", "-c", "java" + homeOption + " -jar " + jarArg),
                    "Spring Boot 可执行 jar " + jarArg);
        }
        if (CommandRunner.isMavenProject(worktree)) {
            Path classes = classesDirOf(worktree);
            if (classes != null) {
                Path module = classes.getParent().getParent();
                String main = firstClass(classes, BOOT_APP);
                String pom = readText(module.resolve("pom.xml"));
                if (main != null && pom != null && pom.contains("spring-boot-maven-plugin")) {
                    List<String> maven = new ArrayList<>(CommandRunner.mavenCommand(module));
                    maven.add("spring-boot:run");
                    // Maven 自己要用真实的 ~/.m2，隔离只能加在它 fork 出来的应用 JVM 上；路径带空格时参数穿不过 .cmd，放弃隔离
                    if (isolatedHome != null && isolatedHome.toString().indexOf(' ') < 0) {
                        maven.add("-Dspring-boot.run.jvmArguments=-Duser.home=" + slash(isolatedHome));
                    } else if (isolatedHome != null) {
                        log.accept("worktree 路径带空格，spring-boot:run 方式无法隔离 user.home");
                    }
                    return new Launch(module, windows ? withCmd(maven) : maven, "mvn spring-boot:run，主类 " + main);
                }
                Launch launch = classpathLaunch(module, classes, main, isolatedHome, log, runner);
                if (launch != null) {
                    return launch;
                }
            }
        }
        if (CommandRunner.isNodeProject(worktree)) {
            List<String> start = new ArrayList<>(List.of(CommandRunner.npm(), "start"));
            return new Launch(worktree, windows ? withCmd(start) : start, "npm start");
        }
        return null;
    }

    static Path isolatedHome(Path worktree) throws IOException {
        Path home = worktree.toAbsolutePath().resolve("target/wb-e2e/home");
        Files.createDirectories(home);
        return home;
    }

    private static String slash(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static List<String> withCmd(List<String> command) {
        List<String> out = new ArrayList<>(List.of("cmd", "/c"));
        out.addAll(command);
        return out;
    }

    /** 编译产物目录：优先带启动入口的模块，其次有 Web 控制器的，再次任意一个存在的。 */
    static Path classesDirOf(Path worktree) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(worktree.resolve("target/classes"));
        try (Stream<Path> modules = Files.list(worktree)) {
            modules.filter(Files::isDirectory).map(module -> module.resolve("target/classes")).forEach(candidates::add);
        } catch (IOException ignored) {
            // 列不出子模块：只看根目录
        }
        List<Path> existing = candidates.stream().filter(Files::isDirectory).toList();
        for (String marker : List.of(BOOT_APP, WEB_CONTROLLER)) {
            for (Path classes : existing) {
                if (!annotatedClasses(classes, marker).isEmpty()) {
                    return classes;
                }
            }
        }
        return existing.isEmpty() ? null : existing.get(0);
    }

    /**
     * 用编译产物 + 依赖 classpath 启动。classpath 很长，走 java 的 @argfile；
     * 项目没有主类时先编译一个夹具入口类。没有主类也没有 Web 控制器的项目起不了服务，返回 null。
     */
    private static Launch classpathLaunch(Path module, Path classes, String main, Path isolatedHome,
                                          java.util.function.Consumer<String> log, CommandRunner runner) throws IOException {
        List<String> controllers = annotatedClasses(classes, WEB_CONTROLLER);
        controllers.addAll(annotatedClasses(classes, WEB_CONTROLLER_ANNOTATED));
        if (main == null && controllers.isEmpty()) {
            return null;
        }
        Path harness = classes.getParent().resolve("wb-e2e");
        Files.createDirectories(harness);
        Path cpFile = harness.resolve("classpath.txt");
        List<String> resolve = new ArrayList<>(CommandRunner.mavenCommand(module));
        resolve.add("-q");
        resolve.add("dependency:build-classpath");
        resolve.add("-Dmdep.outputFile=" + cpFile.toAbsolutePath());
        CommandRunner.Result resolved = runner.run(module, resolve, log, 10, TimeUnit.MINUTES, null, null);
        String dependencies = readText(cpFile);
        if (dependencies == null && !resolved.ok()) {
            throw new IOException("解析依赖 classpath 失败：" + tail(resolved.output(), 600));
        }
        String classpath = dependencies == null ? "" : dependencies.trim();

        List<String> entries = new ArrayList<>();
        entries.add(classes.toAbsolutePath().toString());
        String entry = main;
        String how;
        if (main != null) {
            how = "编译产物 + 依赖 classpath，主类 " + main;
        } else {
            // 项目没有启动入口：夹具生成一个只负责拉起 Web 层的入口类，放在构建产物目录里
            Path source = harness.resolve("src/wb/WbE2eApplication.java");
            Path out = harness.resolve("classes");
            Path javacArgs = harness.resolve("javac.args");
            Files.createDirectories(source.getParent());
            Files.createDirectories(out);
            String basePackage = scanPackage(classNames(classes), controllers);
            Files.writeString(source, harnessSource(basePackage), StandardCharsets.UTF_8);
            writeArgFile(javacArgs, List.of("-encoding", "UTF-8",
                    "-cp", quote(entries.get(0) + File.pathSeparator + classpath),
                    "-d", quote(out.toString()), quote(source.toString())));
            CommandRunner.Result compiled = runner.run(module, List.of(jdkTool("javac"), "@" + javacArgs), log,
                    5, TimeUnit.MINUTES, null, null);
            if (!compiled.ok()) {
                throw new IOException("E2E 启动夹具编译失败：" + tail(compiled.output(), 600));
            }
            entries.add(0, out.toAbsolutePath().toString());
            entry = HARNESS_MAIN;
            how = "编译产物 + 依赖 classpath；项目没有启动入口，由 E2E 夹具提供入口（扫描包 " + basePackage + "）";
        }
        Path javaArgs = harness.resolve("java.args");
        writeArgFile(javaArgs, List.of("-cp", quote(String.join(File.pathSeparator, entries)
                + (classpath.isEmpty() ? "" : File.pathSeparator + classpath))));
        // 直接起 java（不经 shell），-D 参数原样传入
        List<String> command = new ArrayList<>(List.of(jdkTool("java"), "@" + javaArgs));
        if (isolatedHome != null) {
            command.add("-Duser.home=" + slash(isolatedHome));
        }
        command.add(entry);
        return new Launch(module, command, how);
    }

    /** 夹具入口：只做自动配置 + 组件扫描，把项目编译产物里的 Web 层原样拉起来。 */
    static String harnessSource(String basePackage) {
        return """
                package wb;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;

                /** 工作台全链路 E2E 的临时启动入口：项目自己没有可启动的入口时用它拉起 Web 层，不进入项目代码。 */
                public class WbE2eApplication {

                    @Configuration
                    @EnableAutoConfiguration
                    @ComponentScan(basePackages = "%s")
                    public static class Boot {
                    }

                    public static void main(String[] args) {
                        SpringApplication.run(Boot.class, args);
                    }
                }
                """.formatted(basePackage);
    }

    /**
     * 夹具要扫描的包：全部类的公共包（通常就是项目的根包，控制器、服务都在它下面）。
     * 公共包退到了根（类分布在互不相干的顶层包里）时，改扫各控制器所在包的上一级。
     */
    static String scanPackage(List<String> classNames, List<String> controllers) {
        String common = classNames.isEmpty() ? "" : classNames.get(0);
        for (String name : classNames) {
            while (!name.startsWith(common + ".") && !common.isEmpty()) {
                common = common.contains(".") ? common.substring(0, common.lastIndexOf('.')) : "";
            }
        }
        if (!common.isEmpty() || controllers.isEmpty()) {
            return common;
        }
        List<String> packages = new ArrayList<>();
        for (String controller : controllers) {
            String parent = controller.contains(".") ? controller.substring(0, controller.lastIndexOf('.')) : "";
            if (!parent.isEmpty() && !packages.contains(parent)) {
                packages.add(parent);
            }
        }
        return String.join(",", packages);
    }

    /** 编译产物里带了指定注解的顶层类（内部类不算）的全限定名。 */
    static List<String> annotatedClasses(Path classesDir, String descriptor) {
        List<String> out = new ArrayList<>();
        for (String name : classNames(classesDir)) {
            if (describes(classesDir.resolve(name.replace('.', '/') + ".class"), descriptor)) {
                out.add(name);
            }
        }
        return out;
    }

    private static String firstClass(Path classesDir, String descriptor) {
        List<String> found = annotatedClasses(classesDir, descriptor);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<String> classNames(Path classesDir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(classesDir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(classesDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .map(p -> classesDir.relativize(p).toString().replace('\\', '/'))
                    .map(p -> p.substring(0, p.length() - ".class".length()).replace('/', '.'))
                    .sorted().forEach(out::add);
        } catch (IOException ignored) {
            // 读不了的产物目录当作没有类
        }
        return out;
    }

    /** 类文件的常量池里是否出现某注解的描述符。 */
    private static boolean describes(Path classFile, String descriptor) {
        try {
            return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1).contains(descriptor);
        } catch (IOException e) {
            return false;
        }
    }

    /** java / javac 的 @argfile：每行一个参数，带空格的值加引号（引号里反斜杠是转义符，路径统一成正斜杠）。 */
    private static void writeArgFile(Path file, List<String> arguments) throws IOException {
        Files.writeString(file, String.join("\n", arguments) + "\n", StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\"";
    }

    /** 用运行后端的这套 JDK 里的工具，避免依赖 PATH。 */
    private static String jdkTool(String name) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path tool = Path.of(System.getProperty("java.home"), "bin", windows ? name + ".exe" : name);
        return Files.isRegularFile(tool) ? tool.toString() : name;
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** 根目录或一层子模块的 target 下，带 BOOT-INF 的那个 jar（即 Spring Boot 可执行包）。 */
    static Path findBootJar(Path worktree) {
        List<Path> targets = new ArrayList<>();
        targets.add(worktree.resolve("target"));
        try (Stream<Path> modules = Files.list(worktree)) {
            modules.filter(Files::isDirectory).map(module -> module.resolve("target")).forEach(targets::add);
        } catch (IOException ignored) {
            // 列不出子模块：只看根目录
        }
        for (Path target : targets) {
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (Stream<Path> jars = Files.list(target)) {
                Path boot = jars.filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().contains("-sources")
                                && !p.getFileName().toString().contains("-javadoc")
                                && !p.getFileName().toString().contains("-tests"))
                        .filter(E2eStage::isBootJar)
                        .findFirst().orElse(null);
                if (boot != null) {
                    return boot;
                }
            } catch (IOException ignored) {
                // 该目录读不了：看下一个
            }
        }
        return null;
    }

    private static boolean isBootJar(Path jar) {
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry("BOOT-INF/") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等到服务真正响应 HTTP。返回对 /actuator/health 的探测结果（状态码只记录不判定，
     * 不是每个项目都暴露 actuator）；超时没有等到任何 HTTP 响应时返回 null。
     */
    private String healthCheck(int port) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        long deadline = System.currentTimeMillis() + HTTP_READY_WAIT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return "/actuator/health → " + response.statusCode() + (response.statusCode() == 200 ? "（UP）" : "");
            } catch (ConnectException | HttpTimeoutException notUpYet) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException | IllegalArgumentException e) {
                // 服务有响应但探测请求本身出了问题：HTTP 已经通了
                return "服务已响应 HTTP；/actuator/health 探测异常：" + e.getMessage();
            }
        }
        return null;
    }

    /**
     * 逐条执行 api 用例。落点形如 {@code GET /users/{id}}：路径变量填成 1；
     * 通过标准里写明了状态码就精确比对，否则要求 2xx。路径先和代码里的接口映射核对。
     */
    private List<Map<String, Object>> runApiCases(List<Map<String, Object>> apiCases, int port, Path worktree, StageContext ctx) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> mappings = HttpMappingScanner.scan(worktree);
        if (mappings.isEmpty()) {
            ctx.log("代码里没有扫到接口映射，跳过路径核对");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        for (Map<String, Object> testCase : apiCases) {
            String name = String.valueOf(testCase.getOrDefault("name", "未命名用例"));
            String verify = String.valueOf(testCase.getOrDefault("verify", ""));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            results.add(result);

            String[] request = SpecStage.parseRequest(String.valueOf(testCase.getOrDefault("target", "")));
            if (request == null) {
                result.put("passed", false);
                result.put("error", "用例落点格式不对（应形如 GET /users/{id}）：" + testCase.get("target"));
                ctx.log("API 用例 " + name + " 落点格式不对");
                continue;
            }
            String concretePath = PATH_PARAM.matcher(request[1]).replaceAll("1");
            String query = queryOf(String.valueOf(testCase.getOrDefault("target", "")));
            String url = "http://localhost:" + port + concretePath + query;
            result.put("request", request[0] + " " + concretePath + query);
            if (!mappings.isEmpty() && !HttpMappingScanner.matches(mappings, request[0], request[1])) {
                result.put("passed", false);
                result.put("error", "路径不在代码的接口映射中");
                ctx.log("API 用例 " + name + " 的路径 " + request[0] + " " + request[1] + " 不在代码的接口映射中");
                continue;
            }
            Integer expected = expectedStatus(verify);
            result.put("expected", expected == null ? "2xx" : String.valueOf(expected));
            try {
                String requestBody = testCase.get("body") == null ? "" : String.valueOf(testCase.get("body")).trim();
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
                if (!requestBody.isEmpty()) {
                    builder.header("Content-Type", "application/json");
                }
                HttpRequest httpRequest = builder.method(request[0], requestBody.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(requestBody)).build();
                HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                boolean passed = expected == null ? status >= 200 && status < 300 : status == expected;
                result.put("status", status);
                result.put("passed", passed);
                String body = response.body() == null ? "" : response.body();
                result.put("body", body.substring(0, Math.min(200, body.length())));
                ctx.log("API 用例 " + name + "：" + request[0] + " " + concretePath + " → " + status + (passed ? " 通过" : " 未通过"));
            } catch (Exception e) {
                result.put("passed", false);
                result.put("error", e.getMessage());
                ctx.log("API 用例 " + name + " 执行失败：" + e.getMessage());
            }
        }
        return results;
    }

    /** 通过标准里写明的状态码（200–599）；没写就返回 null，按 2xx 判定。 */
    static Integer expectedStatus(String verify) {
        Matcher status = STATUS_IN_TEXT.matcher(verify == null ? "" : verify);
        return status.find() ? Integer.valueOf(status.group(1)) : null;
    }

    /** 用例落点里的查询串（{@code ?name=tom}），路径变量同样填成 1；没有就返回空串。 */
    static String queryOf(String target) {
        int query = target.indexOf('?');
        return query < 0 ? "" : PATH_PARAM.matcher(target.substring(query)).replaceAll("1");
    }

    /** 项目带 @playwright/test 时跑它的套件；没有套件时返回 null（跳过）。 */
    private Map<String, Object> runFrontendE2e(Path worktree, List<Map<String, Object>> e2eCases, StageContext ctx) {
        if (!CommandRunner.hasPlaywright(worktree)) {
            if (e2eCases.isEmpty()) {
                ctx.log("项目未配置 Playwright，前端 E2E 跳过");
            }
            return null;
        }
        ctx.log("运行前端 Playwright 套件");
        List<String> command = new ArrayList<>(List.of(CommandRunner.npx(), "playwright", "test"));
        for (Map<String, Object> testCase : e2eCases) {
            Object target = testCase.get("target");
            if (target instanceof String s && !s.isBlank()) {
                command.add(s);
            }
        }
        CommandRunner.Result result = runner.run(worktree, command, ctx::log, 20, TimeUnit.MINUTES, null, null);
        return Map.of("passed", result.ok(), "summary", result.ok() ? "通过" : "存在失败用例",
                "exitCode", result.exitCode(), "outputTail", tail(result.output(), 4000));
    }

    private static String describeFailures(List<Map<String, Object>> apiResults, boolean frontendOk) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> result : apiResults) {
            if (Boolean.FALSE.equals(result.get("passed"))) {
                parts.add(result.get("name") + "（" + result.getOrDefault("error", "期望 " + result.get("expected")
                        + "，实际 " + result.get("status")) + "）");
            }
        }
        if (!frontendOk) {
            parts.add("前端 E2E 存在失败用例");
        }
        return "E2E 未通过：" + String.join("；", parts);
    }

    private static String tail(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : text.substring(text.length() - max));
    }
}
