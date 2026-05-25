package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;
import java.util.Map;

import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DjangoUrlScanner Tests")
class DjangoUrlScannerTest {

    private static final String PROJECT_PATH = "/home/projects/myapp";

    private DjangoUrlScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new DjangoUrlScanner();
    }

    @Test
    @DisplayName("FBV: path('users/', views.user_list) resolves methodNodeId across modules")
    void fbv_crossModuleResolution() {
        // Target: app.views.user_list(request)
        PyFunction userList = PyFunction.builder()
                .name("user_list").qualName("user_list")
                .paramNames(List.of("request"))
                .lineStart(10).lineEnd(20)
                .isMethod(false)
                .build();
        PyModule viewsModule = PyModule.builder()
                .filePath("/app/views.py").modulePath("app.views")
                .topLevelFunctions(List.of(userList))
                .build();

        // urls.py:  from . import views
        //           path('users/', views.user_list)
        PyImport imp = PyImport.builder()
                .moduleName("").symbol("views").fromImport(true).relativeLevel(1)
                .lineNumber(1).build();
        PyCall call = PyCall.builder()
                .calleeExpression("path").lineNumber(5)
                .firstStringArg("users/")
                .secondPositionalArg("views.user_list")
                .build();
        PyModule urlsModule = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .imports(List.of(imp))
                .calls(List.of(call))
                .build();

        Map<String, PyModule> modulesByPath = Map.of(
                "app.urls", urlsModule,
                "app.views", viewsModule);

        List<EntryPointNode> entries = scanner.scanModule(urlsModule, PROJECT_PATH, modulesByPath);

        assertThat(entries).hasSize(1);
        EntryPointNode e = entries.get(0);
        assertThat(e.getEntryKey()).isEqualTo("users/");
        assertThat(e.getFramework()).isEqualTo("django");
        assertThat(e.getMethodNodeId()).isNotNull();
        assertThat(e.getMethodNodeId()).isEqualTo(
                PythonKnowledgeGraphBuilder.computeMethodNodeId(
                        "app.views", "user_list", List.of("request")));
    }

    @Test
    @DisplayName("FBV: from .views import home_view → path('home/', home_view)")
    void fbv_directSymbolImport() {
        PyFunction homeView = PyFunction.builder()
                .name("home_view").qualName("home_view")
                .paramNames(List.of("request"))
                .lineStart(1).lineEnd(5)
                .isMethod(false)
                .build();
        PyModule viewsModule = PyModule.builder()
                .filePath("/app/views.py").modulePath("app.views")
                .topLevelFunctions(List.of(homeView))
                .build();

        PyImport imp = PyImport.builder()
                .moduleName("views").symbol("home_view")
                .fromImport(true).relativeLevel(1).lineNumber(1).build();
        PyCall call = PyCall.builder()
                .calleeExpression("path").lineNumber(5)
                .firstStringArg("home/")
                .secondPositionalArg("home_view")
                .build();
        PyModule urlsModule = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .imports(List.of(imp))
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(urlsModule, PROJECT_PATH,
                Map.of("app.views", viewsModule, "app.urls", urlsModule));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMethodNodeId()).isEqualTo(
                PythonKnowledgeGraphBuilder.computeMethodNodeId(
                        "app.views", "home_view", List.of("request")));
    }

    @Test
    @DisplayName("CBV: path('users/<id>/', UserDetail.as_view()) resolves to representative handler")
    void cbv_asViewResolution() {
        PyFunction get = PyFunction.builder()
                .name("get").qualName("UserDetail.get")
                .paramNames(List.of("self", "request"))
                .isMethod(true).enclosingClass("UserDetail")
                .build();
        PyClass userDetail = PyClass.builder()
                .name("UserDetail")
                .baseClasses(List.of("View"))
                .methods(List.of(get))
                .lineStart(1).lineEnd(20)
                .build();
        PyModule viewsModule = PyModule.builder()
                .filePath("/app/views.py").modulePath("app.views")
                .classes(List.of(userDetail))
                .build();

        PyImport imp = PyImport.builder()
                .moduleName("views").symbol("UserDetail")
                .fromImport(true).relativeLevel(1).lineNumber(1).build();
        PyCall call = PyCall.builder()
                .calleeExpression("path").lineNumber(5)
                .firstStringArg("users/<id>/")
                .secondPositionalArg("UserDetail.as_view()")
                .build();
        PyModule urlsModule = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .imports(List.of(imp))
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(urlsModule, PROJECT_PATH,
                Map.of("app.views", viewsModule, "app.urls", urlsModule));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMethodNodeId()).isEqualTo(
                PythonKnowledgeGraphBuilder.computeMethodNodeId(
                        "app.views", "UserDetail.get", List.of("self", "request")));
    }

    @Test
    @DisplayName("Unresolvable view expression → entry created with null methodNodeId")
    void unresolvable_yieldsNullMethodNodeId() {
        PyCall call = PyCall.builder()
                .calleeExpression("path").lineNumber(10)
                .firstStringArg("admin/")
                .secondPositionalArg("admin.site.urls")
                .build();
        PyModule urlsModule = PyModule.builder()
                .filePath("/proj/urls.py").modulePath("proj.urls")
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(urlsModule, PROJECT_PATH,
                Map.of("proj.urls", urlsModule));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).isEqualTo("admin/");
        assertThat(entries.get(0).getMethodNodeId()).isNull();
    }

    @Test
    @DisplayName("Non-urls.py module → 0 entries (Mode 2 / any-module CBV scan removed)")
    void nonUrlsModule_yieldsNoEntries() {
        PyClass cbv = PyClass.builder()
                .name("UnregisteredView")
                .baseClasses(List.of("View"))
                .lineStart(1).lineEnd(10).build();
        PyModule viewsModule = PyModule.builder()
                .filePath("/app/views.py").modulePath("app.views")
                .classes(List.of(cbv)).build();

        List<EntryPointNode> entries = scanner.scanModule(viewsModule, PROJECT_PATH,
                Map.of("app.views", viewsModule));

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Multiple path() calls in one urls.py all become entries")
    void multiplePaths() {
        PyFunction f1 = PyFunction.builder().name("a").qualName("a").build();
        PyFunction f2 = PyFunction.builder().name("b").qualName("b").build();
        PyFunction f3 = PyFunction.builder().name("c").qualName("c").build();
        PyModule views = PyModule.builder()
                .filePath("/app/views.py").modulePath("app.views")
                .topLevelFunctions(List.of(f1, f2, f3)).build();

        PyImport imp = PyImport.builder().moduleName("").symbol("views")
                .fromImport(true).relativeLevel(1).lineNumber(1).build();
        PyModule urls = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .imports(List.of(imp))
                .calls(List.of(
                        PyCall.builder().calleeExpression("path").lineNumber(1)
                                .firstStringArg("a/").secondPositionalArg("views.a").build(),
                        PyCall.builder().calleeExpression("path").lineNumber(2)
                                .firstStringArg("b/").secondPositionalArg("views.b").build(),
                        PyCall.builder().calleeExpression("re_path").lineNumber(3)
                                .firstStringArg("^c/").secondPositionalArg("views.c").build()))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(urls, PROJECT_PATH,
                Map.of("app.views", views, "app.urls", urls));

        assertThat(entries).hasSize(3);
        assertThat(entries).allMatch(e -> e.getMethodNodeId() != null);
    }

    @Test
    @DisplayName("Qualified expression django.urls.path is still recognized")
    void qualifiedPathCall() {
        PyCall call = PyCall.builder()
                .calleeExpression("django.urls.path").lineNumber(8)
                .firstStringArg("admin/")
                .secondPositionalArg("admin.site.urls")
                .build();
        PyModule urls = PyModule.builder()
                .filePath("/proj/urls.py").modulePath("proj.urls")
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(urls, PROJECT_PATH,
                Map.of("proj.urls", urls));

        assertThat(entries).hasSize(1);
    }

    @Test
    @DisplayName("Backwards-compat: scanModule(module, projectPath) leaves methodNodeId null")
    void backwardCompatOverload() {
        PyCall call = PyCall.builder()
                .calleeExpression("path").lineNumber(10)
                .firstStringArg("home/")
                .secondPositionalArg("views.home")
                .build();
        PyModule urls = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(urls, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMethodNodeId()).isNull();
    }
}
