package com.huawei.hisi.knowledgegraph.python;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Lexer;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
import com.huawei.hisi.knowledgegraph.service.storage.Neo4jStorageService;
import com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils;
import com.huawei.hisi.neo4j.model.MethodNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.springframework.stereotype.Service;

/**
 * Builds knowledge-graph {@link MethodNode}s from Python source files.
 *
 * <p>Provides single-file parsing ({@link #parseFile}) and project-level
 * walking ({@link #buildProject}, {@link #buildAndSave}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonKnowledgeGraphBuilder {

    private static final String LANGUAGE = "python";

    private final Neo4jStorageService neo4jStorageService;

    /**
     * Parse a single Python file and return one {@link MethodNode} per
     * function/method found.
     *
     * @param filePath    absolute path to the {@code .py} file
     * @param projectPath root directory of the project
     * @return method nodes extracted from the file (never null)
     */
    public List<MethodNode> parseFile(String filePath, String projectPath) throws IOException {
        String source = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        String relativePath = KnowledgeGraphCommonUtils.relativeFilePath(projectPath, filePath);
        String modulePath = toModulePath(relativePath);

        Python3Lexer lexer = new Python3Lexer(CharStreams.fromString(source));
        Python3Parser parser = new Python3Parser(new CommonTokenStream(lexer));
        PyModule module = new PythonAstVisitor().visit(parser.file_input(), filePath, modulePath);

        List<MethodNode> nodes = new ArrayList<>();

        for (PyFunction func : module.getTopLevelFunctions()) {
            String signature = func.getQualName() + "(" + String.join(",", func.getParamNames()) + ")";
            String nodeIdSource = modulePath + "::" + signature;
            nodes.add(MethodNode.builder()
                    .nodeId(toNodeId(nodeIdSource))
                    .className(modulePath)
                    .methodName(func.getName())
                    .signature(signature)
                    .filePath(filePath)
                    .startLine(func.getLineStart())
                    .endLine(func.getLineEnd())
                    .language(LANGUAGE)
                    .projectPath(projectPath)
                    .build());
        }

        for (PyClass pyClass : module.getClasses()) {
            for (PyFunction method : pyClass.getMethods()) {
                String signature = pyClass.getName() + "." + method.getName()
                        + "(" + String.join(",", method.getParamNames()) + ")";
                String nodeIdSource = modulePath + "::" + signature;
                nodes.add(MethodNode.builder()
                        .nodeId(toNodeId(nodeIdSource))
                        .className(pyClass.getName())
                        .methodName(method.getName())
                        .signature(signature)
                        .filePath(filePath)
                        .startLine(method.getLineStart())
                        .endLine(method.getLineEnd())
                        .language(LANGUAGE)
                        .projectPath(projectPath)
                        .build());
            }
        }

        return Collections.unmodifiableList(nodes);
    }

    /**
     * Walk {@code projectPath} recursively, parse every {@code .py} file
     * (excluding paths matched by {@code excludePaths}), and return the
     * aggregated list of method nodes.
     *
     * <p>Files that fail to parse are logged and skipped.
     */
    public List<MethodNode> buildProject(String projectPath, List<String> excludePaths) throws IOException {
        List<String> effectiveExcludes = new ArrayList<>(
                com.huawei.hisi.service.CodeAnalysisCoreService.EXCLUDED_SCAN_DIRS);
        if (excludePaths != null) {
            effectiveExcludes.addAll(excludePaths);
        }
        List<MethodNode> result = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(Paths.get(projectPath))) {
            List<Path> pyFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> !KnowledgeGraphCommonUtils.shouldExclude(
                            p.toString(), effectiveExcludes))
                    .toList();

            for (Path pyFile : pyFiles) {
                try {
                    result.addAll(parseFile(pyFile.toString(), projectPath));
                } catch (Exception e) {
                    log.warn("Failed to parse Python file {}: {}", pyFile, e.getMessage());
                }
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Build the project and persist all nodes via Neo4j.
     *
     * @param projectPath       root directory
     * @param excludePaths      paths to exclude
     */
    public void buildAndSave(String projectPath, List<String> excludePaths)
            throws IOException {
        List<MethodNode> nodes = buildProject(projectPath, excludePaths);
        neo4jStorageService.saveMethodNodes(nodes);
        log.info("[Python] Saved {} method nodes for project {}", nodes.size(), projectPath);
    }

    /**
     * Convert a relative file path to a dotted Python module path.
     * E.g. {@code "app/api/users.py"} becomes {@code "app.api.users"}.
     */
    public static String toModulePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        String normalized = relativePath.replace('\\', '/');
        if (normalized.endsWith(".py")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized.replace('/', '.');
    }

    /**
     * Generate a 16-character hex node ID from the given source string
     * using SHA-256.
     */
    public static String toNodeId(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
