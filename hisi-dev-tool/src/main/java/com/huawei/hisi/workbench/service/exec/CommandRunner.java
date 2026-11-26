package com.huawei.hisi.workbench.service.exec;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 修复流程的通用命令执行器：在 worktree 里跑构建/测试/E2E 命令，
 * 输出边读边推给日志，超时杀掉整个进程树。比 fixengine 的 MavenExecutor 更通用：
 * 支持任意命令、显式超时与取消登记。
 */
@Slf4j
public class CommandRunner {

    /** 命令在 Windows 上最多等多久（分钟）。 */
    private static final long DEFAULT_TIMEOUT_MINUTES = 15;

    public record Result(int exitCode, String output, boolean timedOut) {
        public boolean ok() { return exitCode == 0; }
    }

    /** 每行输出（含换行）推给日志。 */
    public Result run(Path workdir, List<String> command, Consumer<String> logConsumer) {
        return run(workdir, command, logConsumer, DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES, null, null);
    }

    /** 带超时与额外环境变量；processHandle 返回启动的进程（供中止登记）。 */
    public Result run(Path workdir, List<String> command, Consumer<String> logConsumer,
                      long timeout, TimeUnit unit, Map<String, String> extraEnv, java.util.function.Consumer<Process> processHandle) {
        log.info("[WorkbenchExec] {} {}", workdir, String.join(" ", command));
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            if (extraEnv != null && !extraEnv.isEmpty()) {
                pb.environment().putAll(extraEnv);
            }
            process = pb.start();
            if (processHandle != null) {
                processHandle.accept(process);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (logConsumer != null) {
                        logConsumer.accept(line);
                    }
                }
            }
            boolean finished = process.waitFor(timeout, unit);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(-1, output.toString(), true);
            }
            return new Result(process.exitValue(), output.toString(), false);
        } catch (Exception e) {
            log.error("[WorkbenchExec] 命令执行异常: {}", e.getMessage());
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new Result(-1, output + "\n[执行异常] " + e.getMessage(), false);
        }
    }

    /** 探测项目根下哪个 Maven 包装器可用：mvnw.cmd / mvnw / mvn。 */
    public static List<String> mavenCommand(Path projectRoot) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            if (projectRoot.resolve("mvnw.cmd").toFile().exists()) {
                return List.of(projectRoot.resolve("mvnw.cmd").toString());
            }
            return List.of("mvn.cmd");
        }
        if (projectRoot.resolve("mvnw").toFile().exists()) {
            return List.of("./mvnw");
        }
        return List.of("mvn");
    }

    /** npm / npx 的可执行名：Windows 上它们是 .cmd，ProcessBuilder 不带扩展名找不到。 */
    public static String npm() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "npm.cmd" : "npm";
    }

    public static String npx() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "npx.cmd" : "npx";
    }

    /** 是否 Maven 项目。 */
    public static boolean isMavenProject(Path projectRoot) {
        return projectRoot.resolve("pom.xml").toFile().exists();
    }

    private static final java.util.regex.Pattern TEST_FRAMEWORK = java.util.regex.Pattern.compile(
            "junit-jupiter|<artifactId>junit</artifactId>|testng|spock-core|kotest|spring-boot-starter-test");

    /**
     * Maven 项目跑不起 Spec 用例的缺口，每条都给出要补的内容；没有缺口返回空串。
     * 任务拆分据此要求补构建文件，开发阶段据此改 pom。
     */
    public static String testSetupGaps(Path projectRoot) {
        if (!isMavenProject(projectRoot)) {
            return "";
        }
        String pom = readText(projectRoot.resolve("pom.xml"));
        if (pom == null) {
            return "";
        }
        StringBuilder gaps = new StringBuilder();
        if (!TEST_FRAMEWORK.matcher(pom).find()) {
            if (pom.contains("spring-boot")) {
                String version = "";
                if (!pom.contains("spring-boot-starter-parent") && !pom.contains("spring-boot-dependencies")) {
                    version = pom.contains("<spring-boot.version>")
                            ? "\n            <version>${spring-boot.version}</version>"
                            : "\n            <version>（与 pom 里其它 spring-boot 依赖相同的版本）</version>";
                }
                gaps.append("- 没有测试依赖：需要在 <dependencies> 里加上\n")
                        .append("        <dependency>\n")
                        .append("            <groupId>org.springframework.boot</groupId>\n")
                        .append("            <artifactId>spring-boot-starter-test</artifactId>\n").append(version).append("\n")
                        .append("            <scope>test</scope>\n")
                        .append("        </dependency>\n");
            } else {
                boolean bomManaged = pom.contains("junit-bom") || pom.contains("${junit.version}");
                String junitVersion = bomManaged ? ""
                        : pom.contains("<junit.version>") ? "\n            <version>${junit.version}</version>"
                        : "\n            <version>5.10.2</version>";
                gaps.append("- 没有测试依赖：需要在 <dependencies> 里加上 org.junit.jupiter:junit-jupiter（scope test")
                        .append(junitVersion.isEmpty() ? "，版本已管理" : junitVersion)
                        .append("）和 org.mockito:mockito-core（scope test，版本 5.11.0）\n");
            }
        }
        if (!pom.contains("maven-surefire-plugin") && !pom.contains("spring-boot-starter-parent")) {
            gaps.append("- 没有声明 maven-surefire-plugin：当前 Maven 3.8 自带的 2.12.4 不支持 JUnit 5，")
                    .append("需要在 <build><plugins> 里声明 org.apache.maven.plugins:maven-surefire-plugin，版本 3.2.5")
                    .append("（pom 里还没有 <build> 就新建一个，放在 </project> 之前）\n");
        }
        return gaps.toString();
    }

    /**
     * 项目没有 Spring Boot 启动类时说明一声：这时 @SpringBootTest / @WebMvcTest 这类依赖容器的用例根本加载不起来。
     * 有启动类（或不是 Maven 项目）返回空串。
     */
    public static String springContextNote(Path projectRoot) {
        if (!isMavenProject(projectRoot) || hasSpringBootApplication(projectRoot)) {
            return "";
        }
        return "- 项目里没有 @SpringBootApplication 启动类：@SpringBootTest、@WebMvcTest 这类依赖 Spring 容器的用例加载不起来；"
                + "unit 用例请直接 new 被测类，或用 MockMvcBuilders.standaloneSetup，不要依赖容器启动\n";
    }

    private static boolean hasSpringBootApplication(Path projectRoot) {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return false;
        }
        // 先看名字像启动类的文件，绝大多数项目第一步就能命中，不必读完整个源码树
        try (var files = Files.walk(sourceRoot)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : sources) {
                if (file.getFileName().toString().endsWith("Application.java") && mentionsSpringBootApplication(file)) {
                    return true;
                }
            }
            for (Path file : sources) {
                if (mentionsSpringBootApplication(file)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static boolean mentionsSpringBootApplication(Path file) {
        String text = readText(file);
        return text != null && text.contains("@SpringBootApplication");
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否 Python 项目（pytest 可用时）。 */
    public static boolean isPythonProject(Path projectRoot) {
        return projectRoot.resolve("requirements.txt").toFile().exists()
                || projectRoot.resolve("pyproject.toml").toFile().exists();
    }

    /** 是否 Node 项目。 */
    public static boolean isNodeProject(Path projectRoot) {
        return projectRoot.resolve("package.json").toFile().exists();
    }

    /** 项目里是否带了前端 Playwright 套件。 */
    public static boolean hasPlaywright(Path projectRoot) {
        if (!isNodeProject(projectRoot)) {
            return false;
        }
        try {
            String packageJson = java.nio.file.Files.readString(projectRoot.resolve("package.json"));
            return packageJson.contains("@playwright/test");
        } catch (Exception e) {
            return false;
        }
    }

    /** 把测试类全名转成相对项目根的源码路径，找不到时返回 null。 */
    public static Path resolveTestSource(Path projectRoot, String className) {
        String relative = className.replace('.', '/') + ".java";
        List<String> roots = List.of("src/test/java", "src/test/kotlin", "src/test/groovy");
        for (String root : roots) {
            Path candidate = projectRoot.resolve(root).resolve(relative);
            if (candidate.toFile().exists()) {
                return candidate;
            }
        }
        // 多模块：在子模块的 src/test/java 下找
        File[] modules = projectRoot.toFile().listFiles(File::isDirectory);
        if (modules != null) {
            for (File module : modules) {
                for (String root : roots) {
                    Path candidate = module.toPath().resolve(root).resolve(relative);
                    if (candidate.toFile().exists()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** 向上找包含某文件的模块：测试源在哪个模块就 -pl 哪个模块。 */
    public static String moduleOf(Path projectRoot, Path file) {
        Path parent = file.getParent();
        Path root = projectRoot.toAbsolutePath().normalize();
        while (parent != null && parent.startsWith(root) && !parent.equals(root)) {
            if (parent.resolve("pom.xml").toFile().exists()) {
                Path relative = root.relativize(parent);
                return relative.toString().replace('\\', '/');
            }
            parent = parent.getParent();
        }
        return null;
    }

    /** 读取 surefire 报告目录里的用例统计（tests/errors/failures 与失败用例名）。 */
    public static Map<String, Object> surefireSummary(Path projectRoot) {
        List<Path> reports = new ArrayList<>();
        collectSurefireReports(projectRoot.toFile(), reports, 0);
        int tests = 0, errors = 0, failures = 0;
        List<String> failedTests = new ArrayList<>();
        for (Path report : reports) {
            try {
                String xml = java.nio.file.Files.readString(report);
                for (String tag : new String[]{"tests", "errors", "failures"}) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile(tag + "=\"(\\d+)\"").matcher(xml);
                    if (m.find()) {
                        int value = Integer.parseInt(m.group(1));
                        if ("tests".equals(tag)) {
                            tests += value;
                        } else if ("errors".equals(tag)) {
                            errors += value;
                        } else {
                            failures += value;
                        }
                    }
                }
                java.util.regex.Matcher fail = java.util.regex.Pattern
                        .compile("<testcase name=\"([^\"]+)\"[^>]*>(?:\\s*<(?:error|failure))").matcher(xml);
                while (fail.find()) {
                    failedTests.add(fail.group(1));
                }
            } catch (Exception ignored) {
                // 报告可能还在写入或格式异常：跳过
            }
        }
        return Map.of("tests", tests, "errors", errors, "failures", failures, "failed", failedTests);
    }

    private static void collectSurefireReports(File dir, List<Path> out, int depth) {
        if (depth > 5) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if ("surefire-reports".equals(child.getName())) {
                    File[] files = child.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
                    if (files != null) {
                        for (File file : files) {
                            out.add(file.toPath());
                        }
                    }
                } else {
                    collectSurefireReports(child, out, depth + 1);
                }
            }
        }
    }
}
