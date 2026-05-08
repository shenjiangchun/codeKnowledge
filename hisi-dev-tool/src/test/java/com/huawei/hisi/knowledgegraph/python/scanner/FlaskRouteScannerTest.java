package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;

import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlaskRouteScanner Tests")
class FlaskRouteScannerTest {

    private static final String PROJECT_PATH = "/home/projects/myapp";

    private FlaskRouteScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new FlaskRouteScanner();
    }

    @Test
    @DisplayName("app.route(\"/users\") → GET /users")
    void scanModule_appRouteDefaultGet() {
        PyFunction fn = PyFunction.builder()
                .name("list_users")
                .decorators(List.of("app.route(\"/users\")"))
                .lineStart(10).lineEnd(15).build();
        PyModule module = PyModule.builder()
                .filePath("views.py").modulePath("views")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        EntryPointNode e = entries.get(0);
        assertThat(e.getEntryKey()).isEqualTo("GET /users");
        assertThat(e.getEntryType()).isEqualTo(EntryPointNode.TYPE_HTTP);
        assertThat(e.getLanguage()).isEqualTo("python");
        assertThat(e.getFramework()).isEqualTo("flask");
        assertThat(e.getEntryInfo()).contains("\"subType\":\"FLASK_ROUTE\"");
    }

    @Test
    @DisplayName("bp.route(\"/items\", methods=[\"POST\"]) → POST /items")
    void scanModule_bpRouteWithMethodsPost() {
        PyFunction fn = PyFunction.builder()
                .name("create_item")
                .decorators(List.of("bp.route(\"/items\", methods=[\"POST\"])"))
                .lineStart(20).lineEnd(30).build();
        PyModule module = PyModule.builder()
                .filePath("items.py").modulePath("items")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).isEqualTo("POST /items");
    }

    @Test
    @DisplayName("Non-route decorator ignored")
    void scanModule_ignoresNonRouteDecorators() {
        PyFunction fn = PyFunction.builder()
                .name("helper")
                .decorators(List.of("staticmethod", "login_required"))
                .lineStart(5).lineEnd(10).build();
        PyModule module = PyModule.builder()
                .filePath("utils.py").modulePath("utils")
                .topLevelFunctions(List.of(fn)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Multiple routes in one module")
    void scanModule_multipleRoutes() {
        PyFunction fn1 = PyFunction.builder()
                .name("index").decorators(List.of("app.route(\"/\")"))
                .lineStart(1).lineEnd(3).build();
        PyFunction fn2 = PyFunction.builder()
                .name("about").decorators(List.of("app.route(\"/about\")"))
                .lineStart(5).lineEnd(8).build();
        PyFunction fn3 = PyFunction.builder()
                .name("contact").decorators(List.of("app.route(\"/contact\", methods=[\"GET\",\"POST\"])"))
                .lineStart(10).lineEnd(15).build();
        PyModule module = PyModule.builder()
                .filePath("main.py").modulePath("main")
                .topLevelFunctions(List.of(fn1, fn2, fn3)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("Class method with route")
    void scanModule_methodInsideClass() {
        PyFunction method = PyFunction.builder()
                .name("get_profile")
                .decorators(List.of("app.route(\"/profile\")"))
                .lineStart(5).lineEnd(10)
                .isMethod(true).enclosingClass("UserViews")
                .build();
        PyClass clazz = PyClass.builder()
                .name("UserViews")
                .methods(List.of(method))
                .lineStart(3).lineEnd(15).build();
        PyModule module = PyModule.builder()
                .filePath("user_views.py").modulePath("user_views")
                .classes(List.of(clazz)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryInfo()).contains("\"handlerClass\":\"UserViews\"");
    }

    @Test
    @DisplayName("Null module returns empty list")
    void scanModule_nullModule() {
        assertThat(scanner.scanModule(null, PROJECT_PATH)).isEmpty();
    }
}
