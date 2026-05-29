package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
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
    @DisplayName("urls.py with path(\"users/\", views.user_list) → 1 entry")
    void scanModule_pathCall() {
        PyCall call = PyCall.builder()
                .calleeExpression("path")
                .lineNumber(10)
                .firstStringArg("users/")
                .build();
        PyModule module = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        EntryPointNode e = entries.get(0);
        assertThat(e.getEntryKey()).isEqualTo("users/");
        assertThat(e.getFramework()).isEqualTo("django");
        assertThat(e.getLanguage()).isEqualTo("python");
        assertThat(e.getEntryInfo()).contains("\"subType\":\"DJANGO_VIEW\"");
    }

    @Test
    @DisplayName("re_path with regex pattern captured")
    void scanModule_rePathCall() {
        PyCall call = PyCall.builder()
                .calleeExpression("re_path")
                .lineNumber(15)
                .firstStringArg("^items/(?P<id>\\d+)/$")
                .build();
        PyModule module = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).contains("items/");
    }

    @Test
    @DisplayName("CBV: class extending ModelViewSet → 1 entry")
    void scanModule_cbvViewSet() {
        PyClass clazz = PyClass.builder()
                .name("UserViewSet")
                .baseClasses(List.of("ModelViewSet"))
                .lineStart(10).lineEnd(50).build();
        PyModule module = PyModule.builder()
                .filePath("/app/views.py").modulePath("app.views")
                .classes(List.of(clazz)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        EntryPointNode e = entries.get(0);
        assertThat(e.getEntryKey()).isEqualTo("UserViewSet");
        assertThat(e.getEntryInfo()).contains("\"subType\":\"DJANGO_CBV\"");
    }

    @Test
    @DisplayName("Non-urls.py file with non-CBV class → 0 entries")
    void scanModule_nonUrlsFileSkipsCalls() {
        PyCall call = PyCall.builder()
                .calleeExpression("path")
                .lineNumber(10)
                .firstStringArg("ignored/")
                .build();
        PyClass clazz = PyClass.builder()
                .name("Helper")
                .baseClasses(List.of("object"))
                .lineStart(1).lineEnd(5).build();
        PyModule module = PyModule.builder()
                .filePath("/app/helpers.py").modulePath("app.helpers")
                .calls(List.of(call))
                .classes(List.of(clazz))
                .build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Multiple paths in one urls.py")
    void scanModule_multiplePaths() {
        PyCall call1 = PyCall.builder().calleeExpression("path")
                .lineNumber(10).firstStringArg("users/").build();
        PyCall call2 = PyCall.builder().calleeExpression("path")
                .lineNumber(11).firstStringArg("items/").build();
        PyCall call3 = PyCall.builder().calleeExpression("re_path")
                .lineNumber(12).firstStringArg("^api/v1/").build();
        PyModule module = PyModule.builder()
                .filePath("/app/urls.py").modulePath("app.urls")
                .calls(List.of(call1, call2, call3)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("APIView base class detected as CBV")
    void scanModule_apiViewBase() {
        PyClass clazz = PyClass.builder()
                .name("ProductDetail")
                .baseClasses(List.of("APIView"))
                .lineStart(5).lineEnd(30).build();
        PyModule module = PyModule.builder()
                .filePath("/app/api.py").modulePath("app.api")
                .classes(List.of(clazz)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryKey()).isEqualTo("ProductDetail");
    }

    @Test
    @DisplayName("django.urls.path qualified expression resolves correctly")
    void scanModule_qualifiedPathCall() {
        PyCall call = PyCall.builder()
                .calleeExpression("django.urls.path")
                .lineNumber(8)
                .firstStringArg("admin/")
                .build();
        PyModule module = PyModule.builder()
                .filePath("/proj/urls.py").modulePath("proj.urls")
                .calls(List.of(call)).build();

        List<EntryPointNode> entries = scanner.scanModule(module, PROJECT_PATH);

        assertThat(entries).hasSize(1);
    }
}
