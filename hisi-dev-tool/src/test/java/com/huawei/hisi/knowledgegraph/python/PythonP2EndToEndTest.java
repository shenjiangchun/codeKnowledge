package com.huawei.hisi.knowledgegraph.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Lexer;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
import com.huawei.hisi.knowledgegraph.python.scanner.CeleryTaskScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.DjangoUrlScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FastApiRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FlaskRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonHttpCallScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonMqCall;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonMqCallScanner;
import com.huawei.hisi.knowledgegraph.service.storage.Neo4jStorageService;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P2 end-to-end integration test exercising the full pipeline:
 * Python parsing (ANTLR4) -> Flask/Celery scanners -> call graph resolution.
 * Uses inline temp files with a mixed Flask + Celery fixture.
 */
class PythonP2EndToEndTest {

    private static final String APP_PY = """
            from flask import Flask
            app = Flask(__name__)

            @app.route("/health", methods=["GET"])
            def health():
                return "OK"

            @app.route("/users", methods=["POST"])
            def create_user():
                notify_user()
                return "created"

            def notify_user():
                pass
            """;

    private static final String TASKS_PY = """
            from celery import Celery
            celery = Celery("worker")

            @celery.task
            def process_order(order_id):
                validate(order_id)
                return True

            def validate(order_id):
                pass
            """;

    @TempDir
    Path tempDir;

    private PythonKnowledgeGraphBuilder kgBuilder;
    private FlaskRouteScanner flaskScanner;
    private CeleryTaskScanner celeryScanner;
    private PythonCallGraphResolver callGraphResolver;
    private PythonMqCallScanner mqCallScanner;

    @BeforeEach
    void setUp() {
        kgBuilder = new PythonKnowledgeGraphBuilder(
                mock(Neo4jStorageService.class),
                new PythonCallGraphResolver(),
                new FastApiRouteScanner(),
                new DjangoUrlScanner(),
                new FlaskRouteScanner(),
                new PythonHttpCallScanner(),
                new PythonMqCallScanner(),
                new CeleryTaskScanner());
        flaskScanner = new FlaskRouteScanner();
        celeryScanner = new CeleryTaskScanner();
        callGraphResolver = new PythonCallGraphResolver();
        mqCallScanner = new PythonMqCallScanner();
    }

    @Test
    @DisplayName("P2 e2e: Flask + Celery parsing, scanning, and call graph resolution")
    void p2EndToEnd_flaskAndCelery() throws Exception {
        // --- Setup: write fixture files ---
        Path appPy = tempDir.resolve("app.py");
        Path tasksPy = tempDir.resolve("tasks.py");
        Files.writeString(appPy, APP_PY);
        Files.writeString(tasksPy, TASKS_PY);

        String projectPath = tempDir.toAbsolutePath().toString();

        // --- Phase 1: buildProject -> MethodNodes ---
        List<MethodNode> methods = kgBuilder.buildProject(projectPath, List.of());

        List<String> methodNames = methods.stream()
                .map(MethodNode::getMethodName)
                .toList();
        assertThat(methodNames).containsExactlyInAnyOrder(
                "health", "create_user", "notify_user", "process_order", "validate");
        assertThat(methods).allMatch(m -> "python".equals(m.getLanguage()));

        // --- Phase 2: FlaskRouteScanner on app.py ---
        PyModule appModule = parseModule(appPy, projectPath);
        List<EntryPointNode> flaskRoutes = flaskScanner.scanModule(appModule, projectPath);

        assertThat(flaskRoutes).hasSize(2);
        assertThat(flaskRoutes).allMatch(e -> "python".equals(e.getLanguage()));
        assertThat(flaskRoutes).allMatch(e -> "flask".equals(e.getFramework()));

        List<String> routeKeys = flaskRoutes.stream()
                .map(EntryPointNode::getEntryKey)
                .toList();
        assertThat(routeKeys).containsExactlyInAnyOrder("GET /health", "POST /users");

        // --- Phase 3: CeleryTaskScanner on tasks.py ---
        PyModule tasksModule = parseModule(tasksPy, projectPath);
        List<EntryPointNode> celeryTasks = celeryScanner.scanModule(tasksModule, projectPath);

        assertThat(celeryTasks).hasSize(1);
        assertThat(celeryTasks).allMatch(e -> "python".equals(e.getLanguage()));
        assertThat(celeryTasks).allMatch(e -> "celery".equals(e.getFramework()));
        assertThat(celeryTasks.get(0).getEntryKey()).isEqualTo("process_order");

        // --- Phase 4: PythonCallGraphResolver ---
        List<PyModule> allModules = List.of(appModule, tasksModule);
        List<Map<String, Object>> edges = callGraphResolver.resolveProject(allModules, projectPath);

        // Expect at least 2 DIRECT call edges: create_user->notify_user, process_order->validate
        List<Map<String, Object>> directEdges = edges.stream()
                .filter(e -> "DIRECT".equals(e.get("callType")))
                .toList();
        assertThat(directEdges).hasSizeGreaterThanOrEqualTo(2);

        // All edges must have callType set
        assertThat(edges).allMatch(e -> e.get("callType") != null);

        // --- Phase 5: PythonMqCallScanner (no MQ calls expected in this fixture) ---
        List<PythonMqCall> mqCalls = new ArrayList<>();
        for (PyModule module : allModules) {
            mqCalls.addAll(mqCallScanner.scanModule(module, projectPath, "mixed"));
        }
        // No send_task/send/publish calls in our fixture, so empty is correct
        assertThat(mqCalls).isEmpty();
    }

    private PyModule parseModule(Path pyFile, String projectPath) throws IOException {
        String source = Files.readString(pyFile);
        String relativePath = Path.of(projectPath).relativize(pyFile).toString();
        String modulePath = PythonKnowledgeGraphBuilder.toModulePath(relativePath);

        Python3Lexer lexer = new Python3Lexer(CharStreams.fromString(source));
        Python3Parser parser = new Python3Parser(new CommonTokenStream(lexer));
        return new PythonAstVisitor().visit(parser.file_input(), pyFile.toString(), modulePath);
    }
}
