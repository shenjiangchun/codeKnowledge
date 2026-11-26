package com.huawei.hisi.workbench.service.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.service.TargetProcessManager;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.service.InMemoryAgentEvents;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.WorkbenchEventSink;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 阶段的入口守卫与结构检查。核心是「没有可执行的东西时必须失败，不能跳过或假通过」——
 * 回归与 E2E 的用例都来自 Spec，Spec 里没有就是没得跑。
 */
class StageGuardsTest {

    @TempDir
    Path temp;

    private InMemoryAgentEvents events;
    private StageContext context;

    @BeforeEach
    void setUp() {
        events = new InMemoryAgentEvents();
        WorkbenchFixRun run = WorkbenchFixRun.builder().id(5L).itemKey("k1").sessionId(77L)
                .worktreePath(temp.toString()).build();
        WorkbenchItem item = WorkbenchItem.builder().itemKey("k1").projectPath(temp.toString()).title("t").build();
        context = new StageContext(run, item, new WorkbenchEventSink(events, new ObjectMapper()), new HashMap<>(), null);
    }

    /** 没有任何产物的上下文；事件落在独立的内存仓库里（守卫会先发布失败结果）。 */
    private static StageContext contextWith(Map<String, Object> artifacts) {
        WorkbenchFixRun run = WorkbenchFixRun.builder().id(5L).itemKey("k1").sessionId(77L).worktreePath("/nowhere").build();
        WorkbenchItem item = WorkbenchItem.builder().itemKey("k1").projectPath("/nowhere").title("t").build();
        return new StageContext(run, item, new WorkbenchEventSink(new InMemoryAgentEvents(), new ObjectMapper()),
                artifacts, null);
    }

    private static Map<String, Object> testCase(String type, String name, String target, String verify) {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("type", type);
        testCase.put("name", name);
        testCase.put("target", target);
        testCase.put("verify", verify);
        return testCase;
    }

