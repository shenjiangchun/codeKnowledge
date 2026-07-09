package com.huawei.hisi.knowledgegraph.python.call;

import java.util.List;
import java.util.Map;

import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PythonCallGraphResolverTest {

    private final PythonCallGraphResolver resolver = new PythonCallGraphResolver();

    private static String nodeId(String source) {
        return PythonKnowledgeGraphBuilder.toNodeId(source);
    }

    @Test
    @DisplayName("resolveModule: intra-module direct call yields a DIRECT edge")
    void intraModuleDirectCall() {
        PyFunction a = PyFunction.builder()
                .name("a").qualName("a").lineStart(1).lineEnd(2).isMethod(false).build();
        PyFunction b = PyFunction.builder()
                .name("b").qualName("b").lineStart(3).lineEnd(4).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("b").lineNumber(2).enclosingFunction("a").build();
        PyModule module = PyModule.builder()
                .filePath("/x/m.py").modulePath("m")
                .topLevelFunctions(List.of(a, b))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges = resolver.resolveModule(module, "/x", List.of(module));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("DIRECT");
        assertThat(e.get("callLine")).isEqualTo(2);
        assertThat(e.get("callerId")).isEqualTo(nodeId("m::a()"));
        assertThat(e.get("calleeId")).isEqualTo(nodeId("m::b()"));
        assertThat(e).doesNotContainKey("unresolved");
    }

    @Test
    @DisplayName("resolveModule: self.method() yields a SELF edge")
    void selfMethodCall() {
        PyFunction foo = PyFunction.builder()
                .name("foo").qualName("C.foo").paramNames(List.of("self"))
                .lineStart(2).lineEnd(3).isMethod(true).enclosingClass("C").build();
        PyFunction bar = PyFunction.builder()
                .name("bar").qualName("C.bar").paramNames(List.of("self"))
                .lineStart(4).lineEnd(5).isMethod(true).enclosingClass("C").build();
        PyClass cls = PyClass.builder()
                .name("C").methods(List.of(foo, bar)).lineStart(1).lineEnd(5).build();
        PyCall call = PyCall.builder()
                .calleeExpression("self.bar").lineNumber(3).enclosingFunction("C.foo").build();
        PyModule module = PyModule.builder()
                .filePath("/x/m.py").modulePath("m")
                .classes(List.of(cls))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges = resolver.resolveModule(module, "/x", List.of(module));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("SELF");
        assertThat(e.get("callerId")).isEqualTo(nodeId("m::C.foo(self)"));
        assertThat(e.get("calleeId")).isEqualTo(nodeId("m::C.bar(self)"));
    }

    @Test
    @DisplayName("resolveModule: cross-module 'from x import f' resolves to the imported module's function")
    void crossModuleFromImportCall() {
        PyFunction func = PyFunction.builder()
                .name("func").qualName("func").lineStart(1).lineEnd(2).isMethod(false).build();
        PyModule moduleB = PyModule.builder()
                .filePath("/x/b.py").modulePath("b")
                .topLevelFunctions(List.of(func))
                .build();

        PyImport imp = PyImport.builder()
                .moduleName("b").symbol("func").fromImport(true).lineNumber(1).build();
        PyFunction caller = PyFunction.builder()
                .name("a").qualName("a").lineStart(3).lineEnd(4).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("func").lineNumber(4).enclosingFunction("a").build();
        PyModule moduleA = PyModule.builder()
                .filePath("/x/a.py").modulePath("a")
                .imports(List.of(imp))
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges =
                resolver.resolveModule(moduleA, "/x", List.of(moduleA, moduleB));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("IMPORT");
        assertThat(e.get("callerId")).isEqualTo(nodeId("a::a()"));
        assertThat(e.get("calleeId")).isEqualTo(nodeId("b::func()"));
        assertThat(e).doesNotContainKey("unresolved");
    }

    @Test
    @DisplayName("resolveModule: 'import x.y as alias' + alias.func() resolves cross-module")
    void moduleStyleImportWithAlias() {
        PyFunction helper = PyFunction.builder()
                .name("helper").qualName("helper").lineStart(1).lineEnd(2).isMethod(false).build();
        PyModule utilsMod = PyModule.builder()
                .filePath("/x/pkg/utils.py").modulePath("pkg.utils")
                .topLevelFunctions(List.of(helper))
                .build();

        PyImport imp = PyImport.builder()
                .moduleName("pkg.utils").alias("u").fromImport(false).lineNumber(1).build();
        PyFunction caller = PyFunction.builder()
                .name("main").qualName("main").lineStart(3).lineEnd(4).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("u.helper").lineNumber(4).enclosingFunction("main").build();
        PyModule mainMod = PyModule.builder()
                .filePath("/x/main.py").modulePath("main")
                .imports(List.of(imp))
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges =
                resolver.resolveModule(mainMod, "/x", List.of(mainMod, utilsMod));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("IMPORT");
        assertThat(e.get("calleeId")).isEqualTo(nodeId("pkg.utils::helper()"));
        assertThat(e).doesNotContainKey("unresolved");
    }

    @Test
    @DisplayName("resolveModule: 'from x import Cls' + Cls.method() resolves to the class method")
    void fromImportClassMethod() {
        PyFunction doWork = PyFunction.builder()
                .name("do_work").qualName("Worker.do_work").paramNames(List.of("self"))
                .lineStart(3).lineEnd(4).isMethod(true).enclosingClass("Worker").build();
        PyClass worker = PyClass.builder()
                .name("Worker").methods(List.of(doWork)).lineStart(1).lineEnd(5).build();
        PyModule libMod = PyModule.builder()
                .filePath("/x/lib.py").modulePath("lib")
                .classes(List.of(worker))
                .build();

        PyImport imp = PyImport.builder()
                .moduleName("lib").symbol("Worker").fromImport(true).lineNumber(1).build();
        PyFunction caller = PyFunction.builder()
                .name("run").qualName("run").lineStart(3).lineEnd(4).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("Worker.do_work").lineNumber(4).enclosingFunction("run").build();
        PyModule appMod = PyModule.builder()
                .filePath("/x/app.py").modulePath("app")
                .imports(List.of(imp))
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges =
                resolver.resolveModule(appMod, "/x", List.of(appMod, libMod));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("IMPORT");
        assertThat(e.get("calleeId")).isEqualTo(nodeId("lib::Worker.do_work(self)"));
        assertThat(e).doesNotContainKey("unresolved");
    }

    @Test
    @DisplayName("resolveModule: LocalClass.method() in same module yields DIRECT edge")
    void localClassMethodCall() {
        PyFunction process = PyFunction.builder()
                .name("process").qualName("Handler.process").paramNames(List.of("self", "data"))
                .lineStart(3).lineEnd(4).isMethod(true).enclosingClass("Handler").build();
        PyClass handler = PyClass.builder()
                .name("Handler").methods(List.of(process)).lineStart(1).lineEnd(5).build();
        PyFunction caller = PyFunction.builder()
                .name("main").qualName("main").lineStart(7).lineEnd(8).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("Handler.process").lineNumber(8).enclosingFunction("main").build();
        PyModule module = PyModule.builder()
                .filePath("/x/m.py").modulePath("m")
                .classes(List.of(handler))
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges = resolver.resolveModule(module, "/x", List.of(module));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("DIRECT");
        assertThat(e.get("calleeId")).isEqualTo(nodeId("m::Handler.process(self,data)"));
        assertThat(e).doesNotContainKey("unresolved");
    }

    @Test
    @DisplayName("resolveModule: unknown name produces an unresolved edge")
    void unresolvedCall() {
        PyFunction caller = PyFunction.builder()
                .name("a").qualName("a").lineStart(1).lineEnd(2).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("unknown").lineNumber(2).enclosingFunction("a").build();
        PyModule module = PyModule.builder()
                .filePath("/x/m.py").modulePath("m")
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges = resolver.resolveModule(module, "/x", List.of(module));

        // Per class Javadoc: unknown callees are emitted as UNRESOLVED edges
        // (with synthetic calleeId) so downstream tools can still see the call site.
        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("UNRESOLVED");
        assertThat(e.get("unresolved")).isEqualTo(true);
        assertThat((String) e.get("calleeId")).startsWith("unresolved:");
    }

    @Test
    @DisplayName("resolveModule: empty module yields empty list")
    void emptyModule() {
        PyModule module = PyModule.builder()
                .filePath("/x/m.py").modulePath("m").build();

        List<Map<String, Object>> edges = resolver.resolveModule(module, "/x", List.of(module));

        assertThat(edges).isEmpty();
    }

    @Test
    @DisplayName("resolveProject: aggregates edges across all modules")
    void resolveProjectAggregates() {
        PyFunction func = PyFunction.builder()
                .name("func").qualName("func").lineStart(1).lineEnd(2).isMethod(false).build();
        PyModule moduleB = PyModule.builder()
                .filePath("/x/b.py").modulePath("b")
                .topLevelFunctions(List.of(func))
                .build();

        PyImport imp = PyImport.builder()
                .moduleName("b").symbol("func").fromImport(true).lineNumber(1).build();
        PyFunction caller = PyFunction.builder()
                .name("a").qualName("a").lineStart(3).lineEnd(4).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("func").lineNumber(4).enclosingFunction("a").build();
        PyModule moduleA = PyModule.builder()
                .filePath("/x/a.py").modulePath("a")
                .imports(List.of(imp))
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges =
                resolver.resolveProject(List.of(moduleA, moduleB), "/x");

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).get("callType")).isEqualTo("IMPORT");
    }

    @Test
    @DisplayName("resolveModule: 'from . import views' + views.func() resolves via submodule fallback")
    void fromDotImportSubmodule() {
        PyFunction userList = PyFunction.builder()
                .name("user_list").qualName("user_list").paramNames(List.of("request"))
                .lineStart(1).lineEnd(2).isMethod(false).build();
        PyModule viewsMod = PyModule.builder()
                .filePath("/x/app/views.py").modulePath("app.views")
                .topLevelFunctions(List.of(userList))
                .build();

        PyImport imp = PyImport.builder()
                .moduleName("").symbol("views").fromImport(true).relativeLevel(1).lineNumber(1).build();
        PyFunction caller = PyFunction.builder()
                .name("setup").qualName("setup").lineStart(3).lineEnd(4).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("views.user_list").lineNumber(4).enclosingFunction("setup").build();
        PyModule urlsMod = PyModule.builder()
                .filePath("/x/app/urls.py").modulePath("app.urls")
                .imports(List.of(imp))
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges =
                resolver.resolveModule(urlsMod, "/x", List.of(urlsMod, viewsMod));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("IMPORT");
        assertThat(e.get("calleeId")).isEqualTo(nodeId("app.views::user_list(request)"));
    }

    @Test
    @DisplayName("resolveModule: 'from x import *' + func() resolves via wildcard fallback")
    void wildcardImportFallback() {
        PyFunction helper = PyFunction.builder()
                .name("helper").qualName("helper").paramNames(List.of("x"))
                .lineStart(1).lineEnd(2).isMethod(false).build();
        PyModule utilsMod = PyModule.builder()
                .filePath("/x/utils.py").modulePath("utils")
                .topLevelFunctions(List.of(helper))
                .build();

        PyImport wildcard = PyImport.builder()
                .moduleName("utils").symbol("*").fromImport(true).lineNumber(1).build();
        PyFunction caller = PyFunction.builder()
                .name("main").qualName("main").lineStart(3).lineEnd(4).isMethod(false).build();
        PyCall call = PyCall.builder()
                .calleeExpression("helper").lineNumber(4).enclosingFunction("main").build();
        PyModule mainMod = PyModule.builder()
                .filePath("/x/main.py").modulePath("main")
                .imports(List.of(wildcard))
                .topLevelFunctions(List.of(caller))
                .calls(List.of(call))
                .build();

        List<Map<String, Object>> edges =
                resolver.resolveModule(mainMod, "/x", List.of(mainMod, utilsMod));

        assertThat(edges).hasSize(1);
        Map<String, Object> e = edges.get(0);
        assertThat(e.get("callType")).isEqualTo("IMPORT");
        assertThat(e.get("calleeId")).isEqualTo(nodeId("utils::helper(x)"));
    }
}
