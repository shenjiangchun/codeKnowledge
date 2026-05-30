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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.huawei.hisi.knowledgegraph.python.PythonFrameworkDetector.Framework;
import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Lexer;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
import com.huawei.hisi.knowledgegraph.python.scanner.CeleryTaskScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.DjangoUrlScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FastApiRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.FlaskRouteScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonHttpCall;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonHttpCallScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonMqCall;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonMqCallScanner;
import com.huawei.hisi.knowledgegraph.service.storage.Neo4jStorageService;
import com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils;
import com.huawei.hisi.neo4j.model.DataModelNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.springframework.stereotype.Service;

/**
 * Builds knowledge-graph data from Python source files.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Walk project, parse each {@code .py} file into a {@link PyModule}.</li>
 *   <li>Emit {@link MethodNode} for each function / method.</li>
 *   <li>Resolve intra-/inter-module calls into call relations
 *       via {@link PythonCallGraphResolver}.</li>
 *   <li>Detect frameworks (FastAPI / Django / Flask) and run the matching
 *       entry-point scanners + always-on HTTP/MQ/Celery scanners.</li>
 *   <li>Persist methods, call relations, entry points, and bridge edges
 *       to Neo4j.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonKnowledgeGraphBuilder {

    private static final String LANGUAGE = "python";

    private final Neo4jStorageService neo4jStorageService;
    private final PythonCallGraphResolver pythonCallGraphResolver;
    private final FastApiRouteScanner fastApiRouteScanner;
    private final DjangoUrlScanner djangoUrlScanner;
    private final FlaskRouteScanner flaskRouteScanner;
    private final PythonHttpCallScanner pythonHttpCallScanner;
    private final PythonMqCallScanner pythonMqCallScanner;
    private final CeleryTaskScanner celeryTaskScanner;
    private final PythonDataModelScanner pythonDataModelScanner;
    private final Neo4jDataModelNodeRepository neo4jDataModelNodeRepository;

    /**
     * Parse a single Python file and return one {@link MethodNode} per
     * function/method found. (Kept for backwards compatibility / unit tests.)
     */
    public List<MethodNode> parseFile(String filePath, String projectPath) throws IOException {
        ParsedFile parsed = parseFileInternal(filePath, projectPath);
        return parsed.nodes();
    }

    /**
     * Walk {@code projectPath} recursively, parse every {@code .py} file
     * (excluding paths matched by {@code excludePaths}), and return the
     * aggregated list of method nodes.
     */
    public List<MethodNode> buildProject(String projectPath, List<String> excludePaths) throws IOException {
        BuildResult result = buildProjectInternal(projectPath, excludePaths);
        return result.methodNodes;
    }

    /**
     * Build the project and persist all nodes / relations / entry points / bridges via Neo4j.
     */
    public void buildAndSave(String projectPath, List<String> excludePaths) throws IOException {
        BuildResult result = buildProjectInternal(projectPath, excludePaths);

        neo4jStorageService.saveMethodNodes(result.methodNodes);
        log.info("[Python KG] Saved {} method nodes for project {}",
                result.methodNodes.size(), projectPath);

        if (!result.callRelations.isEmpty()) {
            // Diagnostic: count resolved vs unresolved edges, and check node-id matches
            Set<String> validNodeIds = result.methodNodes.stream()
                    .map(MethodNode::getNodeId).collect(Collectors.toSet());
            int resolved = 0, unresolved = 0, callerMissing = 0, calleeMissing = 0, bothPresent = 0;
            for (Map<String, Object> rel : result.callRelations) {
                String callerId = (String) rel.get("callerId");
                String calleeId = (String) rel.get("calleeId");
                boolean isUnresolved = calleeId != null && calleeId.startsWith("unresolved:");
                if (isUnresolved) {
                    unresolved++;
                } else {
                    resolved++;
                }
                boolean callerOk = callerId != null && validNodeIds.contains(callerId);
                boolean calleeOk = calleeId != null && validNodeIds.contains(calleeId);
                if (!callerOk) callerMissing++;
                if (!calleeOk && !isUnresolved) calleeMissing++;
                if (callerOk && calleeOk) bothPresent++;
            }
            log.info("[Python KG] Call edge diagnostics: total={}, resolved={}, unresolved={}, "
                            + "callerMissing={}, calleeMissing(resolved)={}, bothNodesPresent={}",
                    result.callRelations.size(), resolved, unresolved,
                    callerMissing, calleeMissing, bothPresent);

            // Filter out edges where either caller or callee doesn't exist as a Method node,
            // to avoid silent MERGE failures in Neo4j.
            List<Map<String, Object>> persistableEdges = result.callRelations.stream()
                    .filter(rel -> {
                        String cid = (String) rel.get("callerId");
                        String eid = (String) rel.get("calleeId");
                        return cid != null && validNodeIds.contains(cid)
                                && eid != null && validNodeIds.contains(eid);
                    })
                    .collect(Collectors.toList());
            log.info("[Python KG] Persisting {} call relations (filtered from {} total)",
                    persistableEdges.size(), result.callRelations.size());
            if (!persistableEdges.isEmpty()) {
                neo4jStorageService.saveCallRelations(persistableEdges);
            }
            log.info("[Python KG] Saved {} call relations", persistableEdges.size());
        }

        if (!result.entryPoints.isEmpty()) {
            neo4jStorageService.saveEntryPoints(result.entryPoints);
            log.info("[Python KG] Saved {} entry points", result.entryPoints.size());
        }

        if (!result.bridgeRelations.isEmpty()) {
            neo4jStorageService.saveBridgeRelations(result.bridgeRelations);
            log.info("[Python KG] Saved {} bridge relations (HTTP/MQ)",
                    result.bridgeRelations.size());
        }

        // Scan Python data models and USES_MODEL relations (isolated, no impact on existing logic)
        try {
            List<DataModelNode> pyDataModels = pythonDataModelScanner.scanDataModels(
                    result.allModules, projectPath);
            if (!pyDataModels.isEmpty()) {
                Set<String> dmClassNames = pyDataModels.stream()
                    .map(DataModelNode::getClassName).collect(Collectors.toSet());
                List<Map<String, Object>> usesRelations =
                    pythonDataModelScanner.scanUsesModelRelations(
                        result.allModules, result.methodNodes, projectPath, dmClassNames);

                neo4jDataModelNodeRepository.saveAll(pyDataModels);
                if (!usesRelations.isEmpty()) {
                    neo4jDataModelNodeRepository.createUsesModelRelations(usesRelations);
                }
                log.info("[Python KG] 数据模型节点: {}, USES_MODEL 关系: {}",
                        pyDataModels.size(), usesRelations.size());
            }
        } catch (Exception e) {
            log.warn("[Python KG] 数据模型扫描异常（不影响核心图谱）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Internal pipeline
    // ------------------------------------------------------------------

    private BuildResult buildProjectInternal(String projectPath, List<String> excludePaths) throws IOException {
        List<String> effectiveExcludes = new ArrayList<>(
                com.huawei.hisi.service.CodeAnalysisCoreService.EXCLUDED_SCAN_DIRS);
        if (excludePaths != null) {
            effectiveExcludes.addAll(excludePaths);
        }

        log.info("[Python KG] Project path: {}", projectPath);
        log.info("[Python KG] Effective exclude patterns: {}", effectiveExcludes);

        Set<Framework> frameworks = PythonFrameworkDetector.detect(projectPath);
        String primaryFramework = pickPrimaryFramework(frameworks);

        List<MethodNode> allNodes = new ArrayList<>();
        List<PyModule> allModules = new ArrayList<>();

        Path projectDir = Paths.get(projectPath);
        log.info("[Python KG] Project directory exists? {}", Files.exists(projectDir));
        if (Files.exists(projectDir)) {
            log.info("[Python KG] Project directory is directory? {}", Files.isDirectory(projectDir));
        }

        try (Stream<Path> walk = Files.walk(projectDir)) {
            // 先收集所有文件，然后逐步过滤，记录每一步的情况
            List<Path> allFiles = walk.toList();
            log.info("[Python KG] Total files found during walk: {}", allFiles.size());

            List<Path> regularFiles = allFiles.stream()
                    .filter(Files::isRegularFile)
                    .toList();
            log.info("[Python KG] Regular files: {}", regularFiles.size());

            List<Path> pyFilesAll = regularFiles.stream()
                    .filter(p -> p.toString().endsWith(".py"))
                    .toList();
            log.info("[Python KG] All .py files: {}", pyFilesAll.size());

            // 记录被排除的文件
            List<Path> excludedFiles = new ArrayList<>();
            List<Path> pyFiles = pyFilesAll.stream()
                    .filter(p -> {
                        boolean excluded = KnowledgeGraphCommonUtils.shouldExclude(p.toString(), effectiveExcludes);
                        if (excluded) {
                            excludedFiles.add(p);
                        }
                        return !excluded;
                    })
                    .toList();

            if (!excludedFiles.isEmpty()) {
                log.info("[Python KG] Excluded files ({}):", excludedFiles.size());
                for (Path excluded : excludedFiles) {
                    log.info("[Python KG]   - Excluded: {}", excluded);
                }
            }

            log.info("[Python KG] Found {} Python files to parse", pyFiles.size());

            // 记录所有将被解析的文件
            log.info("[Python KG] Files to parse:");
            for (Path pyFile : pyFiles) {
                log.info("[Python KG]   - {}", pyFile);
            }

            for (Path pyFile : pyFiles) {
                try {
                    log.info("[Python KG] Parsing file: {}", pyFile);
                    ParsedFile parsed = parseFileInternal(pyFile.toString(), projectPath);
                    log.info("[Python KG] Parsed file {}: {} top-level functions, {} classes",
                            pyFile, parsed.module.getTopLevelFunctions().size(), parsed.module.getClasses().size());
                    allNodes.addAll(parsed.nodes());
                    allModules.add(parsed.module());
                } catch (Exception e) {
                    log.warn("Failed to parse Python file {}: {}", pyFile, e.getMessage(), e);
                }
            }
        }

        // Build modulesByPath for cross-module view-callable resolution (Django FBV / CBV).
        Map<String, PyModule> modulesByPath = new LinkedHashMap<>();
        for (PyModule m : allModules) {
            if (m.getModulePath() != null) {
                modulesByPath.putIfAbsent(m.getModulePath(), m);
            }
        }

        // Entry points: framework-gated + always-on Celery
        List<EntryPointNode> entryPoints = new ArrayList<>();
        List<PythonHttpCall> httpCalls = new ArrayList<>();
        List<PythonMqCall> mqCalls = new ArrayList<>();
        List<DjangoUrlScanner.IncludeMapping> djangoIncludes = new ArrayList<>();

        for (PyModule module : allModules) {
            if (frameworks.contains(Framework.DJANGO)) {
                entryPoints.addAll(djangoUrlScanner.scanModule(module, projectPath, modulesByPath));
                djangoIncludes.addAll(djangoUrlScanner.scanIncludes(module));
            }
            if (frameworks.contains(Framework.FASTAPI)) {
                entryPoints.addAll(fastApiRouteScanner.scanModule(module, projectPath));
            }
            if (frameworks.contains(Framework.FLASK)) {
                entryPoints.addAll(flaskRouteScanner.scanModule(module, projectPath));
            }
            entryPoints.addAll(celeryTaskScanner.scanModule(module, projectPath));

            httpCalls.addAll(pythonHttpCallScanner.scanModule(module, projectPath, primaryFramework));
            mqCalls.addAll(pythonMqCallScanner.scanModule(module, projectPath, primaryFramework));
        }

        if (!djangoIncludes.isEmpty()) {
            DjangoUrlScanner.applyIncludes(entryPoints, djangoIncludes, modulesByPath);
        }

        // Guard: clear methodNodeId references that don't point to a known Method node.
        // This prevents broken call-chain queries from returning empty for resolvable URLs
        // and surfaces resolution gaps in the logs for diagnosis.
        clearDanglingMethodNodeIds(entryPoints, allNodes);

        // Resolve intra-/cross-module call edges
        List<Map<String, Object>> callRelations = new ArrayList<>(
                pythonCallGraphResolver.resolveProject(allModules, projectPath));

        // Build bridge edges (caller method → synthetic endpoint id)
        List<Map<String, Object>> bridgeRelations = new ArrayList<>();
        for (PythonHttpCall c : httpCalls) {
            bridgeRelations.add(buildHttpBridgeEdge(c, allModules, projectPath));
        }
        for (PythonMqCall c : mqCalls) {
            bridgeRelations.add(buildMqBridgeEdge(c, allModules, projectPath));
        }

        log.info("[Python KG] Built methods={}, callRelations={}, entryPoints={}, "
                        + "httpBridges={}, mqBridges={}",
                allNodes.size(), callRelations.size(), entryPoints.size(),
                httpCalls.size(), mqCalls.size());

        return new BuildResult(
                Collections.unmodifiableList(allNodes),
                Collections.unmodifiableList(callRelations),
                Collections.unmodifiableList(entryPoints),
                Collections.unmodifiableList(bridgeRelations),
                Collections.unmodifiableList(allModules));
    }

    private ParsedFile parseFileInternal(String filePath, String projectPath) throws IOException {
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

        return new ParsedFile(module, Collections.unmodifiableList(nodes));
    }

    // ------------------------------------------------------------------
    // Bridge edge builders
    // ------------------------------------------------------------------

    private Map<String, Object> buildHttpBridgeEdge(PythonHttpCall call,
                                                    List<PyModule> allModules,
                                                    String projectPath) {
        String callerId = locateCallerNodeId(call.getFilePath(),
                call.getEnclosingFunction(), allModules);
        String endpoint = call.getHttpMethod() != null
                ? call.getHttpMethod() + " " + safe(call.getUrl())
                : safe(call.getUrl());
        String calleeId = "http-bridge:" + toNodeId(endpoint + ":" + safe(call.getLibrary()));

        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("callerId", callerId != null ? callerId : "unresolved:" + toNodeId(call.getFilePath() + ":" + call.getLineNumber()));
        rel.put("calleeId", calleeId);
        rel.put("callType", "HTTP");
        rel.put("bridgeType", "HTTP");
        rel.put("targetEndpoint", endpoint);
        rel.put("library", safe(call.getLibrary()));
        rel.put("callLine", call.getLineNumber());
        return rel;
    }

    private Map<String, Object> buildMqBridgeEdge(PythonMqCall call,
                                                  List<PyModule> allModules,
                                                  String projectPath) {
        String callerId = locateCallerNodeId(call.getFilePath(),
                call.getEnclosingFunction(), allModules);
        String topic = safe(call.getTopic());
        String calleeId = "mq-bridge:" + toNodeId(topic + ":" + safe(call.getLibrary()));

        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("callerId", callerId != null ? callerId : "unresolved:" + toNodeId(call.getFilePath() + ":" + call.getLineNumber()));
        rel.put("calleeId", calleeId);
        rel.put("callType", "MQ");
        rel.put("bridgeType", "MQ");
        rel.put("targetEndpoint", topic);
        rel.put("library", safe(call.getLibrary()));
        rel.put("callLine", call.getLineNumber());
        return rel;
    }

    /**
     * Locate the {@link MethodNode#getNodeId()} for the method enclosing a
     * call site by matching {@code filePath} and {@code enclosingFunction}
     * (which is the {@code qualName}: either {@code "func"} or {@code "Class.method"}).
     */
    private String locateCallerNodeId(String filePath, String enclosingFunction,
                                      List<PyModule> allModules) {
        if (filePath == null || enclosingFunction == null || enclosingFunction.isEmpty()) {
            return null;
        }
        for (PyModule module : allModules) {
            if (!filePath.equals(module.getFilePath())) {
                continue;
            }
            int dot = enclosingFunction.indexOf('.');
            if (dot < 0) {
                for (PyFunction f : module.getTopLevelFunctions()) {
                    if (f.getName().equals(enclosingFunction)) {
                        String sig = f.getQualName() + "(" + String.join(",", f.getParamNames()) + ")";
                        return toNodeId(module.getModulePath() + "::" + sig);
                    }
                }
            } else {
                String className = enclosingFunction.substring(0, dot);
                String methodName = enclosingFunction.substring(dot + 1);
                for (PyClass c : module.getClasses()) {
                    if (!c.getName().equals(className)) {
                        continue;
                    }
                    for (PyFunction m : c.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            String sig = className + "." + methodName
                                    + "(" + String.join(",", m.getParamNames()) + ")";
                            return toNodeId(module.getModulePath() + "::" + sig);
                        }
                    }
                }
            }
            return null;
        }
        return null;
    }

    /**
     * Drop {@code methodNodeId} references on entry points that don't point to
     * any known {@link MethodNode}. Without this guard, Cypher call-chain
     * queries silently return empty results when the resolver produced an ID
     * that doesn't match a real Method node (e.g. due to stale data, scanner
     * bug, or unresolved import).
     */
    private static void clearDanglingMethodNodeIds(List<EntryPointNode> entryPoints,
                                                   List<MethodNode> allNodes) {
        Set<String> validNodeIds = new HashSet<>(allNodes.size());
        for (MethodNode node : allNodes) {
            if (node.getNodeId() != null) {
                validNodeIds.add(node.getNodeId());
            }
        }
        int dangling = 0;
        for (EntryPointNode ep : entryPoints) {
            if (ep.getMethodNodeId() != null && !validNodeIds.contains(ep.getMethodNodeId())) {
                log.warn("[Python KG] EntryPoint {} ({}) methodNodeId={} 不指向任何 Method 节点,清零",
                        ep.getEntryKey(), ep.getFramework(), ep.getMethodNodeId());
                ep.setMethodNodeId(null);
                dangling++;
            }
        }
        if (dangling > 0) {
            log.warn("[Python KG] {} 个 EntryPoint methodNodeId 失效已清零", dangling);
        }
    }

    private static String pickPrimaryFramework(Set<Framework> frameworks) {
        if (frameworks.contains(Framework.FASTAPI)) {
            return "fastapi";
        }
        if (frameworks.contains(Framework.DJANGO)) {
            return "django";
        }
        if (frameworks.contains(Framework.FLASK)) {
            return "flask";
        }
        return null;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
        if (normalized.endsWith("/__init__")) {
            normalized = normalized.substring(0, normalized.length() - "/__init__".length());
        }
        return normalized.replace('/', '.');
    }

    /**
     * Compute the {@code methodNodeId} for a Python function/method given its
     * containing module path, qualified name ({@code "func"} or
     * {@code "Class.method"}), and parameter list. This is the inverse of
     * what {@link #parseFileInternal} writes into {@link MethodNode#getNodeId()}
     * and MUST stay in sync with that formula.
     */
    public static String computeMethodNodeId(String modulePath, String qualName, List<String> paramNames) {
        String params = paramNames == null ? "" : String.join(",", paramNames);
        String signature = qualName + "(" + params + ")";
        return toNodeId(modulePath + "::" + signature);
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

    private record ParsedFile(PyModule module, List<MethodNode> nodes) {
    }

    private record BuildResult(List<MethodNode> methodNodes,
                               List<Map<String, Object>> callRelations,
                               List<EntryPointNode> entryPoints,
                               List<Map<String, Object>> bridgeRelations,
                               List<PyModule> allModules) {
    }
}
