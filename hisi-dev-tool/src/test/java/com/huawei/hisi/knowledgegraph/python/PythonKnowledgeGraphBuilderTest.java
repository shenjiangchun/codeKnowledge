package com.huawei.hisi.knowledgegraph.python;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.python.scanner.CeleryTaskScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.DjangoUrlScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FastApiRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FlaskRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonHttpCallScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonMqCallScanner;
import com.huawei.hisi.knowledgegraph.service.storage.Neo4jStorageService;
import com.huawei.hisi.neo4j.model.MethodNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PythonKnowledgeGraphBuilderTest {

    private final PythonKnowledgeGraphBuilder builder = newBuilder(mock(Neo4jStorageService.class));

    private static PythonKnowledgeGraphBuilder newBuilder(Neo4jStorageService storage) {
        return new PythonKnowledgeGraphBuilder(
                storage,
                new PythonCallGraphResolver(),
                new FastApiRouteScanner(),
                new DjangoUrlScanner(),
                new FlaskRouteScanner(),
                new PythonHttpCallScanner(),
                new PythonMqCallScanner(),
                new CeleryTaskScanner());
    }

    private Path writePy(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent() == null ? dir : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("parseFile: top-level function produces single node with module-as-class")
    void parseFile_topLevelFunction(@TempDir Path dir) throws IOException {
        Path file = writePy(dir, "greet.py", "def greet(name):\n    return 'hi ' + name\n");

        List<MethodNode> nodes = builder.parseFile(file.toString(), dir.toString());

        assertThat(nodes).hasSize(1);
        MethodNode node = nodes.get(0);
        assertThat(node.getMethodName()).isEqualTo("greet");
        assertThat(node.getClassName()).isEqualTo("greet");
        assertThat(node.getLanguage()).isEqualTo("python");
        assertThat(node.getProjectPath()).isEqualTo(dir.toString());
        assertThat(node.getFilePath()).isEqualTo(file.toString());
        assertThat(node.getSignature()).isEqualTo("greet(name)");
    }

    @Test
    @DisplayName("parseFile: class with two methods produces two nodes with class name")
    void parseFile_classWithMethods(@TempDir Path dir) throws IOException {
        String src = String.join("\n",
                "class Dog:",
                "    def bark(self):",
                "        pass",
                "    def fetch(self, item):",
                "        pass",
                "");
        Path file = writePy(dir, "dog.py", src);

        List<MethodNode> nodes = builder.parseFile(file.toString(), dir.toString());

        assertThat(nodes).hasSize(2);
        assertThat(nodes).allMatch(n -> "Dog".equals(n.getClassName()));
        assertThat(nodes).extracting(MethodNode::getMethodName)
                .containsExactlyInAnyOrder("bark", "fetch");
        assertThat(nodes).extracting(MethodNode::getSignature)
                .containsExactlyInAnyOrder("Dog.bark(self)", "Dog.fetch(self,item)");
    }

    @Test
    @DisplayName("parseFile: same source parsed twice yields identical nodeIds")
    void parseFile_signatureHash_isConsistent(@TempDir Path dir) throws IOException {
        Path file = writePy(dir, "stable.py", "def f(a, b):\n    return a + b\n");

        List<MethodNode> first = builder.parseFile(file.toString(), dir.toString());
        List<MethodNode> second = builder.parseFile(file.toString(), dir.toString());

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0).getNodeId()).isEqualTo(second.get(0).getNodeId());
    }

    @Test
    @DisplayName("parseFile: nodeId equals first 16 chars of SHA-256(modulePath::signature)")
    void parseFile_nodeId_matchesSpecFormat(@TempDir Path dir) throws IOException {
        Path subdir = dir.resolve("pkg");
        Files.createDirectories(subdir);
        Path file = writePy(subdir, "mod.py", "def f(x):\n    pass\n");

        List<MethodNode> nodes = builder.parseFile(file.toString(), dir.toString());

        assertThat(nodes).hasSize(1);
        String expected = sha256Hex16("pkg.mod::f(x)");
        assertThat(nodes.get(0).getNodeId()).isEqualTo(expected);
    }

    @Test
    @DisplayName("buildProject: excluded files are skipped")
    void buildProject_skipsExcludedPaths(@TempDir Path dir) throws IOException {
        writePy(dir, "keep.py", "def keep():\n    pass\n");
        writePy(dir, "skip.py", "def skip():\n    pass\n");

        List<MethodNode> nodes = builder.buildProject(dir.toString(), List.of("skip.py"));

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getMethodName()).isEqualTo("keep");
    }

    @Test
    @DisplayName("buildProject: failing file is skipped without throwing")
    void buildProject_skipsFailingFile(@TempDir Path dir) throws IOException {
        writePy(dir, "ok.py", "def ok():\n    pass\n");
        // Invalid Python that ANTLR cannot parse cleanly - missing colon, garbage.
        writePy(dir, "bad.py", "def bad(\n    @@@@@\n");

        List<MethodNode> nodes = builder.buildProject(dir.toString(), List.of());

        // We expect at least the valid file's node; bad file may either fail
        // or produce zero nodes — either way the call must not throw.
        assertThat(nodes).extracting(MethodNode::getMethodName).contains("ok");
    }

    @Test
    @DisplayName("buildAndSave: invokes Neo4j storage")
    void buildAndSave_saves(@TempDir Path dir) throws IOException {
        writePy(dir, "x.py", "def f():\n    pass\n");
        Neo4jStorageService storage = mock(Neo4jStorageService.class);
        PythonKnowledgeGraphBuilder b = newBuilder(storage);

        b.buildAndSave(dir.toString(), List.of());

        verify(storage).saveMethodNodes(anyList());
    }

    @Test
    @DisplayName("toModulePath: __init__.py stripped to package name")
    void toModulePath_initPy_stripsInit() {
        assertThat(PythonKnowledgeGraphBuilder.toModulePath("app/api/__init__.py"))
                .isEqualTo("app.api");
        assertThat(PythonKnowledgeGraphBuilder.toModulePath("app/__init__.py"))
                .isEqualTo("app");
        assertThat(PythonKnowledgeGraphBuilder.toModulePath("__init__.py"))
                .isEqualTo("__init__");
        assertThat(PythonKnowledgeGraphBuilder.toModulePath("app/api/views.py"))
                .isEqualTo("app.api.views");
    }

    private static String sha256Hex16(String src) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 16);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