    private static Map<String, Object> specOf(Map<String, Object>... cases) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("goal", "修复空指针");
        spec.put("acceptanceCriteria", List.of("不再抛 NPE"));
        spec.put("testCases", new ArrayList<>(List.of(cases)));
        return spec;
    }

    // ──────────────────────────── 回归 / E2E：零用例不假通过 ────────────────────────────

    @Test
    void regressionFailsWhenTheSpecHasNoReproOrUnitCases() {
        StageContext ctx = contextWith(Map.of("spec", specOf(testCase("api", "查询用户", "GET /users/1", "返回 200"))));

        StageHandler.StageOutcome outcome = new TestsStage().execute(ctx);

        assertEquals(StageHandler.StageOutcome.FAILED, outcome.kind());
        assertTrue(outcome.message().contains("未定义"));
    }

    @Test
    void regressionFailsWhenThereIsNoSpecAtAll() {
        StageHandler.StageOutcome outcome = new TestsStage().execute(contextWith(Map.of()));

        assertEquals(StageHandler.StageOutcome.FAILED, outcome.kind());
    }

    @Test
    void regressionPublishesAFailedResultSoTheReportDoesNotShowItAsSkipped() {
        Map<String, Object> artifacts = new HashMap<>();
        artifacts.put("spec", specOf(testCase("api", "查询用户", "GET /users/1", "返回 200")));
        StageContext ctx = new StageContext(context.run(), context.item(),
                new WorkbenchEventSink(events, new ObjectMapper()), artifacts, null);

        new TestsStage().execute(ctx);

        String checkpoint = events.findBySessionId(77L).stream().filter(e -> e.getType() == EventType.CHECKPOINT)
                .map(e -> e.getPayload()).reduce((first, second) -> second).orElse("");
        assertTrue(checkpoint.contains("\"nodeName\":\"tests\""));
        assertTrue(checkpoint.contains("\"passed\":false"));
    }

    @Test
    void e2eFailsWhenTheSpecHasNoApiOrE2eCases() {
        Map<String, Object> spec = specOf(testCase("unit", "空服务时返回 503", "com.a.UserControllerTest#a", "断言 503"));
        StageContext ctx = contextWith(Map.of("spec", spec));

        StageHandler.StageOutcome outcome = new E2eStage(mock(TargetProcessManager.class)).execute(ctx);

        assertEquals(StageHandler.StageOutcome.FAILED, outcome.kind());
        assertTrue(outcome.message().contains("未定义"));
    }

    @Test
    void withoutServiceStartupTheE2eStageDoesNotDemandApiCasesItNeverAskedTheSpecFor() {
        // 不启动被测服务时，Spec 阶段不要求 api/e2e 用例；E2E 阶段这时只验证构建，不能再以「没有 api/e2e 用例」判失败
        // （真机上第 12 轮回归都通过了，却在这里被判失败）
        Map<String, Object> spec = specOf(testCase("unit", "空服务时返回 503", "com.a.UserControllerTest#a", "断言 503"));
        E2eStage stage = new E2eStage(mock(TargetProcessManager.class));
        stage.serviceE2e = false;

        StageHandler.StageOutcome outcome = stage.execute(contextWith(Map.of("spec", spec)));

        assertFalse(outcome.kind().equals(StageHandler.StageOutcome.FAILED) && outcome.message().contains("未定义"),
                "不应因为 Spec 没有 api/e2e 用例而失败，实际：" + outcome.message());
    }

    @Test
    void regressionCasesArePickedFromTheSpecByType() {
        Map<String, Object> spec = specOf(
                testCase("repro", "复现 NPE", "com.a.UserControllerTest#npe", "抛出 NPE"),
                testCase("UNIT", "空服务返回 503", "com.a.UserControllerTest#empty", "返回 503"),
                testCase("api", "查询用户", "GET /users/1", "返回 200"));

        List<Map<String, Object>> regression = TestsStage.testCases(spec, "repro", "unit");
        List<Map<String, Object>> endToEnd = TestsStage.testCases(spec, "api", "e2e");

        assertEquals(List.of("复现 NPE", "空服务返回 503"), regression.stream().map(c -> c.get("name")).toList());
        assertEquals(List.of("查询用户"), endToEnd.stream().map(c -> c.get("name")).toList());
        assertTrue(TestsStage.testCases(Map.of(), "repro", "unit").isEmpty());
    }

    @Test
    void testClassNameIgnoresTheMethodPart() {
        assertEquals("com.a.UserControllerTest", TestsStage.classNameOf("com.a.UserControllerTest#getUser_nullService"));
        assertEquals("com.a.UserControllerTest", TestsStage.classNameOf(" com/a/UserControllerTest "));
    }

    // ──────────────────────────── Spec：结构检查与落点解析 ────────────────────────────

    private static final List<String> MAPPINGS = List.of("GET /users/{id}", "POST /users", "GET /users/search");

    @Test
    void aCompleteSpecHasNoProblems() {
        Map<String, Object> spec = specOf(
                testCase("repro", "复现 NPE", "com.a.UserControllerTest#npe", "抛出 NullPointerException"),
                testCase("api", "查询用户", "GET /users/{id}", "返回 200"));

        assertTrue(SpecStage.problemsOf(spec, MAPPINGS).isEmpty());
    }

    @Test
    void specWithoutRegressionCasesIsRejected() {
        Map<String, Object> spec = specOf(testCase("api", "查询用户", "GET /users/{id}", "返回 200"));

        assertTrue(SpecStage.problemsOf(spec, MAPPINGS).contains("至少需要 1 条 repro 或 unit 用例"));
    }

    @Test
    void aWebProjectMustHaveACaseThatHitsTheInterface() {
        Map<String, Object> spec = specOf(testCase("unit", "空服务返回 503", "com.a.UserControllerTest#a", "返回 503"));

        assertTrue(SpecStage.problemsOf(spec, MAPPINGS).contains("项目有 HTTP 接口，至少需要 1 条 api 或 e2e 用例"));
    }

    @Test
    void aProjectWithoutHttpInterfacesMayHaveOnlyUnitCases() {
        Map<String, Object> spec = specOf(testCase("unit", "排序稳定", "com.a.SortUtilTest#stable", "断言顺序不变"));

        assertTrue(SpecStage.problemsOf(spec, List.of()).isEmpty());
    }

    @Test
    void apiCasesAreNotRequiredWhenTheServiceWillNotBeStarted() {
        Map<String, Object> spec = specOf(testCase("unit", "拆分后行为不变", "com.a.SkillServiceImplTest#definitions", "断言技能清单不变"));

        assertTrue(SpecStage.problemsOf(spec, MAPPINGS).contains("项目有 HTTP 接口，至少需要 1 条 api 或 e2e 用例"));
        assertTrue(SpecStage.problemsOf(spec, MAPPINGS, false).isEmpty(), "不启动服务做接口验证时，单元用例就够了");
    }

    @Test
    void apiCaseOnAnInventedPathIsRejected() {
        Map<String, Object> spec = specOf(
                testCase("unit", "空服务返回 503", "com.a.UserControllerTest#a", "返回 503"),
                testCase("api", "查询用户", "GET /api/users/{id}", "返回 200"));

        assertTrue(SpecStage.problemsOf(spec, MAPPINGS).get(0).contains("不在 HTTP 接口清单中"));
    }

    @Test
    void apiCasePathIsNotCheckedWhenTheCodeHasNoMappings() {
        Map<String, Object> spec = specOf(
                testCase("unit", "空服务返回 503", "com.a.UserControllerTest#a", "返回 503"),
                testCase("api", "查询用户", "GET /users/1", "返回 200"));

        assertTrue(SpecStage.problemsOf(spec, List.of()).isEmpty());
    }

    @Test
    void anApiCaseThatDependsOnTestDoublesIsRejected() {
        // E2E 是对真实启动的服务发请求：靠 @MockBean 预设状态的用例在那一步永远跑不过
        Map<String, Object> mocked = specOf(
                testCase("unit", "空服务返回 503", "com.a.UserControllerTest#a", "返回 503"),
                testCase("api", "用户不存在时返回 404", "GET /users/{id}",
                        "使用 @MockBean 将 UserService.getUserById 返回 null，请求 /users/1，返回 404"));

        assertTrue(SpecStage.problemsOf(mocked, MAPPINGS).get(0).contains("必须是黑盒请求"));

        Map<String, Object> blackBox = specOf(
                testCase("unit", "空服务返回 503", "com.a.UserControllerTest#a", "返回 503"),
                testCase("api", "查询不存在的用户", "GET /users/{id}", "GET /users/999999 返回 404，响应体 code 为 USER_NOT_FOUND"));
        assertTrue(SpecStage.problemsOf(blackBox, MAPPINGS).isEmpty());
    }

    @Test
    void specWithoutGoalOrCasesIsRejected() {
        assertFalse(SpecStage.problemsOf(null, MAPPINGS).isEmpty());
        List<String> problems = SpecStage.problemsOf(Map.of("goal", " ", "acceptanceCriteria", List.of()), MAPPINGS);
        assertTrue(problems.contains("缺少 goal"));
        assertTrue(problems.contains("缺少非空的 testCases"));
    }

    @Test
    void requestTargetParsesIntoMethodAndPath() {
        assertEquals("GET", SpecStage.parseRequest("GET /users/{id}")[0]);
        assertEquals("/users/{id}", SpecStage.parseRequest("GET /users/{id}")[1]);
        assertEquals("POST", SpecStage.parseRequest("post /users")[0]);
        assertEquals("/users", SpecStage.parseRequest("/users")[1]);
        assertEquals("/users", SpecStage.parseRequest("GET http://localhost:8080/users?x=1")[1]);
        assertNull(SpecStage.parseRequest("users/{id}"));
        assertNull(SpecStage.parseRequest(""));
    }

    @Test
    void e2eReadsTheStatusFromTheVerifyTextAndKeepsTheQuery() {
        assertEquals(404, E2eStage.expectedStatus("返回 404，而不是 500"));
        assertEquals(200, E2eStage.expectedStatus("响应 200 且 id 为 2001"));
        assertNull(E2eStage.expectedStatus("响应体的 id 为 1"));
        assertNull(E2eStage.expectedStatus(null));
        assertEquals("?name=1", E2eStage.queryOf("GET /users/search?name={name}"));
        assertEquals("", E2eStage.queryOf("GET /users/{id}"));
    }

    // ──────────────────────────── 方案 / 任务：结构检查 ────────────────────────────

    @Test
    void proposalWithoutOptionsIsRejectedAndSparseOptionsAreCompleted() {
        assertTrue(ProposalStage.problemsOf(null).contains("没有返回内容"));
        assertTrue(ProposalStage.problemsOf(Map.of("recommendation", "A")).get(0).contains("options"));
        assertTrue(ProposalStage.problemsOf(Map.of("options", List.of(Map.of("id", "A")))).contains("options[0] 缺少 summary"));

        List<Map<String, Object>> options = ProposalStage.normalizeOptions(List.of(
                Map.of("summary", "直接判空并返回 503。补上单元测试覆盖。"),
                Map.of("id", "B", "title", "修复并补防护", "summary", "改实现", "risk", "low")));

        assertEquals(List.of("A", "B"), options.stream().map(o -> o.get("id")).toList());
        assertEquals("直接判空并返回 503", options.get(0).get("title"));
        assertEquals("medium", options.get(0).get("risk"));
        assertEquals("low", options.get(1).get("risk"));
        assertTrue(ProposalStage.normalizeOptions("不是数组").isEmpty());
    }

    private static Map<String, Object> task(String file) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("title", "任务 " + file);
        task.put("file", file);
        task.put("verify", "用例");
        return task;
    }

    @Test
    void tasksNeedFullPaths() {
        assertTrue(TasksStage.problemsOf(Map.of("tasks", List.of(task("UserController.java"))), false)
                .get(0).contains("完整路径"));
        assertTrue(TasksStage.problemsOf(Map.of("tasks", List.of()), false).get(0).contains("tasks 数组"));

        List<Map<String, Object>> ok = List.of(
                task("src/test/java/com/a/UserControllerTest.java"), task("src/main/java/com/a/UserController.java"));
        assertTrue(TasksStage.problemsOf(Map.of("tasks", ok), false).isEmpty());
    }

    @Test
    void whenTheProjectLacksTestSetupTheFirstTaskMustFixTheBuildFile() {
        List<Map<String, Object>> missingBuildFile = List.of(
                task("src/test/java/com/a/UserControllerTest.java"), task("pom.xml"));
        List<String> problems = TasksStage.problemsOf(Map.of("tasks", missingBuildFile), true);
        assertTrue(problems.stream().anyMatch(p -> p.contains("tasks[0] 必须是补齐构建文件的任务")));

        List<Map<String, Object>> rightOrder = List.of(task("pom.xml"),
                task("src/test/java/com/a/UserControllerTest.java"));
        assertTrue(TasksStage.problemsOf(Map.of("tasks", rightOrder), true).isEmpty());

        List<Map<String, Object>> noTestClass = List.of(task("pom.xml"), task("src/main/java/com/a/UserController.java"));
        assertTrue(TasksStage.problemsOf(Map.of("tasks", noTestClass), true).contains("缺少新建测试类（src/test/ 下）的任务"));
    }

    @Test
    void everyTestClassNamedByTheSpecNeedsATask() {
        Map<String, Object> spec = specOf(
                testCase("repro", "复现 NPE", "com.a.UserControllerReproTest#npe", "抛出 NPE"),
                testCase("unit", "空服务返回 503", "com.a.UserControllerTest#empty", "返回 503"),
                testCase("unit", "同一类里的另一条", "com.a.UserControllerTest#other", "返回 503"),
                testCase("api", "查询用户", "GET /users/1", "返回 200"));
        List<String> classes = TasksStage.testClassesOf(spec);
        assertEquals(List.of("com.a.UserControllerReproTest", "com.a.UserControllerTest"), classes, "按落点去重，api 用例不算");

        List<Map<String, Object>> onlyOne = List.of(task("pom.xml"),
                task("src/test/java/com/a/UserControllerTest.java"), task("src/main/java/com/a/UserController.java"));
        assertTrue(TasksStage.problemsOf(Map.of("tasks", onlyOne), true, classes).stream()
                .anyMatch(p -> p.contains("UserControllerReproTest") && p.contains("src/test/java/com/a/UserControllerReproTest.java")));

        List<Map<String, Object>> both = new ArrayList<>(onlyOne);
        both.add(task("src/test/java/com/a/UserControllerReproTest.java"));
        assertTrue(TasksStage.problemsOf(Map.of("tasks", both), true, classes).isEmpty());
    }

    // ──────────────────────────── 项目现状：测试基础设施缺口 ────────────────────────────

    private Path pom(String name, String content) throws Exception {
        Path dir = temp.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), content);
        return dir;
    }

    @Test
    void testSetupGapsNameWhatToAdd() throws Exception {
        String bare = """
                <project>
                  <properties><spring-boot.version>3.2.0</spring-boot.version></properties>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId><version>${spring-boot.version}</version></dependency>
                  </dependencies>
                </project>
                """;
        String gaps = CommandRunner.testSetupGaps(pom("bare", bare));

        assertTrue(gaps.contains("spring-boot-starter-test"));
        assertTrue(gaps.contains("${spring-boot.version}"), "没有 BOM 管理版本时要给出版本写法");
        assertTrue(gaps.contains("maven-surefire-plugin"));
        assertTrue(gaps.contains("3.2.5"));

        String plain = """
                <project><dependencies>
                  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                    <scope>test</scope></dependency>
                </dependencies>
                <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><version>3.1.2</version></plugin></plugins></build>
                </project>
                """;
        assertEquals("", CommandRunner.testSetupGaps(pom("plain", plain)), "测试依赖和 surefire 都齐了就没有缺口");
        assertEquals("", CommandRunner.testSetupGaps(temp.resolve("not-maven")));
    }

    @Test
    void plainJavaProjectIsToldToAddJupiterNotSpring() throws Exception {
        String plain = "<project><dependencies></dependencies></project>";

        String gaps = CommandRunner.testSetupGaps(pom("plain-java", plain));

        assertTrue(gaps.contains("junit-jupiter"));
        assertFalse(gaps.contains("spring-boot-starter-test"));
    }

    @Test
    void aProjectWithoutASpringBootApplicationIsCalledOut() throws Exception {
        Path noBoot = pom("no-boot", "<project/>");
        Files.createDirectories(noBoot.resolve("src/main/java/com/a"));
        Files.writeString(noBoot.resolve("src/main/java/com/a/UserController.java"), "class UserController {}");

        String note = CommandRunner.springContextNote(noBoot);

        assertTrue(note.contains("没有 @SpringBootApplication"));
        assertTrue(note.contains("standaloneSetup"));

        Path withBoot = pom("with-boot", "<project/>");
        Files.createDirectories(withBoot.resolve("src/main/java/com/a"));
        Files.writeString(withBoot.resolve("src/main/java/com/a/App.java"),
                "@SpringBootApplication public class App {}");
        assertEquals("", CommandRunner.springContextNote(withBoot), "启动类不一定叫 Application.java，照样要认出来");
        assertEquals("", CommandRunner.springContextNote(temp.resolve("not-maven")));
    }

    // ──────────────────────────── 证据：方法源码片段 ────────────────────────────

    @Test
    void methodSnippetCoversTheAnnotationsAndTheBody() {
        String source = "class A {\r\n    int other() { return userService.get(); }\r\n\r\n    @GetMapping(\"/{id}\")\r\n"
                + "    public User getUser(@PathVariable Long id) {\r\n        return userService.getUserById(id);\r\n    }\r\n"
                + "    void after() { }\r\n}\r\n";

        String snippet = EvidenceStage.methodSnippet(source, "getUser");

        assertTrue(snippet.contains("@GetMapping(\"/{id}\")"));
        assertTrue(snippet.contains("return userService.getUserById(id);"));
        assertFalse(snippet.contains("after()"), "片段到方法体结束为止");
        assertNull(EvidenceStage.methodSnippet(source, "missing"));
    }

    @Test
    void theThrowLineFromTheStackTraceIsShownEvenInsideAVeryLongMethod() {
        StringBuilder source = new StringBuilder("class A {\n");
        for (int i = 0; i < 300; i++) {
            source.append("    // filler line ").append(i).append('\n');
        }
        source.append("    boolean hit = s.get(\"category\").equals(category);\n}\n");

        String around = EvidenceStage.aroundLine(source.toString(), 302, 12, 8);

        assertEquals(302, EvidenceStage.locationLine("com.a.SkillMarketController#getSkillList:190".replace("190", "302")));
        assertTrue(around.contains("302 |     boolean hit = s.get(\"category\").equals(category);"));
        assertTrue(around.startsWith("290 |"), "从抛出行前 12 行开始");
        assertTrue(around.contains("303 | }"), "到抛出行后 8 行（文件末尾）为止");
        assertNull(EvidenceStage.aroundLine("class A {}\n", 99, 12, 8));
        assertEquals(0, EvidenceStage.locationLine("com.a.UserController#getUser"));
        assertEquals(0, EvidenceStage.locationLine(null));
    }

    // ──────────────────────────── 证据：类名定位 ────────────────────────────

    private Path source(String project, String relativePath) throws Exception {
        Path file = temp.resolve(project).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A {}");
        return file;
    }

    @Test
    void aSimpleClassNameIsResolvedAgainstTheProjectSources() throws Exception {
        source("p1", "src/main/java/com/example/controller/UserController.java");

        assertEquals("com.example.controller.UserController",
                EvidenceStage.resolveClassName(temp.resolve("p1").toString(), "UserController", null));
    }

    @Test
    void aSimpleClassNameIsResolvedInsideAOneLevelModule() throws Exception {
        source("p2", "api/src/main/java/com/shop/web/OrderController.java");
        Files.writeString(temp.resolve("p2/pom.xml"), "<project/>");

        assertEquals("com.shop.web.OrderController",
                EvidenceStage.resolveClassName(temp.resolve("p2").toString(), "OrderController", null));
    }

    @Test
    void anUnresolvableClassNameFallsBackToTheStackFrameThenToItself() {
        String evidence = "[\"[E2] com.example.controller.UserController.getUser() 第 20 行（UserController.java）[抛出点]\"]";

        assertEquals("com.example.controller.UserController",
                EvidenceStage.resolveClassName(temp.resolve("missing").toString(), "UserController", evidence));
        assertEquals("Unknown",
                EvidenceStage.resolveClassName(temp.resolve("missing").toString(), "Unknown", "没有堆栈帧"));
        assertEquals("com.example.UserController",
                EvidenceStage.resolveClassName(temp.resolve("missing").toString(), "com.example.UserController", null),
                "已经是全限定名就原样使用");
        assertNull(EvidenceStage.resolveClassName(temp.toString(), null, null));
    }

    @Test
    void staleSurefireReportsAreDeletedBeforeARun(@TempDir Path worktree) throws Exception {
        Path reports = worktree.resolve("target/surefire-reports");
        Path moduleReports = worktree.resolve("api/target/surefire-reports");
        Files.createDirectories(reports);
        Files.createDirectories(moduleReports);
        Files.writeString(reports.resolve("TEST-old.xml"), "<testsuite tests=\"3\"/>");
        Files.writeString(moduleReports.resolve("TEST-old.xml"), "<testsuite tests=\"1\"/>");
        Path classes = worktree.resolve("target/classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("A.class"), "x");

        TestsStage.deleteSurefireReports(worktree);

        assertTrue(Files.notExists(reports.resolve("TEST-old.xml")));
        assertTrue(Files.notExists(moduleReports.resolve("TEST-old.xml")));
        assertTrue(Files.exists(classes.resolve("A.class")), "只清报告，不动编译产物");
        TestsStage.deleteSurefireReports(worktree.resolve("missing"));
    }
}
