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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.huawei.hisi.knowledgegraph.model.ClassExtends;
import com.huawei.hisi.knowledgegraph.model.MethodOverride;
import com.huawei.hisi.knowledgegraph.python.PythonFrameworkDetector.Framework;
import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.model.PyCall;
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
     * Parse a single Python file and return entry points detected by framework scanners.
     * Runs FastAPI/Django/Flask/Celery scanners on the parsed module.
     * Used for incremental refresh of Python entry points.
     */
    public List<EntryPointNode> buildFileEntryPoints(String filePath, String projectPath) throws IOException {
        ParsedFile parsed = parseFileInternal(filePath, projectPath);
        PyModule module = parsed.module();

        Set<Framework> frameworks = PythonFrameworkDetector.detect(projectPath);
        List<EntryPointNode> entryPoints = new ArrayList<>();

        Map<String, PyModule> modulesByPath = new LinkedHashMap<>();
        modulesByPath.putIfAbsent(module.getModulePath(), module);

        if (frameworks.contains(Framework.DJANGO)) {
            entryPoints.addAll(djangoUrlScanner.scanModule(module, projectPath, modulesByPath));
        }
        if (frameworks.contains(Framework.FASTAPI)) {
            entryPoints.addAll(fastApiRouteScanner.scanModule(module, projectPath));
        }
        if (frameworks.contains(Framework.FLASK)) {
            entryPoints.addAll(flaskRouteScanner.scanModule(module, projectPath));
        }
        entryPoints.addAll(celeryTaskScanner.scanModule(module, projectPath));

        // Guard: clear dangling methodNodeId references
        clearDanglingMethodNodeIds(entryPoints, parsed.nodes());

        // Post-process: set serviceName for all EntryPoints
        applyServiceNames(entryPoints, projectPath);

        log.debug("[Python KG] Built {} entry points from file: {}", entryPoints.size(), filePath);
        return entryPoints;
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

        // Build and persist EXTENDS / OVERRIDE relations
        buildAndSaveInheritanceRelations(result.allModules, result.methodNodes, projectPath);

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
            long mainCallerEdges = persistableEdges.stream()
                    .filter(rel -> {
                        String cid = (String) rel.get("callerId");
                        return cid != null && validNodeIds.contains(cid)
                                && result.methodNodes.stream()
                                        .anyMatch(n -> cid.equals(n.getNodeId())
                                                && "__main__".equals(n.getMethodName()));
                    })
                    .count();
            log.info("[Python KG] Edge filter diagnostics: totalBefore={}, persistable={}, mainCallerEdges={}",
                    result.callRelations.size(), persistableEdges.size(), mainCallerEdges);
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
    // Inheritance relations (EXTENDS / OVERRIDE)
    // ------------------------------------------------------------------

    /**
     * Build EXTENDS and OVERRIDE relations from Python class inheritance,
     * then persist them to Neo4j.
     *
     * <p>For each class with base classes, resolves the base class reference
     * to an FQN via {@link PythonCallGraphResolver#resolveBaseClass}, then:
     * <ul>
     *   <li>Creates EXTENDS edges between child and parent class methods</li>
     *   <li>Creates OVERRIDE edges for methods with the same name in both</li>
     * </ul>
     */
    private void buildAndSaveInheritanceRelations(List<PyModule> allModules,
                                                   List<MethodNode> methodNodes,
                                                   String projectPath) {
        // Index: simple class name -> set of method names (for OVERRIDE detection)
        Map<String, Set<String>> methodsBySimpleClassName = new LinkedHashMap<>();
        for (MethodNode node : methodNodes) {
            String cls = node.getClassName();
            // Top-level functions have className == modulePath; skip those
            if (cls == null || cls.contains(".") || "__main__".equals(node.getMethodName())) {
                continue;
            }
            methodsBySimpleClassName.computeIfAbsent(cls, k -> new LinkedHashSet<>())
                    .add(node.getMethodName());
        }

        List<ClassExtends> extendsRelations = new ArrayList<>();
        List<MethodOverride> overrideRelations = new ArrayList<>();

        for (PyModule module : allModules) {
            for (PyClass pyClass : module.getClasses()) {
                if (pyClass.getBaseClasses() == null || pyClass.getBaseClasses().isEmpty()) {
                    continue;
                }
                String childSimpleName = pyClass.getName();
                Set<String> childMethodNames = methodsBySimpleClassName.get(childSimpleName);

                for (String baseText : pyClass.getBaseClasses()) {
                    String parentFqn = pythonCallGraphResolver.resolveBaseClass(baseText, module);
                    if (parentFqn == null) {
                        continue;
                    }
                    // Extract simple class name from FQN for matching (last segment)
                    String parentSimpleName = simpleNameFromFqn(parentFqn);

                    extendsRelations.add(ClassExtends.builder()
                            .subclass(childSimpleName)
                            .superclass(parentSimpleName)
                            .projectPath(projectPath)
                            .build());

                    // Detect overridden methods: methods present in both child and parent
                    Set<String> parentMethodNames = methodsBySimpleClassName.get(parentSimpleName);
                    if (childMethodNames == null || parentMethodNames == null) {
                        continue;
                    }
                    for (String methodName : childMethodNames) {
                        if (parentMethodNames.contains(methodName)) {
                            overrideRelations.add(MethodOverride.builder()
                                    .subclass(childSimpleName)
                                    .superclass(parentSimpleName)
                                    .methodName(methodName)
                                    .projectPath(projectPath)
                                    .build());
                        }
                    }
                }
            }
        }

        if (!extendsRelations.isEmpty()) {
            neo4jStorageService.saveClassExtends(extendsRelations);
            log.info("[Python KG] Saved {} EXTENDS relations", extendsRelations.size());
        }
        if (!overrideRelations.isEmpty()) {
            neo4jStorageService.saveMethodOverrides(overrideRelations);
            log.info("[Python KG] Saved {} OVERRIDE relations", overrideRelations.size());
        }
    }

    /**
     * Extract the simple class name from a fully qualified name.
     * E.g. "myapp.views.UserService" -> "UserService"
     */
    private static String simpleNameFromFqn(String fqn) {
        if (fqn == null) return null;
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
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

        // Create MAIN entry points for modules with if __name__ == "__main__": blocks
        Set<String> allNodeIds = allNodes.stream()
                .map(MethodNode::getNodeId).collect(Collectors.toSet());
        for (PyModule module : allModules) {
            boolean hasMainCalls = module.getCalls().stream()
                    .anyMatch(PyCall::isInMainBlock);
            if (!hasMainCalls) {
                continue;
            }
            String mainNodeId = toNodeId(module.getModulePath() + "::__main__()");
            if (!allNodeIds.contains(mainNodeId)) {
                continue;
            }
            entryPoints.add(EntryPointNode.builder()
                    .entryId(projectPath + ":MAIN:" + module.getModulePath())
                    .entryType(EntryPointNode.TYPE_MAIN)
                    .entryKey("__main__")
                    .entryInfo(module.getModulePath())
                    .projectPath(projectPath)
                    .language(LANGUAGE)
                    .methodNodeId(mainNodeId)
                    .build());
        }

        if (!djangoIncludes.isEmpty()) {
            DjangoUrlScanner.applyIncludes(entryPoints, djangoIncludes, modulesByPath);
        }

        // Post-process: set serviceName for all EntryPoints
        applyServiceNames(entryPoints, projectPath);

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
        source = normalizeFstringsForParser(source);
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
                    .complexity(calculateComplexity(filePath, func.getLineStart(), func.getLineEnd()))
                    .methodBody(extractMethodBody(filePath, func.getLineStart(), func.getLineEnd()))
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
                        .complexity(calculateComplexity(filePath, method.getLineStart(), method.getLineEnd()))
                        .methodBody(extractMethodBody(filePath, method.getLineStart(), method.getLineEnd()))
                        .build());
            }
        }

        // Create pseudo method node for if __name__ == "__main__": blocks
        boolean hasMainBlockCalls = module.getCalls().stream()
                .anyMatch(PyCall::isInMainBlock);
        if (hasMainBlockCalls) {
            String mainSignature = modulePath + ".__main__()";
            String mainNodeIdSource = modulePath + "::__main__()";
            nodes.add(MethodNode.builder()
                    .nodeId(toNodeId(mainNodeIdSource))
                    .className(modulePath)
                    .methodName("__main__")
                    .signature(mainSignature)
                    .filePath(filePath)
                    .startLine(0)
                    .endLine(0)
                    .language(LANGUAGE)
                    .projectPath(projectPath)
                    .build());
        }

        return new ParsedFile(module, Collections.unmodifiableList(nodes));
    }

    /**
     * Normalize PEP 701 f-strings so the ANTLR Python 3 grammar can parse them.
     *
     * <p>Python 3.12+ allows f-strings to reuse the same quote character inside
     * {@code {...}} expressions (e.g. {@code f"key={d["id"]}"}).  The ANTLR grammar
     * predates PEP 701 and cannot handle this, causing lexer errors.  We work around
     * it by converting inner quotes to the alternate style — exactly what pre-3.12
     * Python required.</p>
     */
    static String normalizeFstringsForParser(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int len = source.length();
        while (i < len) {
            // Detect f-string start: [fFrR]* followed by ' or "
            int prefixStart = i;
            while (i < len && isFstringPrefixChar(source.charAt(i))) {
                i++;
            }
            if (i > prefixStart && i < len && (source.charAt(i) == '"' || source.charAt(i) == '\'')) {
                char quote = source.charAt(i);
                // Check for triple-quoted
                boolean triple = (i + 2 < len && source.charAt(i + 1) == quote && source.charAt(i + 2) == quote);
                int contentStart = triple ? i + 3 : i + 1;
                // Copy prefix + opening quotes
                out.append(source, prefixStart, contentStart);
                // Find matching end, normalizing inner quotes inside {...}
                i = contentStart;
                int depth = 0;
                while (i < len) {
                    char c = source.charAt(i);
                    if (c == '\\' && i + 1 < len) {
                        out.append(c).append(source.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (c == '{') {
                        depth++;
                        out.append(c);
                        i++;
                        continue;
                    }
                    if (c == '}') {
                        depth--;
                        out.append(c);
                        i++;
                        continue;
                    }
                    // Inside expression: flip same-type quotes to alternate
                    if (depth > 0 && c == quote) {
                        out.append(quote == '"' ? '\'' : '"');
                        i++;
                        continue;
                    }
                    // Check for closing quote
                    if (depth == 0) {
                        if (triple) {
                            if (i + 2 < len && source.charAt(i) == quote && source.charAt(i + 1) == quote && source.charAt(i + 2) == quote) {
                                out.append(source, i, i + 3);
                                i += 3;
                                break;
                            }
                        } else {
                            if (c == quote) {
                                out.append(c);
                                i++;
                                break;
                            }
                        }
                    }
                    out.append(c);
                    i++;
                }
                continue;
            }
            // Not an f-string prefix — backtrack and copy the first char
            i = prefixStart;
            out.append(source.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static boolean isFstringPrefixChar(char c) {
        return c == 'f' || c == 'F' || c == 'r' || c == 'R';
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

    // ------------------------------------------------------------------
    // McCabe cyclomatic complexity
    // ------------------------------------------------------------------

    /**
     * Calculate McCabe cyclomatic complexity for a Python function by counting
     * decision-point keywords in the source body. Returns 1 (base complexity)
     * on any error or invalid input.
     */
    private int calculateComplexity(String filePath, int startLine, int endLine) {
        if (filePath == null || startLine <= 0 || endLine < startLine) {
            return 1;
        }
        try {
            List<String> allLines = Files.readAllLines(Path.of(filePath), StandardCharsets.UTF_8);
            if (startLine > allLines.size()) return 1;
            int end = Math.min(endLine, allLines.size());
            List<String> bodyLines = allLines.subList(startLine - 1, end);
            String body = String.join(" ", bodyLines);

            int complexity = 1; // Base complexity

            // Count decision points
            complexity += countOccurrences(body, "if ");
            complexity += countOccurrences(body, "elif ");
            complexity += countOccurrences(body, "for ");
            complexity += countOccurrences(body, "while ");
            complexity += countOccurrences(body, "except ");
            complexity += countOccurrences(body, " and ");
            complexity += countOccurrences(body, " or ");

            return Math.max(1, complexity);
        } catch (IOException e) {
            return 1;
        }
    }

    private static int countOccurrences(String text, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    /**
     * Extract and compress a method body from source file for storage in the
     * knowledge graph. Returns an empty string on any I/O error or invalid input.
     */
    private String extractMethodBody(String filePath, int startLine, int endLine) {
        if (filePath == null || startLine <= 0 || endLine < startLine) {
            return "";
        }
        try {
            List<String> allLines = Files.readAllLines(Path.of(filePath), StandardCharsets.UTF_8);
            if (startLine > allLines.size()) return "";
            int end = Math.min(endLine, allLines.size());
            List<String> bodyLines = allLines.subList(startLine - 1, end);
            // Compress: remove excessive whitespace, join to single line
            String body = String.join(" ", bodyLines)
                    .replaceAll("\\s+", " ")
                    .trim();
            // Truncate if too long (match Java pipeline behavior)
            if (body.length() > 2000) {
                body = body.substring(0, 2000);
            }
            return body;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Post-process EntryPoints to set serviceName based on module path.
     * serviceName format: projectShortName:moduleCoreName
     * e.g., hisi-devtool:user_service
     */
    private void applyServiceNames(List<EntryPointNode> entryPoints, String projectPath) {
        String projectShortName = extractProjectShortName(projectPath);
        for (int i = 0; i < entryPoints.size(); i++) {
            EntryPointNode ep = entryPoints.get(i);
            if (ep.getServiceName() == null || ep.getServiceName().isEmpty()) {
                String serviceName = projectShortName + ":" + extractModuleCoreName(ep.getEntryInfo());
                // Create new EntryPointNode with serviceName (record-like builder pattern)
                EntryPointNode updated = EntryPointNode.builder()
                        .entryId(ep.getEntryId())
                        .entryType(ep.getEntryType())
                        .entryKey(ep.getEntryKey())
                        .entryInfo(ep.getEntryInfo())
                        .projectPath(ep.getProjectPath())
                        .language(ep.getLanguage())
                        .framework(ep.getFramework())
                        .serviceName(serviceName)
                        .methodNodeId(ep.getMethodNodeId())
                        .build();
                entryPoints.set(i, updated);
            }
        }
    }

    /**
     * Extract short name from project path.
     * e.g., /path/to/hisi-dev-tool -> hisi-devtool
     */
    private String extractProjectShortName(String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) return "default";
        Path p = Paths.get(projectPath);
        String name = p.getFileName() != null ? p.getFileName().toString() : "default";
        return name.replaceAll("(^hisi-|-dev-tool$|-backend$|-service$|-api$)", "");
    }

    /**
     * Extract core module name from entry info (typically module path).
     * e.g., "services/user_service.py" -> "user"
     */
    private String extractModuleCoreName(String entryInfo) {
        if (entryInfo == null || entryInfo.isEmpty()) return "default";
        // entryInfo is typically a module path like "services/user_service.py"
        String[] parts = entryInfo.replace("\\", "/").split("/");
        String lastPart = parts[parts.length - 1];
        // Remove file extension and common suffixes
        return lastPart.replaceAll("(\\.py$|_service$|_handler$|_controller$|_api$)", "");
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
