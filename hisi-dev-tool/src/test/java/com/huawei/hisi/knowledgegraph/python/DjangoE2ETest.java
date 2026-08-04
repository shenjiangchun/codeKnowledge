package com.huawei.hisi.knowledgegraph.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Lexer;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
import com.huawei.hisi.knowledgegraph.python.scanner.CeleryTaskScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.DjangoUrlScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FastApiRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FlaskRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonHttpCall;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonHttpCallScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonMqCallScanner;
import com.huawei.hisi.knowledgegraph.service.storage.Neo4jStorageService;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Django E2E Pipeline Test")
class DjangoE2ETest {

    private static final String FIXTURE_DIR =
            Paths.get("src/test/resources/python-fixtures/django-app").toAbsolutePath().toString();

    private final DjangoUrlScanner urlScanner = new DjangoUrlScanner();
    private final PythonHttpCallScanner httpCallScanner = new PythonHttpCallScanner();
    private final PythonCallGraphResolver callGraphResolver = new PythonCallGraphResolver();

    private final PythonKnowledgeGraphBuilder kgBuilder =
            new PythonKnowledgeGraphBuilder(
                    mock(Neo4jStorageService.class),
                    callGraphResolver,
                    new FastApiRouteScanner(),
                    urlScanner,
                    new FlaskRouteScanner(),
                    httpCallScanner,
                    new PythonMqCallScanner(),
                    new CeleryTaskScanner(),
                    mock(PythonDataModelScanner.class),
                    mock(Neo4jDataModelNodeRepository.class));

    @Test
    @DisplayName("e2e: full Django pipeline — ANTLR parse, module-level calls, URLs, includes, CBV, cross-module resolution")
    void e2e_fullDjangoPipeline() throws Exception {
        // Phase 1: Parse all modules via the full KG builder pipeline
        List<MethodNode> methods = kgBuilder.buildProject(FIXTURE_DIR, List.of());

        // views.py should produce: user_list, user_detail, OrderView.get, OrderView.post
        assertThat(methods).hasSizeGreaterThanOrEqualTo(4);
        assertThat(methods).allMatch(m -> "python".equals(m.getLanguage()));

        // Phase 2: Parse modules individually for scanner verification
        List<PyModule> modules = new ArrayList<>();
        Map<String, PyModule> modulesByPath = new LinkedHashMap<>();

        try (Stream<Path> walk = Files.walk(Paths.get(FIXTURE_DIR))) {
            List<Path> pyFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".py"))
                    .toList();

            for (Path pyFile : pyFiles) {
                PyModule module = parseModule(pyFile);
                modules.add(module);
                if (module.getModulePath() != null) {
                    modulesByPath.put(module.getModulePath(), module);
                }
            }
        }

        // Phase 3: Verify __init__.py module path fix (H1)
        assertThat(modulesByPath).containsKey("myapp");
        assertThat(modulesByPath).doesNotContainKey("myapp.__init__");

        // Phase 4: Verify module-level calls captured (C1)
        PyModule myappUrls = modulesByPath.get("myapp.urls");
        assertThat(myappUrls).isNotNull();
        assertThat(myappUrls.getCalls()).isNotEmpty();
        assertThat(myappUrls.getCalls()).anyMatch(
                c -> "path".equals(lastSegment(c.getCalleeExpression()))
                        && "<module>".equals(c.getEnclosingFunction()));

        // Phase 5: Scan Django URL entries
        List<EntryPointNode> allEntries = new ArrayList<>();
        List<DjangoUrlScanner.IncludeMapping> allIncludes = new ArrayList<>();

        for (PyModule module : modules) {
            allEntries.addAll(urlScanner.scanModule(module, FIXTURE_DIR, modulesByPath));
            allIncludes.addAll(urlScanner.scanIncludes(module));
        }

        // myapp/urls.py should produce entries for user_list, user_detail, OrderView
        assertThat(allEntries).isNotEmpty();
        assertThat(allEntries).allMatch(e -> "python".equals(e.getLanguage()));
        assertThat(allEntries).allMatch(e -> "django".equals(e.getFramework()));

        List<String> entryKeys = allEntries.stream()
                .map(EntryPointNode::getEntryKey)
                .toList();
        assertThat(entryKeys).anyMatch(k -> k.contains("users/"));

        // Phase 6: Verify CBV produces multiple HTTP method entries (L2)
        long cbvEntries = allEntries.stream()
                .filter(e -> e.getEntryKey() != null && e.getEntryKey().contains("orders/"))
                .count();
        assertThat(cbvEntries).as("OrderView CBV should produce GET + POST entries")
                .isGreaterThanOrEqualTo(2);
        assertThat(entryKeys).anyMatch(k -> k.contains("orders/") && k.contains("[GET]"));
        assertThat(entryKeys).anyMatch(k -> k.contains("orders/") && k.contains("[POST]"));

        // Phase 7: Verify include() detection (M1)
        assertThat(allIncludes).isNotEmpty();
        assertThat(allIncludes).anyMatch(
                inc -> "api/".equals(inc.prefix()) && "myapp.urls".equals(inc.targetModulePath()));

        // Apply includes
        DjangoUrlScanner.applyIncludes(allEntries, allIncludes, modulesByPath);

        // After applying includes, entries from myapp/urls.py should have "api/" prefix prepended
        List<String> updatedKeys = allEntries.stream()
                .map(EntryPointNode::getEntryKey)
                .toList();
        assertThat(updatedKeys).anyMatch(k -> k.startsWith("api/users/"));

        // Phase 8: Verify cross-module call resolution runs without error
        // (H2 resolution correctness is validated by PythonCallGraphResolverTest)
        List<Map<String, Object>> callEdges = callGraphResolver.resolveProject(modules, FIXTURE_DIR);

        // Phase 9: Verify HTTP call scanner detects requests.get in views.py
        List<PythonHttpCall> httpCalls = new ArrayList<>();
        for (PyModule module : modules) {
            httpCalls.addAll(httpCallScanner.scanModule(module, FIXTURE_DIR, "django"));
        }
        assertThat(httpCalls).isNotEmpty();
        assertThat(httpCalls).anyMatch(
                c -> "requests".equals(c.getLibrary()) && "GET".equals(c.getHttpMethod()));
    }

    private PyModule parseModule(Path pyFile) throws IOException {
        String source = Files.readString(pyFile);
        String relativePath = Paths.get(FIXTURE_DIR).relativize(pyFile).toString();
        String modulePath = PythonKnowledgeGraphBuilder.toModulePath(
                relativePath.replace('\\', '/'));

        Python3Lexer lexer = new Python3Lexer(CharStreams.fromString(source));
        Python3Parser parser = new Python3Parser(new CommonTokenStream(lexer));
        return new PythonAstVisitor().visit(parser.file_input(), pyFile.toString(), modulePath);
    }

    private static String lastSegment(String expr) {
        if (expr == null) return null;
        int dot = expr.lastIndexOf('.');
        return dot < 0 ? expr : expr.substring(dot + 1);
    }
}
