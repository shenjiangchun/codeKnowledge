package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.workbench.service.exec.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E 怎么把服务拉起来。重点是降级链：可执行 jar → spring-boot:run → 编译产物 + classpath
 * （项目没有入口时由夹具提供）→ npm start，每一级都要给出能直接执行的命令。
 */
class E2eLaunchPlanTest {

    private static final String BOOT_APP = "Lorg/springframework/boot/autoconfigure/SpringBootApplication;";
    private static final String REST_CONTROLLER = "Lorg/springframework/web/bind/annotation/RestController;";

    @TempDir
    Path worktree;

    /** 不真的执行：解析 classpath 时按参数写出文件，其余命令直接成功，并记下每条命令。 */
    static class FakeRunner extends CommandRunner {
        final List<List<String>> commands = new ArrayList<>();

        @Override
        public Result run(Path workdir, List<String> command, Consumer<String> logConsumer,
                          long timeout, TimeUnit unit, Map<String, String> extraEnv, Consumer<Process> processHandle) {
            commands.add(command);
            for (String arg : command) {
                if (arg.startsWith("-Dmdep.outputFile=")) {
                    try {
                        Files.writeString(Path.of(arg.substring("-Dmdep.outputFile=".length())), "C:\\repo\\a.jar;C:\\repo\\b.jar");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            return new Result(0, "", false);
        }
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.ISO_8859_1);
    }

    private void jar(Path file, boolean boot) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(boot ? "BOOT-INF/" : "META-INF/MANIFEST.MF"));
            zip.closeEntry();
        }
    }

    @Test
    void theBootJarIsRunWithAPathRelativeToTheWorktree() throws Exception {
        jar(worktree.resolve("target/demo-1.0.0.jar"), true);
        write(worktree.resolve("pom.xml"), "<project/>");

        E2eStage.Launch launch = E2eStage.launchPlanOf(worktree);

        assertNotNull(launch);
        assertEquals(worktree, launch.workDir());
        assertEquals("target/demo-1.0.0.jar", launch.command().get(launch.command().size() - 1),
                "工作目录是 worktree 根，只写文件名会找不到 jar");
        assertTrue(String.join(" ", launch.command()).contains("java -jar"));
    }

    @Test
    void aPlainJarIsNotMistakenForAnExecutableOne() throws Exception {
        jar(worktree.resolve("target/lib-1.0.0.jar"), false);
        write(worktree.resolve("pom.xml"), "<project/>");
        Files.createDirectories(worktree.resolve("target/classes"));

        assertNull(E2eStage.launchPlanOf(worktree), "没有入口、没有控制器、没有可执行 jar：起不了服务");
    }

    @Test
    void aProjectWithTheSpringBootPluginRunsThroughMaven() throws Exception {
        write(worktree.resolve("target/classes/com/example/App.class"), "x" + BOOT_APP);
        write(worktree.resolve("pom.xml"), "<plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin>");

        E2eStage.Launch launch = E2eStage.launchPlanOf(worktree);

        assertNotNull(launch);
        assertEquals("spring-boot:run", launch.command().get(launch.command().size() - 1));
        assertTrue(launch.how().contains("com.example.App"));
    }

    @Test
    void aProjectWithoutAnEntryPointIsBootedByTheHarness() throws Exception {
        write(worktree.resolve("pom.xml"), "<project><dependencies/></project>");
        write(worktree.resolve("target/classes/com/example/controller/UserController.class"), "x" + REST_CONTROLLER);
        write(worktree.resolve("target/classes/com/example/service/UserServiceImpl.class"), "x");
        write(worktree.resolve("target/classes/com/example/controller/UserController$UserRequest.class"), "x" + REST_CONTROLLER);
        FakeRunner runner = new FakeRunner();
        List<String> log = new ArrayList<>();

        E2eStage.Launch launch = E2eStage.launchPlanOf(worktree, log::add, runner);

        assertNotNull(launch);
        // 1) 解析依赖 classpath  2) 编译夹具入口  3) 启动
        assertTrue(runner.commands.get(0).contains("dependency:build-classpath"));
        assertTrue(Path.of(runner.commands.get(1).get(0)).getFileName().toString().startsWith("javac"));
        assertEquals("wb.WbE2eApplication", launch.command().get(2));
        assertTrue(launch.command().get(1).endsWith("java.args"), "长 classpath 走 @argfile");
        assertEquals(worktree, launch.workDir());

        Path harness = worktree.resolve("target/wb-e2e");
        String source = Files.readString(harness.resolve("src/wb/WbE2eApplication.java"));
        assertTrue(source.contains("@ComponentScan(basePackages = \"com.example\")"),
                "扫描包是全部类的公共包，控制器和服务都要在它下面");
        String javaArgs = Files.readString(harness.resolve("java.args"));
        // 夹具类在前、项目编译产物其次、依赖在后；路径用正斜杠（@argfile 的引号里反斜杠是转义符）
        assertTrue(javaArgs.startsWith("-cp\n\"" + harness.resolve("classes").toString().replace('\\', '/') + ";"));
        assertTrue(javaArgs.contains("target/classes;C:/repo/a.jar;C:/repo/b.jar"));
        assertFalse(javaArgs.contains("\\"));
    }

    @Test
    void aProjectWithAMainClassButNoPluginRunsFromCompiledClasses() throws Exception {
        write(worktree.resolve("pom.xml"), "<project/>");
        write(worktree.resolve("target/classes/com/example/App.class"), "x" + BOOT_APP);

        E2eStage.Launch launch = E2eStage.launchPlanOf(worktree, line -> { }, new FakeRunner());

        assertNotNull(launch);
        assertEquals("com.example.App", launch.command().get(2));
        assertTrue(launch.how().contains("主类 com.example.App"));
    }

    @Test
    void aModuleWithoutSpringClassesFallsThroughToNodeOrNothing() throws Exception {
        write(worktree.resolve("pom.xml"), "<project/>");
        write(worktree.resolve("target/classes/com/example/Util.class"), "x");
        assertNull(E2eStage.launchPlanOf(worktree, line -> { }, new FakeRunner()));

        write(worktree.resolve("package.json"), "{\"name\":\"web\"}");
        E2eStage.Launch launch = E2eStage.launchPlanOf(worktree, line -> { }, new FakeRunner());
        assertNotNull(launch);
        assertEquals("start", launch.command().get(launch.command().size() - 1));
    }

    @Test
    void theServiceUnderTestGetsItsOwnHomeSoItCannotTouchTheDevelopersData() throws Exception {
        write(worktree.resolve("pom.xml"), "<project/>");
        write(worktree.resolve("target/classes/com/example/App.class"), "x" + BOOT_APP);
        Path home = E2eStage.isolatedHome(worktree);

        E2eStage.Launch launch = E2eStage.launchPlanOf(worktree, home, line -> { }, new FakeRunner());

        assertTrue(Files.isDirectory(home));
        assertTrue(home.startsWith(worktree.toAbsolutePath().resolve("target")), "隔离目录放在构建产物下，不进版本库");
        assertTrue(launch.command().contains("-Duser.home=" + home.toString().replace('\\', '/')));
        assertEquals("com.example.App", launch.command().get(launch.command().size() - 1));
    }

    @Test
    void aBootJarIsRunWithTheIsolatedHomeAsASeparateArgument() throws Exception {
        jar(worktree.resolve("target/demo-1.0.0.jar"), true);
        Path home = E2eStage.isolatedHome(worktree);

        E2eStage.Launch launch = E2eStage.launchPlanOf(worktree, home, line -> { }, new FakeRunner());

        assertEquals("target/demo-1.0.0.jar", launch.command().get(launch.command().size() - 1));
        assertTrue(launch.command().contains("-Duser.home=" + home.toString().replace('\\', '/')));
    }

    @Test
    void scanPackageIsTheCommonRootOrTheControllersParents() {
        assertEquals("com.example", E2eStage.scanPackage(
                List.of("com.example.controller.UserController", "com.example.service.UserServiceImpl",
                        "com.example.model.User"), List.of("com.example.controller.UserController")));

        assertEquals("com.shop.web,org.api.rest", E2eStage.scanPackage(
                List.of("com.shop.web.OrderController", "org.api.rest.HealthController"),
                List.of("com.shop.web.OrderController", "org.api.rest.HealthController")));

        assertEquals("", E2eStage.scanPackage(List.of(), List.of()));
    }

    @Test
    void harnessSourceIsFullyRendered() {
        String source = E2eStage.harnessSource("com.example");

        assertTrue(source.startsWith("package wb;"));
        assertTrue(source.contains("SpringApplication.run(Boot.class, args);"));
        assertFalse(source.contains("%s"));
    }
}
