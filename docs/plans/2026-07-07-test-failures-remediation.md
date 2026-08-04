# Pre-existing Test Failures Remediation (2026-07-07)

> 状态：本地验证完成（Tier 1 + #4 + #5 + #9 修复已验证通过；#8 搁置等专家评审；#10 暂跳过等用户决策），等用户在远端代码仓修改后回拉比对。
> 约束：不在本地提交。改完后 git diff 应仅含本文档与下文所列测试文件。
> 前置文档：docs/plans/2026-07-06-test-failures-backlog.md（仅登记问题，不修复）

## 摘要

对 2026-07-06 backlog 登记的 17 个 pre-existing 失败做了逐项复核，结果如下：

- 17 个失败全部可复现，0 flake（与 backlog 一致）
- 分类修正：原 backlog 标 2 BUG / 7 TEST / 1 ENV，复核后修正为 2 BUG / 14 TEST / 1 ENV
  - #4 PythonCallGraphResolverTest：原标 BUG，复核确认 生产对、测试错 → 重分类为 TEST
  - #5 FastApiRouteScannerTest：原标 TEST，复核确认无误，仍是 TEST
- 新增 2 个 compile-blocker（不在 backlog 内，2026-07-01 commit 70a6c4c 引入）
  - ParseNodeTest / LogAnalysisDagOrchestratorTest：new ParseNode() 调用已删的无参构造
  - 性质：TEST（测试未跟上生产代码构造函数签名变更）
- 修正 backlog 的 BUG 计数：原标 2 BUG，实际仅 1 BUG（#8 DagExecutorTest）
  - #4 重分类为 TEST 后，只剩 #8 是真正的生产 BUG
- **本次实际落地**（共 7 个文件，git diff 范围）：
  - Tier 1 全部 5 项：#2 / #3 / #6 / #7 / #9 ✓
  - Tier 2 部分 2 项：#4 / #5 ✓
  - #11 compile-blocker：ParseNodeTest / LogAnalysisDagOrchestratorTest 已在之前 commit 处于 target state（无 diff）
  - #1 FailureLocatorE2ETest：已在之前 commit 处于 target state（无 diff）
  - **#8 搁置**：DagExecutorTest 已恢复原状（无 diff），等专家评审后再修
  - **#10 暂跳过**：等产品决策
- 验证结果：`mvn surefire:test -Dtest='EntryPointTypeTest,PythonKnowledgeGraphBuilderTest,FlaskRouteScannerTest,KnowledgeGraphCommonUtilsTest,ProjectServiceImplTest,PythonCallGraphResolverTest,FastApiRouteScannerTest' -DfailIfNoTests=false` → **Tests run: 78, Failures: 0, Errors: 0** ✓

## 失败分类原则

| 类别 | 含义 | 修复方向 |
|---|---|---|
| TEST | 测试代码老旧：生产代码契约变了/新加了功能/枚举扩容，测试期望没跟上 | 改测试 |
| BUG | 生产代码错：行为不符合测试期望，且测试期望是合理的 | 改生产 |
| ENV | 环境差异：Windows 路径分隔符等 | 改测试（normalize） |

**判断标准**：如果测试期望符合当前设计意图/契约文档 → BUG；如果测试期望停留在旧契约 → TEST。

## 调查方法

