package com.huawei.hisi.knowledgegraph.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end integration test that exercises the full Python pipeline
 * (parse -> MethodNodes + EntryPointNodes + HttpCalls) against a
 * realistic FastAPI mini-project fixture.
 */
class FastApiE2ETest {

    private static final String FIXTURE_DIR =
            Paths.get("src/test/resources/python-fixtures/fastapi-app").toAbsolutePath().toString();

    private final PythonKnowledgeGraphBuilder kgBuilder =
            new PythonKnowledgeGraphBuilder(
                    mock(Neo4jStorageService.class),
                    new PythonCallGraphResolver(),
                    new FastApiRouteScanner(),
                    new DjangoUrlScanner(),
                    new FlaskRouteScanner(),
                    new PythonHttpCallScanner(),
                    new PythonMqCallScanner(),
                    new CeleryTaskScanner(),
                    mock(PythonDataModelScanner.class),
                    mock(Neo4jDataModelNodeRepository.class));

    private final FastApiRouteScanner routeScanner = new FastApiRouteScanner();
    private final PythonHttpCallScanner httpCallScanner = new PythonHttpCallScanner();

    @Test
    @DisplayName("e2e: parses FastAPI project into MethodNodes, EntryPointNodes, and HttpCalls")
    void e2e_parsesFastApiProject() throws Exception {
        // --- Phase 1: MethodNode extraction via KnowledgeGraphBuilder ---
        List<MethodNode> methods = kgBuilder.buildProject(FIXTURE_DIR, List.of());

        // At least 7 functions: health_check, call_external, list_users, create_user,
        // get_user, list_items, create_item
        assertThat(methods).hasSizeGreaterThanOrEqualTo(7);
        assertThat(methods).allMatch(m -> "python".equals(m.getLanguage()));

        // --- Phase 2: Parse each .py file individually and run scanners ---
        List<EntryPointNode> allRoutes = new ArrayList<>();
        List<PythonHttpCall> allHttpCalls = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(Paths.get(FIXTURE_DIR))) {
            List<Path> pyFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".py"))
                    .toList();

            for (Path pyFile : pyFiles) {
                PyModule module = parseModule(pyFile);
                allRoutes.addAll(routeScanner.scanModule(module, FIXTURE_DIR));
                allHttpCalls.addAll(httpCallScanner.scanModule(module, FIXTURE_DIR, "fastapi"));
            }
        }

        // --- Phase 3: Assert EntryPointNodes ---
        // Expected routes: GET /health, GET /external, GET /users, POST /users,
        // GET /users/{user_id}, GET /items, POST /items
        assertThat(allRoutes).hasSizeGreaterThanOrEqualTo(7);

        List<String> entryKeys = allRoutes.stream()
                .map(EntryPointNode::getEntryKey)
                .toList();
        assertThat(entryKeys).contains("GET /health", "GET /external",
                "GET /users", "POST /users", "GET /users/{user_id}",
                "GET /items", "POST /items");

        // Verify HTTP methods include both GET and POST
        List<String> httpMethods = entryKeys.stream()
                .map(k -> k.split(" ")[0])
                .distinct()
                .toList();
        assertThat(httpMethods).contains("GET", "POST");

        // All entry points should be python + fastapi
        assertThat(allRoutes).allMatch(e -> "python".equals(e.getLanguage()));
        assertThat(allRoutes).allMatch(e -> "fastapi".equals(e.getFramework()));

        // --- Phase 4: Assert PythonHttpCalls ---
        // Expected: requests.get("http://other-service/api/data") + httpx.post("http://auth-service/validate")
        assertThat(allHttpCalls).hasSizeGreaterThanOrEqualTo(2);

        List<String> callUrls = allHttpCalls.stream()
                .map(PythonHttpCall::getUrl)
                .toList();
        assertThat(callUrls).contains(
                "http://other-service/api/data",
                "http://auth-service/validate");

        List<String> callLibraries = allHttpCalls.stream()
                .map(PythonHttpCall::getLibrary)
                .distinct()
                .toList();
        assertThat(callLibraries).contains("requests", "httpx");
    }

    private PyModule parseModule(Path pyFile) throws IOException {
        String source = Files.readString(pyFile);
        String relativePath = Paths.get(FIXTURE_DIR).relativize(pyFile).toString();
        String modulePath = relativePath.replace('\\', '/');
        if (modulePath.endsWith(".py")) {
            modulePath = modulePath.substring(0, modulePath.length() - 3);
        }
        modulePath = modulePath.replace('/', '.');

        Python3Lexer lexer = new Python3Lexer(CharStreams.fromString(source));
        Python3Parser parser = new Python3Parser(new CommonTokenStream(lexer));
        return new PythonAstVisitor().visit(parser.file_input(), pyFile.toString(), modulePath);
    }
}
