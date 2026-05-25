package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;

import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FastApiRouteScanner}.
 */
@DisplayName("FastApiRouteScanner Tests")
class FastApiRouteScannerTest {

    private static final String PROJECT_PATH = "/home/projects/myapp";

    private FastApiRouteScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new FastApiRouteScanner();
    }

    @Test
    @DisplayName("scanModule finds app.get route")
    void scanModule_findsAppGetRoute() {
        PyFunction fn = PyFunction.builder()
                .name("list_users")
                .decorators(List.of("app.get(\"/users\")"))
                .lineStart(10)
                .lineEnd(15)
                .build();
        PyModule module = PyModule.builder()
                .filePath("main.py")
                .modulePath("main")
                .topLevelFunctions(List.of(fn))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        EntryPointNode entry = entries.get(0);
        assertThat(entry.getEntryKey()).isEqualTo("GET /users");
        assertThat(entry.getEntryType()).isEqualTo(EntryPointNode.TYPE_HTTP);
        assertThat(entry.getLanguage()).isEqualTo("python");
        assertThat(entry.getFramework()).isEqualTo("fastapi");
        assertThat(entry.getEntryId()).hasSize(16);
        assertThat(entry.getEntryInfo()).contains("\"httpMethod\":\"GET\"");
        assertThat(entry.getEntryInfo()).contains("\"url\":\"/users\"");
    }

    @Test
    @DisplayName("scanModule finds router.post route")
    void scanModule_findsRouterPost() {
        PyFunction fn = PyFunction.builder()
                .name("create_item")
                .decorators(List.of("router.post(\"/items\")"))
                .lineStart(20)
                .lineEnd(30)
                .build();
        PyModule module = PyModule.builder()
                .filePath("items.py")
                .modulePath("items")
                .topLevelFunctions(List.of(fn))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).isEqualTo("POST /items");
    }

    @Test
    @DisplayName("scanModule ignores non-route decorators")
    void scanModule_ignoresNonRouteDecorators() {
        PyFunction fn = PyFunction.builder()
                .name("helper")
                .decorators(List.of("staticmethod", "lru_cache()"))
                .lineStart(5)
                .lineEnd(10)
                .build();
        PyModule module = PyModule.builder()
                .filePath("utils.py")
                .modulePath("utils")
                .topLevelFunctions(List.of(fn))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("scanModule extracts path parameters")
    void scanModule_pathParameter() {
        PyFunction fn = PyFunction.builder()
                .name("get_user")
                .decorators(List.of("app.get(\"/users/{id}\")"))
                .lineStart(1)
                .lineEnd(5)
                .build();
        PyModule module = PyModule.builder()
                .filePath("main.py")
                .modulePath("main")
                .topLevelFunctions(List.of(fn))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryInfo()).contains("\"/users/{id}\"");
    }

    @Test
    @DisplayName("scanModule finds multiple routes in same module")
    void scanModule_multipleRoutes() {
        PyFunction fn1 = PyFunction.builder()
                .name("list_users").decorators(List.of("app.get(\"/users\")"))
                .lineStart(1).lineEnd(5).build();
        PyFunction fn2 = PyFunction.builder()
                .name("create_user").decorators(List.of("app.post(\"/users\")"))
                .lineStart(7).lineEnd(12).build();
        PyFunction fn3 = PyFunction.builder()
                .name("delete_user").decorators(List.of("app.delete(\"/users/{id}\")"))
                .lineStart(14).lineEnd(20).build();
        PyModule module = PyModule.builder()
                .filePath("main.py")
                .modulePath("main")
                .topLevelFunctions(List.of(fn1, fn2, fn3))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("scanModule finds route on class method with handlerClass=class name")
    void scanModule_methodInsideClass() {
        PyFunction method = PyFunction.builder()
                .name("get_x")
                .decorators(List.of("app.get(\"/x\")"))
                .lineStart(5)
                .lineEnd(10)
                .isMethod(true)
                .enclosingClass("UserController")
                .build();
        PyClass clazz = PyClass.builder()
                .name("UserController")
                .methods(List.of(method))
                .lineStart(3)
                .lineEnd(15)
                .build();
        PyModule module = PyModule.builder()
                .filePath("controller.py")
                .modulePath("controller")
                .classes(List.of(clazz))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryInfo()).contains("\"handlerClass\":\"UserController\"");
    }

    @Test
    @DisplayName("scanModule uses projectPath on entry points")
    void scanModule_projectPathSet() {
        PyFunction fn = PyFunction.builder()
                .name("hello")
                .decorators(List.of("app.get(\"/hello\")"))
                .lineStart(1)
                .lineEnd(3)
                .build();
        PyModule module = PyModule.builder()
                .filePath("main.py")
                .modulePath("main")
                .topLevelFunctions(List.of(fn))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getProjectPath()).isEqualTo(PROJECT_PATH);
    }

    @Test
    @DisplayName("methodNodeId computed from handler function qualName + params")
    void scanModule_setsMethodNodeId() {
        PyFunction fn = PyFunction.builder()
                .name("list_users")
                .qualName("list_users")
                .paramNames(List.of("skip", "limit"))
                .decorators(List.of("app.get(\"/users\")"))
                .lineStart(10)
                .lineEnd(15)
                .build();
        PyModule module = PyModule.builder()
                .filePath("main.py")
                .modulePath("main")
                .topLevelFunctions(List.of(fn))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        String expected = PythonKnowledgeGraphBuilder.computeMethodNodeId(
                "main", "list_users", List.of("skip", "limit"));
        assertThat(entries.get(0).getMethodNodeId()).isEqualTo(expected);
    }
}