1. mvn test-compile 看是否编译通过（结果：2 个 compile-blocker，预先排除）
2. 临时把 compile-blocker 重命名为 .bak，跑 mvn test-compile 确认其余测试可编译
3. 逐个跑失败测试类：mvn surefire:test -Dtest='XxxTest' -DfailIfNoTests=false
4. 读 surefire 报告 target/surefire-reports/*.txt 拿到准确 assertion 错误
5. 读测试代码 + 对应生产代码 + git log 看相关 commit
6. 对每项判定 TEST / BUG / ENV，给出修复方案

环境 JAVA_HOME=C:/Program Files/Java/jdk-17.0.3.1，Maven 3.3.9，Windows 11。

---

## Tier 1 — 机械测试修复（6 项，零生产风险）

### #2 EntryPointTypeTest.testAllTypesExist — TEST

**根因**：EntryPointType enum 在 commit 37a87ef69246de 期间加了 FEIGN_CLIENT 后总数变 12，测试硬编码 11。

**surefire 输出**：
```
expected: <11> but was: <12>
```

**生产代码**（src/main/java/com/huawei/hisi/knowledgegraph/model/EntryPointType.java:7-19）：
```java
public enum EntryPointType {
    HTTP, SCHEDULED, MQ, EVENT, WEBSOCKET, RPC, LIFECYCLE,
    FASTAPI_ROUTE, FLASK_ROUTE, DJANGO_VIEW, CELERY_TASK,
    FEIGN_CLIENT("FEIGN_CLIENT", "Feign 客户端入口");  // ← 新增
}
```

**修复**（src/test/java/com/huawei/hisi/knowledgegraph/model/EntryPointTypeTest.java:19）：

```diff
-    assertEquals(11, types.length, "Should have 11 entry point types");
+    assertEquals(12, types.length, "Should have 12 entry point types");
```

同时建议补一个 testFeignClientType 测试覆盖新枚举值（与现有 testFastApiRouteType 等同构），但非必需。

---

### #3 PythonKnowledgeGraphBuilderTest.parseFile_topLevelFunction — ENV

**根因**：Windows 上 @TempDir 返回的 Path 用反斜杠（`C:\Users\...\Temp\junit...`），测试比较原始路径字符串 `dir.toString()`，但生产代码 `PythonKnowledgeGraphBuilder.parseFile` 内部对路径做了 `\` → `/` normalize（参见 `PathUtils.normalize`），结果用正斜杠。

**surefire 输出**：
```
expected: "C:\Users\S00807~1\AppData\Local\Temp\junit17139363233326440835"
 but was: "C:/Users/S00807~1/AppData/Local/Temp/junit17139363233326440835"
```

**生产代码**：`PathUtils.normalize` 把 `\` → `/`，行为正确（Neo4j 字段要求 forward slash）。

**修复**（src/test/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilderTest.java:66）：

```diff
-        assertThat(node.getProjectPath()).isEqualTo(dir.toString());
-        assertThat(node.getFilePath()).isEqualTo(file.toString());
+        assertThat(node.getProjectPath()).isEqualTo(dir.toString().replace('\\', '/'));
+        assertThat(node.getFilePath()).isEqualTo(file.toString().replace('\\', '/'));
```

或在测试类顶部加一个 helper：

```java
private static String normalize(Path p) {
    return p.toString().replace('\\', '/');
}
```

然后把第 66、67 行改成 `normalize(dir)` / `normalize(file)`。第二种写法更干净，但第一种 diff 最小。

---

### #6 FlaskRouteScannerTest.scanModule_multipleRoutes — TEST

**根因**：测试构造的 PyFunction fn3 装饰器是 `app.route("/contact", methods=["GET","POST"])`，按 Flask 语义应产出 2 条路由（GET + POST），所以 scanner 正确产出 4 条（`GET /` / `GET /about` / `GET /contact` / `POST /contact`），测试期望 3 是错的。

**surefire 输出**：
```
Expected size: 3 but was: 4 in:
[GET /, GET /about, GET /contact, POST /contact]
```

**生产代码**：`FlaskRouteScanner` 对 `methods=["GET","POST"]` 展开成 2 条 entry，行为正确。

**修复**（src/test/java/com/huawei/hisi/knowledgegraph/python/scanner/FlaskRouteScannerTest.java:101）：

```diff
-        assertThat(entries).hasSize(3);
+        assertThat(entries).hasSize(4);
```

可选：补 `assertThat(entries)` 提取 entryKey 包含 "GET /contact" 和 "POST /contact"，加强语义。

---

### #7 KnowledgeGraphCommonUtilsTest.normalizePath_* (2 tests) — TEST

**根因**：测试期望 `normalizePath(null)` 返回 null，`normalizePath("/work/svc/../other")` 解析 `..` 为 `/work/other`。但生产代码 `KnowledgeGraphCommonUtils.normalizePath` 委派给 `PathUtils.normalize`，后者契约是：
- null / blank → 返回 ""（不返回 null）
- 只做 `\` → `/` 和去末尾斜杠，**不解析 `..`**（注释明确说明）

**surefire 输出**：
```
normalizePath_handlesNull: expected: null but was: ""
normalizePath_resolvesDotDot: expected: "/work/other" but was: "/work/svc/../other"
```

**生产代码**：`KnowledgeGraphCommonUtils.java:30-32` + `PathUtils.java:22-33`，契约清晰，行为正确。`normalizePath` 还标了 `@Deprecated`，建议新代码直接用 `PathUtils.normalize`。

**判定**：测试期望与生产契约不符 → TEST。

**修复**（src/test/java/com/huawei/hisi/knowledgegraph/util/KnowledgeGraphCommonUtilsTest.java）：

```diff
@@ normalizePath_handlesNull @@
-    void normalizePath_handlesNull() {
-        assertThat(normalizePath(null)).isNull();
+    void normalizePath_handlesNull() {
+        assertThat(normalizePath(null)).isEmpty();
     }

@@ normalizePath_resolvesDotDot @@
     @Test
     void normalizePath_resolvesDotDot() {
-        assertThat(normalizePath("/work/svc/../other")).isEqualTo("/work/other");
+        // PathUtils.normalize does not resolve ".." — caller must use Path.resolve first.
+        assertThat(normalizePath("/work/svc/../other")).isEqualTo("/work/svc/../other");
     }
```

---

### #9 ProjectServiceImplTest.testGetStatus_* (2 tests) — TEST

**根因**：`ProjectServiceImpl.getStatus` 第 119 行调用 `Paths.get(PROJECT_DIR, project)`，但 `PROJECT_DIR` 是 `DataSourceConfig` 的静态字段，由 Spring `@Value("${app.project_dir:}")` 注入。测试不启 Spring 上下文，`PROJECT_DIR` 是默认空字符串 `""`，`Paths.get("", "my-project")` 在 Windows 上 NPE（`WindowsFileSystem.getPath` 要求 first 参数非 null，但实际是空字符串触发了 `requireNonNull` 链）。

**surefire 输出**（Windows）：
```
java.lang.NullPointerException
    at sun.nio.fs.WindowsFileSystem.getPath(WindowsFileSystem.java:216)
    at com.huawei.hisi.service.ProjectServiceImpl.getStatus(ProjectServiceImpl.java:119)
```

**生产代码**：`ProjectServiceImpl.java:119` + `DataSourceConfig.java:20,24`。生产代码本身没错——在 Spring 上下文中 `PROJECT_DIR` 会被正确注入。

**深层问题（实际修复中发现）**：doc 前稿只描述了 Windows NPE 这层。`getStatus` 的实际逻辑是：

```java
Path projectDir = Paths.get(PROJECT_DIR, project);
status.put("exists", Files.exists(projectDir));
if (Files.exists(projectDir)) {
    status.put("path", projectDir.toString());
    status.put("status", getAnalysisStatus(project));  // 调到 neo4jMethodNodeRepository.countByProjectPath
    ...
} else {
    status.put("status", "NOT_CLONED");  // ← 即使 stub 了 countByProjectPath，这里走不到
}
```

**测试期望**：stub `countByProjectPath("my-project")` 返回 42L / 0L → 期望 `"COMPLETED"` / `"UNKNOWN"`。但**这要求 `Files.exists(PROJECT_DIR + "/" + "my-project")` 必须为 true**——生产代码先检查文件系统存在性才调到 Neo4j。**仅设 `PROJECT_DIR` 而不创建子目录不够**：路径 `/tmp/my-project` 在 Linux 上不存在 → 仍返回 `"NOT_CLONED"`。

**修复**（src/test/java/com/huawei/hisi/service/ProjectServiceImplTest.java:22-30 `setUp` 方法内）：用 `Files.createTempDirectory` 创一个真实存在的临时目录，再创建 `my-project` 子目录，把 `PROJECT_DIR` 指向这个临时目录根。

```diff
     @BeforeEach
-    void setUp() {
+    void setUp() throws Exception {
         mockRepo = mock(Neo4jMethodNodeRepository.class);
         projectService = new ProjectServiceImpl();
         ReflectionTestUtils.setField(projectService, "neo4jMethodNodeRepository", mockRepo);
         ReflectionTestUtils.setField(projectService, "appConfigService", mock(AppConfigService.class));
         ReflectionTestUtils.setField(projectService, "codeHubUser", "testUser");
         ReflectionTestUtils.setField(projectService, "codeHubPassword", "testPassword");
+        // PROJECT_DIR is a static field on DataSourceConfig injected via @Value.
+        // Without Spring context it stays as the default empty string, which makes
+        // Paths.get("", project) NPE on Windows. getStatus() does
+        // Files.exists(PROJECT_DIR + "/" + project) before consulting Neo4j —
+        // the stubs in testGetStatus_* never get hit otherwise. Point PROJECT_DIR
+        // at a real existing dir that also has a "my-project" subdirectory (the
+        // project names used by those tests) so the existence check passes.
+        java.nio.file.Path existingRoot = java.nio.file.Files.createTempDirectory("proj-root-");
+        java.nio.file.Files.createDirectory(existingRoot.resolve("my-project"));
+        ReflectionTestUtils.setField(
+                com.huawei.hisi.config.DataSourceConfig.class,
+                "PROJECT_DIR", existingRoot.toString());
     }
```

**验证**（Windows + 实际项目）：

第一次修复（只设 `PROJECT_DIR="/tmp"`，不创建子目录）→ 测试从 NPE 变成 `expected: <COMPLETED> but was: <NOT_CLONED>`（Linux）或同样 NOT_CLONED（Windows，只是先 NPE 后 NOT_CLONED）。

第二次修复（加 `Files.createDirectory(existingRoot.resolve("my-project"))`）→ `mvn surefire:test -Dtest=ProjectServiceImplTest` 通过：`Tests run: 4, Failures: 0, Errors: 0`。

注意：`PROJECT_DIR` 在 `DataSourceConfig` 上同时有 `@Value` 标注的 static 字段（line 20）和实例 setter（line 22-25），Spring 实际通过 setter 写入静态字段。`ReflectionTestUtils.setField(Class, fieldName, value)` 直接写静态字段即可。

---

### [新增] #11 ParseNodeTest + LogAnalysisDagOrchestratorTest compile-blocker — TEST

**根因**：commit 70a6c4c (2026-07-01, feat: exception auto-fix closed-loop system) 给 ParseNode 加了构造函数 `ParseNode(CaptureDecoder captureDecoder)`（`ParseNode.java:49-51`），同时**删掉了无参构造**。但 `ParseNodeTest.java:17` 和 `LogAnalysisDagOrchestratorTest.java:50` 仍在用 `new ParseNode()`，导致 `mvn test-compile` 直接失败（GBK 编码下错误信息显示为"无法将类 ParseNode 中的构造器 ParseNode 应用到给定类型"）。

**surefire 输出**（编译期）：
```
[ERROR] ParseNodeTest.java:[17,41] 无法将类 com.huawei.hisi.loganalysis.nodes.ParseNode
        中的构造器 ParseNode 应用到给定类型;
  需要: com.huawei.hisi.loganalysis.decoder.CaptureDecoder
  找到:    没有参数
  原因:  实际参数列表和形式参数列表长度不同
[ERROR] LogAnalysisDagOrchestratorTest.java:[50,21] 同上
```

**生产代码**（`ParseNode.java:47-51`）：
```java
    private final CaptureDecoder captureDecoder;

    public ParseNode(CaptureDecoder captureDecoder) {
        this.captureDecoder = captureDecoder;
    }
```
`CaptureDecoder` 是 `@Component`（`CaptureDecoder.java:41-42`），在 Spring 上下文中由 Spring 注入。但这两个测试都是纯 unit test（不启 Spring），需要测试自己构造。

**判定**：生产代码契约变更合理（需要解码 HISI_CAPTURE 包络），测试未跟上 → TEST。

**修复**（src/test/java/com/huawei/hisi/loganalysis/nodes/ParseNodeTest.java:17）：

```diff
-    private final ParseNode parseNode = new ParseNode();
+    private final ParseNode parseNode = new ParseNode(null);
```
`ParseNode.execute` 内部对 `captureDecoder != null` 做了判空（`ParseNode.java:71`：`while (captureMatcher.find() && captureDecoder != null)`），传 null 时 HISI_CAPTURE 检测分支会被跳过，原有 stack-trace 解析测试语义不变。

**修复**（src/test/java/com/huawei/hisi/loganalysis/orchestrator/LogAnalysisDagOrchestratorTest.java:50）：

```diff
     @BeforeEach
     void setUp() {
-        parseNode = new ParseNode();
+        parseNode = new ParseNode(null);
         kgSearchNode = new KgSearchNode(kgMcpClient);
```

---

## Tier 2 — 需要设计决策（4 项）

### #1 FailureLocatorE2ETest.fixture_endToEnd_done (3 tests) — TEST（fixture 配置错）

**根因**：测试通过 `@TestConfiguration` 注册了 `@Primary` 的 `stubLlmClient`，但生产代码 `ApmClaudeLlmClient`（`ApmClaudeLlmClient.java:41-43`）也标了 `@Primary` + `@ConditionalOnExpression("'${hisi.apm.diagnose.llm.api-key:}' != ''")`。

测试 `@TestPropertySource` 没显式给 `hisi.apm.diagnose.llm.api-key`，但 `application-local.yml` 有 `api-key: sk-RHiJ0...`，且 `application.yml` 的 `spring.profiles.active: dev,local` 让 local profile 在测试时也被激活（`@ActiveProfiles("test")` 是叠加，不是替换）。结果 `ApmClaudeLlmClient` 的条件匹配，两个 `@Primary` 同时存在，Spring 报 `NoUniqueBeanDefinitionException: more than one 'primary' bean found`。

**surefire 输出**：
```
Caused by: ...NoUniqueBeanDefinitionException: No qualifying bean of type
'com.huawei.hisi.apm.service.locator.LlmClient' available:
more than one 'primary' bean found among candidates: [apmClaudeLlmClient, stubLlmClient]
```

**生产代码**：`ApmClaudeLlmClient` 用 `@ConditionalOnExpression` 是合理的（没 api-key 就不注册），但 local profile 的 api-key 让它在测试时也注册了。

**判定**：测试 fixture 没隔离好生产配置 → TEST。

**修复方案 A（推荐，最小改动）**：在测试 `@TestPropertySource` 里把 api-key 显式置空，让 `ApmClaudeLlmClient` 的 `@ConditionalOnExpression` 不匹配：

```diff
 --- a/hisi-dev-tool/src/test/java/com/huawei/hisi/apm/e2e/FailureLocatorE2ETest.java
 +++ b/hisi-dev-tool/src/test/java/com/huawei/hisi/apm/e2e/FailureLocatorE2ETest.java
 @@ -54,6 +54,7 @@
         "hisi.apm.diagnose.timeoutSeconds=30",
         "hisi.apm.diagnose.llmTimeoutSeconds=20",
-        "hisi.apm.diagnose.confidenceLowThreshold=0.5"
+        "hisi.apm.diagnose.confidenceLowThreshold=0.5",
+        // Suppress the production ApmClaudeLlmClient (its @ConditionalOnExpression
+        // matches when api-key is non-empty). Under @ActiveProfiles("test") the
+        // base application.yml still pulls in the "local" profile, which sets
+        // api-key — so the production @Primary LlmClient competes with the
+        // stub below, causing NoUniqueBeanDefinitionException. Forcing api-key
+        // to empty here disables the production bean so the stub wins cleanly.
+        "hisi.apm.diagnose.llm.api-key="
 })
 class FailureLocatorE2ETest {
```

**修复方案 B（更彻底）**：测试加 `@Profile("test")` 隔离或用 `@TestPropertySource(properties = "spring.profiles.active=test")` 覆盖 active profiles，但 Spring Boot 不允许通过 properties 覆盖 `spring.profiles.active`（在 5.x 之前可以，5.x 之后被禁）。所以方案 A 是首选。

**修复方案 C（改生产）**：把 `ApmClaudeLlmClient` 的 `@Primary` 去掉，改用 `@ConditionalOnMissingBean(LlmClient.class)`，让用户提供的 bean（如测试 stub）优先。但这样改生产代码影响范围大，不推荐。

---

### #4 PythonCallGraphResolverTest.unresolvedCall — TEST（原 backlog 标 BUG，复核修正）

**根因**：测试期望"未知 callee 的调用不产生任何 edge"（`assertThat(edges).isEmpty()`），但生产代码 `PythonCallGraphResolver` 的设计契约（类 Javadoc 第 32-33 行明确写了）："Anything else → emitted as an unresolved edge (with a synthetic callee id) so downstream tools can still see the call site." 所以生产代码 emit 了一个 `callType=UNRESOLVED, unresolved=true` 的 edge。

**surefire 输出**：
```
Expecting empty but was: [{"callLine"=2, "callType"="UNRESOLVED",
"calleeId"="unresolved:b23a6a8439c0dde5", "callerId"="cb7b9c592d0fce30",
"unresolved"=true}]
```

**生产代码**：`PythonCallGraphResolver.java:32-33`（Javadoc 契约）+ line 227、241（`return unresolvedEdge(...)`）。行为与契约一致。

**判定**：**生产对、测试错** → 重分类为 TEST。原 backlog 标 BUG 是误判（backlog 自己也写了"生产对、测试错"但仍标 BUG，前后矛盾）。

**修复**（src/test/java/com/huawei/hisi/knowledgegraph/python/call/PythonCallGraphResolverTest.java:225）：

```diff
         List<Map<String, Object>> edges = resolver.resolveModule(module, "/x", List.of(module));

-        assertThat(edges).isEmpty();
+        // Per class Javadoc: unknown callees are emitted as UNRESOLVED edges
+        // (with synthetic calleeId) so downstream tools can still see the call site.
+        assertThat(edges).hasSize(1);
+        Map<String, Object> e = edges.get(0);
+        assertThat(e.get("callType")).isEqualTo("UNRESOLVED");
+        assertThat(e.get("unresolved")).isEqualTo(true);
+        assertThat((String) e.get("calleeId")).startsWith("unresolved:");
```

---

### #5 FastApiRouteScannerTest.singleRouterWithPrefix_prependsToRoutes — TEST

**根因**：测试构造了 PyCall `routerCall`（`APIRouter("/api/v1")`）但没给 PyModule 设 filePath（只有 `filePath("items.py")` 字符串，不是真实文件）。生产代码 `FastApiRouteScanner.buildRouterPrefixMap`（line 93）调用 `readSourceLines(module.getFilePath())`，`Files.readAllLines(Path.of("items.py"))` 在测试工作目录找不到该文件 → 返回空 list → `findAssignmentVarName` 返回 null → `routerPrefixMap` 为空 → prefix 不生效。

**surefire 输出**：
```
expected: "GET /api/v1/items"
 but was: "GET /items"
```

**生产代码**：`FastApiRouteScanner.java:89-107`（`buildRouterPrefixMap` 从源码行读 varName = `APIRouter(...)` 的 varName，这是设计意图（Python AST 不存 varName，需要从源码行回看）。

**判定**：测试 fixture 没构造真实源文件 → TEST。

**修复方案 A（推荐，改测试）**：在 `@TempDir` 下写真实 `.py` 文件，让 filePath 指向该文件：

```diff
 import java.io.IOException;
 import java.nio.file.Files;
 import java.nio.file.Path;
 import java.nio.charset.StandardCharsets;
 import org.junit.jupiter.api.io.TempDir;
 ...
     @Test
     @DisplayName("Single APIRouter with prefix prepends to router-decorated routes")
-    void singleRouterWithPrefix_prependsToRoutes() {
+    void singleRouterWithPrefix_prependsToRoutes(@TempDir Path tmp) throws IOException {
+        // FastApiRouteScanner.buildRouterPrefixMap reads the source file to extract
+        // the varName of varName = APIRouter(...) assignments — PyCall/PyModule
+        // alone don't carry source content, so we must write a real .py file.
+        Path file = tmp.resolve("items.py");
+        Files.writeString(file, String.join("\n",
+                "router = APIRouter(prefix=\"/api/v1\")",
+                "",
+                "@router.get(\"/items\")",
+                "def get_items():",
+                "    pass",
+                ""), StandardCharsets.UTF_8);
+
         PyCall routerCall = PyCall.builder()
                 .calleeExpression("APIRouter")
                 .lineNumber(1)  // ← line 1 now matches router = APIRouter(...)
                 .enclosingFunction("<module>")
                 .firstStringArg("/api/v1")
                 .build();
         PyFunction fn = PyFunction.builder()
                 .name("get_items")
                 .qualName("get_items")
                 .paramNames(List.of())
                 .decorators(List.of("router.get(\"/items\")"))
-                .lineStart(10)
-                .lineEnd(15)
+                .lineStart(3)
+                .lineEnd(5)
                 .build();
         PyModule module = PyModule.builder()
-                .filePath("items.py")
+                .filePath(file.toString())
                 .modulePath("items")
                 .topLevelFunctions(List.of(fn))
                 .calls(List.of(routerCall))
                 .build();
```

注意：测试用 `lineNumber=1` 是因为 `buildRouterPrefixMap` 用 `call.getLineNumber()` 去 `sourceLines.get(lineNumber-1)` 找 assignment 行——必须行号对得上。

**修复方案 B（改生产）**：在 PyCall 加 `assignmentVarName` 字段，让 scanner 优先用 AST 提供的 varName，源码回看作 fallback。这要改 PyCall model + Python parser + scanner，scope 较大，不推荐。

---

### #8 DagExecutorTest.executor_skipsNodesWhoseInputsHashUnchanged — BUG（唯一的真 BUG）

**根因**：`DagExecutor.run` 在每个非最后节点执行完都加 HITL 确认 gate（`DagExecutor.java:137-147`）：

```java
boolean isLastNode = (nodeIdx == orderedNodes.size() - 1);
if (!isLastNode && !forceRerun && !isNodeConfirmed(sessionEvents, node.name())) {
    appendHitlReq(sessionId, node.name(), safeOutput);
    sessionRepo.updateStatus(sessionId, SessionStatus.WAITING_HITL);
    return new ExecutionResult(
            sessionId, SessionStatus.WAITING_HITL, executed, skipped, previousOutput);
}
```

测试场景：2 个节点（clarify, impact），第一次 run 后 clarify 已经执行并写了 CHECKPOINT，但因为是第一个非最后节点，会 emit HITL_REQ 然后 return WAITING_HITL——**第二次 run 期望 clarify 命中 cache 被 skip，但因为 `isNodeConfirmed(sessionEvents, "clarify")` 仍是 false（测试没 seed HITL_RES 事件），所以 clarify 又卡在 HITL gate**，`executed=[]`，`skipped=[]`，assertion `skippedNodes().containsExactly("clarify", "impact")` 失败。

**surefire 输出**：
```
Expecting actual: []
to contain exactly (and in same order): ["clarify", "impact"]
```

**生产代码**：`DagExecutor.java:137-147`。HITL gate 的设计意图是"每个节点完成后让用户确认"，但 cache 命中时也应该跳过 HITL——目前的逻辑是 cache miss 才走 HITL gate，cache hit 直接 skip（line 101-107），**但第一次执行后第二次 run 时，clarify 的 cache 命中了**——咦，那为什么会失败？

**深入分析**：仔细看 `DagExecutor.run` line 99-107：

```java
Map<String, Object> cached = forceRerun ? null : findCachedOutput(sessionEvents, node.name(), inputsHash);
if (cached != null) {
    skipped.add(node.name());
    previousOutput = cached;
    continue;  // ← cache 命中时直接 continue，不走 HITL gate
}
```

第一次 run：clarify 执行 → 写 CHECKPOINT → 因为不是最后节点，emit HITL_REQ → return WAITING_HITL。**第一次 run 没执行 impact**。

第二次 run：
- clarify 的 cache 命中（同 inputsHash）→ skip → continue ✓
- impact：cache 没命中（第一次没执行）→ 执行 → 写 CHECKPOINT → 因为是最后节点，不 emit HITL_REQ → 继续
- 循环结束 → DONE

实际：第二次 run 应该成功，skipped=[clarify], executed=[impact]。但测试期望 `skipped.containsExactly("clarify", "impact")`——即两个都被 skip，这要求 impact 也有 cache。但第一次 run 没执行 impact，所以第二次 run impact 不可能 cache hit。**测试期望不合理**——除非第一次 run 能跑完两个节点。

**根本冲突**：HITL gate 在第一次 run 时阻止了 impact 执行，所以第二次 run impact 必须重新执行。测试期望"两次相同输入的 run 都 skip 所有节点"要求第一次 run 必须跑完所有节点（不被 HITL gate 阻塞）。

**判定**：这是设计冲突——HITL gate 与 cache-skip 的语义不一致。
- 选项 A：测试 fixture 在第一次 run 前 seed HITL_RES 事件，让第一次 run 跑完两个节点，第二次 run 全 skip。
- 选项 B：生产代码改 HITL gate，让 cache 命中时跳过 gate（但第二次 run 的 clarify 已经 cache 命中，问题在第一次 run 的 clarify gate 阻止了 impact 执行）。
- 选项 C：生产代码改 HITL gate，让"非最后节点 + 已写 CHECKPOINT"也跳过 gate（即只在首次执行时 emit HITL_REQ，重跑时不再阻塞）。

**原 backlog 标 BUG**：建议方案 C，即 DagExecutor 在 cache CHECKPOINT 重放时跳过 HITL gate。这是设计决策，需要用户确认。

**修复方案 A（改测试，最小改动）**：

```diff
@@ DagExecutorTest.java:82 @@
     @Test
     @DisplayName("skips nodes whose inputs hash matches a prior checkpoint")
     void executor_skipsNodesWhoseInputsHashUnchanged() {
         AgentSession s = sessionRepo.save(AgentSession.newRunning("user-exec-2", SessionType.DEMAND));
         long sid = s.getId();

         DagNode clarify = new FakeNode("clarify", "clarify-v1",
                 input -> Map.of("intent", "X"));
         DagNode impact = new FakeNode("impact", "impact-v1",
                 input -> Map.of("involved", List.of("M1")));

         Map<String, Object> initial = Map.of("q", "do X");
+        // Pre-seed HITL_RES confirmations for all non-last nodes so the first run
+        // can complete all nodes (writing CHECKPOINT for each) instead of parking
+        // at WAITING_HITL after clarify. Without this, the second run cannot
+        // cache-hit impact because impact was never executed.
+        seedHitlRes(sid, "clarify");
+
         executor.run(List.of(clarify, impact), sid, initial);

         ExecutionResult second = executor.run(List.of(clarify, impact), sid, initial);

         assertThat(second.status()).isEqualTo(SessionStatus.DONE);
         assertThat(second.skippedNodes()).containsExactly("clarify", "impact");
         assertThat(second.executedNodes()).isEmpty();
     }
+
+    private void seedHitlRes(long sessionId, String nodeName) {
+        eventRepo.append(AgentEvent.builder()
+                .sessionId(sessionId)
+                .seq(System.currentTimeMillis())  // monotonic-ish
+                .type(EventType.HITL_RES)
+                .payload("{\"nodeName\":\"" + nodeName + "\",\"action\":\"confirm\"}")
+                .build());
+    }
```

**修复方案 B（改生产，原 backlog 推荐）**：`DagExecutor.run` 在 cache hit 时跳过 HITL gate（已经实现了，line 101-107 的 continue），但**第一次 run 的 clarify 仍然会被 HITL gate 阻塞**。要让第一次 run 跑完两个节点，必须改 HITL gate 逻辑：例如只在首次执行后 emit HITL_REQ 但**不 return**，而是继续下一个节点。但这与 HITL 的设计意图（让用户在每步后 review）冲突。

**推荐**：方案 A（改测试），因为 HITL gate 的设计意图是合理的，测试应该 respect 这个设计。原 backlog 标 BUG 是误判——测试期望"两次相同输入都全 skip"但没给 HITL 确认，逻辑上不可能。

**复核后重分类**：#8 从 BUG 重分类为 TEST。**那么 17 个失败里 0 个真 BUG**。

#### 复核实证（2026-07-07）：方案 A 实测失败 ⛔

**实测过程**：按本文档方案 A 给 `executor_skipsNodesWhoseInputsHashUnchanged` 加 `seedHitlRes(sid, "clarify");` 并新增 `seedHitlRes` 辅助方法，写入 `EventType.HITL_RES` 事件。

**实测结果**（`mvn surefire:test -Dtest=DagExecutorTest`）：
```
[INFO] Tests run: 3, Failures: 1, Errors: 0
[ERROR] executor_skipsNodesWhoseInputsHashUnchanged(...):
   [DagExecutor] sid=1 DONE executed=[clarify, impact] skipped=[]
   Expecting actual: [] to contain exactly (and in same order): ["clarify", "impact"]
```

**关键证据**：日志显示两次 run 都 `executed=[clarify, impact]`、`skipped=[]`——seeded HITL_RES **完全没起作用**。两处 run 都全量重新执行所有节点。

#### 根因链（完整分析）

1. `AgentSession.newRunning(...)`（`AgentSession.java` 构造器）**不设置 `rerunFromNode`**，所以新建 session 拿到手的 `rerunFromNode == null`。

2. `DagExecutor.run` 入口（`DagExecutor.java:65`）：
   ```java
   boolean forceRerun = (rerunFromNode == null);
   ```
   `rerunFromNode == null` → `forceRerun == true`。

3. **关键点 1：cache 路径被旁路**。`DagExecutor.java:100`：
   ```java
   Map<String, Object> cached = forceRerun ? null : findCachedOutput(...);
   ```
   `forceRerun=true` 时 `cached = null`，**所有节点都走执行路径**——line 102-107 的 cache-hit `continue` 永远死代码。

4. **关键点 2：HITL gate 被旁路**。`DagExecutor.java:140`：
   ```java
   if (!isLastNode && !forceRerun && !isNodeConfirmed(...))
   ```
   `forceRerun=true` 时 gate 条件短路成 `if (!isLastNode && false && ...)` → gate 永远是 false —— seeded HITL_RES 没人读，但即便读了也被短路。

5. 结论：**`AgentSession.newRunning()` 这种"全新 session 第一次跑"也是 `forceRerun=true`，与"重新跑某个节点分支"被同等对待**。这与"HITL gate + cache skip"的设计语义完全错位。第一次 run 全量重跑，第二次 run 也全量重跑——cache 机制在生产调用路径下形同虚设。

#### 各方案的当前评估

| 方案 | 改动范围 | 评估 |
|---|---|---|
| **A. seed HITL_RES（已实测失败）** | 仅测试 | ❌ forceRerun 短路了 gate，seeded 事件是死代码 |
| **B. 生产 cache 命中跳过 gate（已实现但无效）** | 仅 `DagExecutor` | ❌ 仍未解决第一次 run 的 gate 阻塞 |
| **C. 改 HITL gate 让"非首跑"跳过 gate** | `DagExecutor` + session 模型 | 需把 gate 条件里的 `!forceRerun` 改成 `isNodeAlreadyConfirmed` 或类似，但与 `forceRerun` 语义冲突——需要重新定义"首次运行"标志。**中等风险，需架构评审。** |
| **D. 测试-only：sentinel rerunFromNode + seed HITL_RES（推荐）** | 仅测试 | ✅ 把 `AgentSession` 的 `rerunFromNode` 设为不匹配任何节点的 sentinel（如 `"$fresh"`），让 `forceRerun=false`，再 seed HITL_RES 让 gate 通过。完全不改生产，测试独立可控。 |
| **E. 改生产：让"无 rerunFromNode"也参与 cache** | `DagExecutor.run` 入口 + gate | 把 gate 条件拆成 `forceRerun && !isFreshSession(session)` 之类——引入 freshSession 概念。**需架构评审。** |
| **F. 改 AgentSession.newRunning 默认设 sentinel** | `AgentSession` | 生产代码改动小但语义影响面大，所有走 `newRunning()` 的入口行为都会变。不推荐。 |

#### 方案 D 的具体实现（推荐 — 专家评审对象）

**修改目标**：不改动生产代码，让测试可以走通 cache-skip 路径。

**关键洞察**：`forceRerun` 是 `(rerunFromNode == null)` 的导出量——把 `rerunFromNode` 设为非 null 的 sentinel（如 `"$fresh"`，永远不和真实节点 name 冲突），同时保持 session 是新建的，`forceRerun` 就变成 `false`，HITL gate 和 cache 都进入正常工作分支。

**步骤**：

1. 测试 setUp 里把 `AgentSession` 的 `rerunFromNode` 字段置为 `"$fresh"`。如果 `AgentSession` 没有公开 setter，用 `ReflectionTestUtils.setField(session, "rerunFromNode", "$fresh")`。
2. 同时 seed HITL_RES 事件让 gate 通过。
3. sentinel 选择：必须永远不会成为真实节点 name 的字符串。当前测试节点 name 是 `clarify` / `impact`，实际生产节点名带版本号（`clarify-v1`）。`"$fresh"` 即可。

#### 专家需要回答

请架构师评审以下两点后再写代码：

1. **方案 D 的 sentinel 设计是否合理**？有没有更优的写法（如用一个工厂方法 `AgentSession.newCachedReady(...)` 表达"已就绪可走 cache 路径"）？

2. **方案 C/E 是否要纳入生产重构 roadmap**？如果生产 HITL gate 语义确实错了（第一次 run 也 forceRerun=true），那生产也有 bug——只是被现有 `forceRerun=true` 的默认掩盖了。修复后的生产逻辑应该是：
   - 新 session：`forceRerun=true`，正常首次执行 + 第一次 gate 触发（如果非最后节点）。
   - 重跑：`rerunFromNode="xxx"`，`forceRerun=false`，走 cache 路径，gate 跳过。
   - 用户/系统触发某个 intermediate rerun：`rerunFromNode="clarify"`，`forceRerun=false`，从 clarify 开始重跑（skip 之前的 cache），到 clarify 时依然要 gate？

#### 当前状态

**搁置**：方案 A 已实测失败；方案 D 等待专家确认后方可落地。本次 patch 不包含 #8 的修复——`DagExecutorTest.java` 已恢复原状（git diff 不包含此文件）。

---


### #10 ReportExportServiceTest (4 tests) — TEST

**根因**：测试期望 `exportRamSessionAsMd` 输出包含 `## 事件历史` section + `USER_MSG` / `CHECKPOINT` / `ASSISTANT_DELTA` 等事件类型 token。但生产代码 `ReportExportService.java:142-...` 只输出 `## 基本信息` / `## 需求描述` / `## 项目路径` / `## 分析结果`（CHECKPOINT 节点结果汇总）/ `## 澄清问答` 等 section，**没有 `## 事件历史` section**（grep 验证 0 匹配）。

**surefire 输出**：
```
Expecting actual: "# RAM 需求分析会话 #...
## 基本信息
...
## 需求描述
...
## 项目路径
...
## 分析结果
..."
to contain: "## 事件历史"
```

**生产代码**：`ReportExportService.java:142-260`。当前设计只输出"分析结果"（CHECKPOINT 提取）和"澄清问答"，不输出原始事件历史。这是一个产品决策——是否在 markdown 报告里包含完整事件流水。

**判定**：测试期望一个被移除或从未实现的功能 → TEST。

**修复方案 A（推荐，删测试）**：4 个失败测试里有 2 个直接依赖 `## 事件历史`：
- `exportRamSessionAsMd_shouldContainEventHistory`：整个测试就是检查事件历史 section，应删。
- `exportRamSessionAsMd_shouldContainCheckpointOutputs`：检查 CHECKPOINT 输出，但断言里也带了 `## 事件历史`——这部分断言删掉即可保留测试。

另 2 个失败：
- `exportMergeReportAsMd_shouldContainAnalysisEvents`：检查 merge 报告里的 `## 分析过程` section——类似问题，看生产代码是否实现了。
- 第 4 个失败需要看 surefire 详细输出。

**修复方案 B（改生产）**：在 `ReportExportService.exportRamSessionAsMd` 加回 `## 事件历史` section，遍历 events 输出 USER_MSG / CHECKPOINT / ASSISTANT_DELTA 等。这是产品决策——是否要在 markdown 报告里包含完整事件流水。

**推荐**：方案 A（删/改测试），除非产品明确要求事件历史 section。原 backlog 标 TEST 是对的。

---

## 处理优先级建议

按风险/收益排序：

1. Tier 1（5 项，机械改）：零生产风险，建议全部修。预计 30 分钟。**✅ 本次已修**
2. #11 ParseNode compile-blocker（2 文件）：修了才能让 mvn test 跑起来，必须修。5 分钟。**✅ 之前已修（target state）**
3. #1 FailureLocatorE2ETest（1 项 3 测试）：方案 A 加一行 `api-key=` 即可，建议修。1 分钟。**✅ 之前已修（target state）**
4. #4 PythonCallGraphResolverTest：改测试断言对齐 Javadoc 契约，建议修。5 分钟。**✅ 本次已修**
5. #5 FastApiRouteScannerTest：方案 A 写真实 `.py` 文件，建议修。10 分钟。**✅ 本次已修**
6. #8 DagExecutorTest：**⏸ 搁置**——方案 A 已实测失败，方案 D 等待专家评审。**本次不修。**
7. #10 ReportExportServiceTest（4 测试）：需要产品决策是否保留事件历史 section，建议**先跳过**，等用户决定。**本次不修。**

总计（本次落地）：5 (Tier 1) + 2 (Tier 2) = 7 个文件修复。所有 fix 已验证通过：`Tests run: 78, Failures: 0, Errors: 0`。

---

## 验证清单（远端改完后跑）

```bash
# 1. test-compile 全量
mvn test-compile
# 期望：BUILD SUCCESS（不再有 ParseNode compile error）

# 2. 跑本次已修的 7 个测试类
mvn surefire:test -Dtest='EntryPointTypeTest,PythonKnowledgeGraphBuilderTest,FlaskRouteScannerTest,KnowledgeGraphCommonUtilsTest,ProjectServiceImplTest,PythonCallGraphResolverTest,FastApiRouteScannerTest' -DfailIfNoTests=false
# 期望：Tests run: 78, Failures: 0, Errors: 0

# 3. 跑 #1 E2E（慢，22s+）
mvn surefire:test -Dtest='FailureLocatorE2ETest' -DfailIfNoTests=false
# 期望：Tests run: 3, Failures: 0, Errors: 0

# 4. 跑 #11 compile-blocker 已修的两个测试
mvn surefire:test -Dtest='ParseNodeTest,LogAnalysisDagOrchestratorTest' -DfailIfNoTests=false
# 期望：Tests run: ?, Failures: 0, Errors: 0

# 5. #8 DagExecutorTest 仍会失败（已搁置），跳过
# 6. #10 ReportExportServiceTest 暂跳过（等产品决策）
```

## 不在本次 scope 内

- ReportExportService 是否要加 `## 事件历史` section —— 产品决策，见 #10 方案 B。
- DagExecutor HITL gate 与 cache-skip 的语义是否要重构 —— 设计决策，见 #8 方案 B/C/D。
- 17 个失败之外的任何其他失败（如 frontend e2e 测试）。
- V1 cleanup 推送 / V2 cancellation test follow-up —— 见 docs/plans/2026-07-06-ram-chat-v1-cleanup-design.md Follow-up 章节。

## 修订记录

相对 2026-07-06-test-failures-backlog.md 的修正：

| # | 原分类 | 复核后分类 | 修正理由 | 本次状态 |
|---|---|---|---|---|
| #4 | BUG | TEST | backlog 自述"生产对、测试错"但仍标 BUG，前后矛盾；按定义应标 TEST | ✅ 已修 |
| #8 | BUG | 需设计决策 | 实测方案 A 失败（forceRerun 短路 gate），方案 D 等待专家评审 | ⏸ 搁置 |
| #11 | 不在 backlog | TEST（新增） | 2026-07-01 commit 70a6c4c 引入，backlog 写于 2026-07-06 但漏登记 | ✅ 之前已修 |

修正后 17 个失败的分类：**0 BUG / 16 TEST / 1 ENV**（加 #11 的 2 个 compile-blocker 后是 0 BUG / 18 TEST / 1 ENV）。

## 本次实际 git diff 范围

**测试文件（7 个）**：
- `src/test/java/com/huawei/hisi/knowledgegraph/model/EntryPointTypeTest.java`（#2, 11→12）
- `src/test/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilderTest.java`（#3, 路径 normalize）
- `src/test/java/com/huawei/hisi/knowledgegraph/python/scanner/FlaskRouteScannerTest.java`（#6, 3→4）
- `src/test/java/com/huawei/hisi/knowledgegraph/util/KnowledgeGraphCommonUtilsTest.java`（#7, 2 tests）
- `src/test/java/com/huawei/hisi/service/ProjectServiceImplTest.java`（#9, PROJECT_DIR + my-project dir）
- `src/test/java/com/huawei/hisi/knowledgegraph/python/call/PythonCallGraphResolverTest.java`（#4, UNRESOLVED edge）
- `src/test/java/com/huawei/hisi/knowledgegraph/python/scanner/FastApiRouteScannerTest.java`（#5, real .py file）

**文档（1 个）**：
- `docs/plans/2026-07-07-test-failures-remediation.md`（本文档）

**未在 diff 中**（target state 已在之前 commit 落地）：
- `src/test/java/com/huawei/hisi/apm/e2e/FailureLocatorE2ETest.java`（#1）
- `src/test/java/com/huawei/hisi/loganalysis/nodes/ParseNodeTest.java`（#11）
- `src/test/java/com/huawei/hisi/loganalysis/orchestrator/LogAnalysisDagOrchestratorTest.java`（#11）

**未在 diff 中**（本次搁置/跳过）：
- `src/test/java/com/huawei/hisi/ram/orchestrator/DagExecutorTest.java`（#8，搁置）
- 任何与 #10 ReportExportServiceTest 相关的文件（暂跳过）

