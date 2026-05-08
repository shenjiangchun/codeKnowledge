package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PythonHttpCallScanner}.
 */
@DisplayName("PythonHttpCallScanner Tests")
class PythonHttpCallScannerTest {

    private static final String PROJECT_PATH = "/home/projects/myapp";

    private PythonHttpCallScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new PythonHttpCallScanner();
    }

    @Test
    @DisplayName("requests.get detected as GET with library=requests")
    void requestsGet() {
        PyCall call = PyCall.builder()
                .calleeExpression("requests.get")
                .lineNumber(10)
                .enclosingFunction("fetch_data")
                .firstStringArg("http://example.com/api")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, "fastapi");

        assertThat(results).hasSize(1);
        PythonHttpCall r = results.get(0);
        assertThat(r.getLibrary()).isEqualTo("requests");
        assertThat(r.getHttpMethod()).isEqualTo("GET");
        assertThat(r.getUrl()).isEqualTo("http://example.com/api");
        assertThat(r.getLineNumber()).isEqualTo(10);
        assertThat(r.getEnclosingFunction()).isEqualTo("fetch_data");
        assertThat(r.getLanguage()).isEqualTo("python");
        assertThat(r.getFramework()).isEqualTo("fastapi");
        assertThat(r.getFilePath()).isEqualTo("app.py");
    }

    @Test
    @DisplayName("requests.post detected as POST")
    void requestsPost() {
        PyCall call = PyCall.builder()
                .calleeExpression("requests.post")
                .lineNumber(20)
                .enclosingFunction("send_data")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getHttpMethod()).isEqualTo("POST");
        assertThat(results.get(0).getLibrary()).isEqualTo("requests");
        assertThat(results.get(0).getUrl()).isNull();
    }

    @Test
    @DisplayName("session.get matched as heuristic instance call")
    void sessionGet() {
        PyCall call = PyCall.builder()
                .calleeExpression("session.get")
                .lineNumber(5)
                .enclosingFunction("do_request")
                .firstStringArg("/users")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        PythonHttpCall r = results.get(0);
        assertThat(r.getLibrary()).isEqualTo("session");
        assertThat(r.getHttpMethod()).isEqualTo("GET");
        assertThat(r.getUrl()).isEqualTo("/users");
    }

    @Test
    @DisplayName("non-HTTP call like os.path.join produces no results")
    void nonHttpCallIgnored() {
        PyCall call = PyCall.builder()
                .calleeExpression("os.path.join")
                .lineNumber(3)
                .enclosingFunction("build_path")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("multiple HTTP calls in one function are all captured")
    void multipleCalls() {
        PyCall get = PyCall.builder()
                .calleeExpression("requests.get")
                .lineNumber(10)
                .enclosingFunction("sync_data")
                .build();
        PyCall post = PyCall.builder()
                .calleeExpression("httpx.post")
                .lineNumber(15)
                .enclosingFunction("sync_data")
                .build();
        PyCall delete = PyCall.builder()
                .calleeExpression("aiohttp.delete")
                .lineNumber(20)
                .enclosingFunction("sync_data")
                .build();
        PyModule module = PyModule.builder()
                .filePath("app.py")
                .modulePath("app")
                .calls(List.of(get, post, delete))
                .build();

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(r -> "sync_data".equals(r.getEnclosingFunction()));
        assertThat(results).extracting(PythonHttpCall::getHttpMethod)
                .containsExactly("GET", "POST", "DELETE");
    }

    @Test
    @DisplayName("projectPath is set on result")
    void projectPathSet() {
        PyCall call = PyCall.builder()
                .calleeExpression("requests.get")
                .lineNumber(1)
                .enclosingFunction("f")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getProjectPath()).isEqualTo(PROJECT_PATH);
    }

    @Test
    @DisplayName("URL extracted correctly from firstStringArg")
    void urlExtracted() {
        PyCall call = PyCall.builder()
                .calleeExpression("httpx.get")
                .lineNumber(7)
                .enclosingFunction("call_api")
                .firstStringArg("https://api.example.com/v1/items")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getUrl()).isEqualTo("https://api.example.com/v1/items");
    }

    @Test
    @DisplayName("client.post matched as heuristic instance call")
    void clientPost() {
        PyCall call = PyCall.builder()
                .calleeExpression("client.post")
                .lineNumber(12)
                .enclosingFunction("upload")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getLibrary()).isEqualTo("client");
        assertThat(results.get(0).getHttpMethod()).isEqualTo("POST");
    }

    @Test
    @DisplayName("null module returns empty list")
    void nullModule() {
        List<PythonHttpCall> results = scanner.scanModule(null, PROJECT_PATH, null);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("requests.request detected as REQUEST method")
    void requestsRequest() {
        PyCall call = PyCall.builder()
                .calleeExpression("requests.request")
                .lineNumber(1)
                .enclosingFunction("generic_call")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getHttpMethod()).isEqualTo("REQUEST");
    }

    @Test
    @DisplayName("single-segment callee expression is ignored")
    void singleSegmentIgnored() {
        PyCall call = PyCall.builder()
                .calleeExpression("get")
                .lineNumber(1)
                .enclosingFunction("f")
                .build();
        PyModule module = moduleWith(call);

        List<PythonHttpCall> results = scanner.scanModule(module, PROJECT_PATH, null);
        assertThat(results).isEmpty();
    }

    private static PyModule moduleWith(PyCall... calls) {
        return PyModule.builder()
                .filePath("app.py")
                .modulePath("app")
                .calls(List.of(calls))
                .build();
    }
}
