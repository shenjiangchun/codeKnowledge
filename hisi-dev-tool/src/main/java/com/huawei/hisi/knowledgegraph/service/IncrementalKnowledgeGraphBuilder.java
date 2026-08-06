package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.model.ClassExtends;
import com.huawei.hisi.knowledgegraph.model.InterfaceImplementation;
import com.huawei.hisi.knowledgegraph.model.MethodOverride;
import com.huawei.hisi.knowledgegraph.model.ProxyRelation;
import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.PythonFrameworkDetector;
import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonHttpCall;
import com.huawei.hisi.knowledgegraph.python.scanner.PythonMqCall;
import com.huawei.hisi.knowledgegraph.scanner.MyBatisXmlScanner;
import com.huawei.hisi.knowledgegraph.python.scanner.DjangoUrlScanner;
import com.huawei.hisi.knowledgegraph.util.ProjectLanguageDetector;
import com.huawei.hisi.knowledgegraph.util.ProjectLanguageDetector.Language;
import com.huawei.hisi.neo4j.model.DataModelNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.utils.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Incremental knowledge graph refresh service.
 *
 * <p>Uses composition (not inheritance) to orchestrate incremental refresh by
 * calling {@link KnowledgeGraphBuilder}'s protected methods. The incremental
 * flow differs from full build in exactly 3 places:
 * <ol>
 *   <li>{@code cleanOldData()} → {@code DETACH DELETE} changed file nodes only</li>
 *   <li>Pass 1 scans only changed files (not all files)</li>
 *   <li>Call relations are full-scanned but filtered to rebuilt-node-involving edges</li>
 * </ol>
 * All other 20 post-processing steps are identical to the full build.
 *
 * <p>Shares {@link KnowledgeGraphBuilder#generationSemaphore} with full
 * build for mutual exclusion.
 */
@Slf4j
@Service
public class IncrementalKnowledgeGraphBuilder {

    private final KnowledgeGraphBuilder kgb;
    private final PythonCallGraphResolver pythonCallGraphResolver;

    public IncrementalKnowledgeGraphBuilder(
            KnowledgeGraphBuilder kgb,
            PythonCallGraphResolver pythonCallGraphResolver) {
        this.kgb = kgb;
        this.pythonCallGraphResolver = pythonCallGraphResolver;
    }

    /**
     * Refresh result with statistics.
     */
    public record RefreshResult(
            String projectPath,
            String lastCommit,
            String currentCommit,
            int changedFiles,
            int deletedNodes,
            int rebuiltNodes,
            int rebuiltEdges,
            int rebuiltEntryPoints,
            int vectorsGenerated,
            boolean success
    ) {
        public static RefreshResult noop() {
            return new RefreshResult(null, null, null, 0, 0, 0, 0, 0, 0, true);
        }
    }

    // ==================== Public Entry Point ====================

    /**
     * Incrementally refresh the knowledge graph for a project.
     *
     * @param projectPath the project path
     * @return refresh result with statistics
     */
    public RefreshResult incrementalRefresh(String projectPath) {
        if (!kgb.generationSemaphore.tryAcquire()) {
            throw new IllegalStateException("知识图谱生成任务正在执行中，请稍后再试（同一时刻仅允许一个项目生成）");
        }
        try {
            return doIncrementalRefresh(projectPath);
        } finally {
            kgb.generationSemaphore.release();
        }
    }

    private RefreshResult doIncrementalRefresh(String projectPath) {
        String normalizedPath = PathUtils.normalize(projectPath);
        log.info("[IncRefresh] Starting incremental refresh: {}", normalizedPath);

        try {
            // 1. Validate checkpoint
            Optional<GenerationCheckpointNode> checkpoint =
                    kgb.checkpointRepository.findByProjectPath(normalizedPath);
            if (checkpoint.isEmpty()) {
                throw new NoCheckpointException(normalizedPath);
            }

            String lastCommit = checkpoint.get().getLastCommit();
            String currentCommit = kgb.gitStatusService.getCurrentCommitHash(normalizedPath);

            if (currentCommit != null && currentCommit.equals(lastCommit)) {
                log.info("[IncRefresh] No changes (same commit)");
                return RefreshResult.noop();
            }

            // 2. Get changed files
            List<String> changedFiles = kgb.gitStatusService.getChangedFilesJgit(
                    normalizedPath, lastCommit, currentCommit);
            if (changedFiles.isEmpty()) {
                log.info("[IncRefresh] No changed files");
                return RefreshResult.noop();
            }

            List<String> javaFiles = changedFiles.stream()
                    .filter(f -> f.endsWith(".java")).collect(Collectors.toList());
            List<String> pythonFiles = changedFiles.stream()
                    .filter(f -> f.endsWith(".py")).collect(Collectors.toList());

            // 3. Language detection
            Language language = ProjectLanguageDetector.detectLanguage(normalizedPath);
            log.info("[IncRefresh] Language: {}, {} Java files, {} Python files changed",
                    language, javaFiles.size(), pythonFiles.size());

            // 4. Execute language-specific incremental refresh
            if (language == Language.PYTHON) {
                return pythonIncrementalRefresh(normalizedPath, pythonFiles, currentCommit);
            }

            // Java (or mixed) project
            RefreshResult result = javaIncrementalRefresh(normalizedPath, javaFiles,
                    pythonFiles, currentCommit);

            // Update checkpoint
            String branch = kgb.gitStatusService.getCurrentBranch(normalizedPath);
            kgb.checkpointRepository.upsertCheckpoint(normalizedPath,
                    currentCommit != null ? currentCommit : "NO_COMMIT", branch);

            return result;
        } catch (NoCheckpointException e) {
            throw e;
        } catch (IOException e) {
            log.error("[IncRefresh] Git diff failed: {}", e.getMessage(), e);
            return new RefreshResult(normalizedPath, null, null, 0, 0, 0, 0, 0, 0, false);
        } catch (Exception e) {
            log.error("[IncRefresh] Refresh failed: {}", e.getMessage(), e);
            return new RefreshResult(normalizedPath, null, null, 0, 0, 0, 0, 0, 0, false);
        }
    }

    // ==================== Java Incremental Refresh ====================

    private RefreshResult javaIncrementalRefresh(
            String projectPath, List<String> javaFiles, List<String> pythonFiles,
            String currentCommit) throws IOException {

        long startTime = System.currentTimeMillis();
        int totalChangedFiles = javaFiles.size() + pythonFiles.size();

        // ── Phase A: Full scan to initialize GlobalAnalysisCache ──
        log.info("[IncRefresh] Phase A: Initializing caches");
        initializeCaches(projectPath);

        // Scan MyBatis XML (full scan — XML parsing is cheap)
        MyBatisXmlScanner.Neo4jScanResult myBatisResult =
                kgb.myBatisXmlScanner.scanProjectForNeo4j(projectPath, null);
        log.info("[IncRefresh] MyBatis: {} Mapper, {} SQL",
                myBatisResult.getMapperCount(), myBatisResult.getSqlCount());

        // ── Phase B: Selective cleanup ──
        log.info("[IncRefresh] Phase B: Selective cleanup");
        List<String> allChangedFiles = new ArrayList<>();
        allChangedFiles.addAll(javaFiles);
        allChangedFiles.addAll(pythonFiles);

        // Step B1: Delete entry points FIRST — their delete Cypher uses
        // EXISTS { MATCH (m:Method {nodeId: entry.methodNodeId}) } which
        // fails if the Method node is already gone.
        for (String file : allChangedFiles) {
            Path fp = Paths.get(projectPath, file);
            String normalizedFilePath = PathUtils.normalize(fp.toString());
            kgb.neo4jEntryPointNodeRepository.deleteByFilePathAndProjectPath(
                    normalizedFilePath, projectPath);
        }

        // Step B2: Delete Method nodes (DETACH DELETE cascades all edges)
        int deletedNodes = cleanupChangedNodes(projectPath, allChangedFiles);

        // Delete dispatch-typed CALLS edges (will be rebuilt in Phase E)
        kgb.neo4jMethodNodeRepository.deleteDispatchCallsByProject(projectPath);

        // Build TypeSolver
        List<Path> sourceRoots = kgb.coreService.findSourceRoots(Paths.get(projectPath));
        kgb.globalCache.setTypeSolver(kgb.buildSolver(sourceRoots));
        JavaParser javaParser = kgb.coreService.createJavaParser(kgb.globalCache.getTypeSolver());

        // ── Phase C: Rebuild method nodes + entry points (changed files only) ──
        log.info("[IncRefresh] Phase C: Rebuilding nodes + entry points");

        List<MethodNode> allRebuiltNodes = new ArrayList<>();
        Set<String> rebuiltNodeIds = new HashSet<>();
        List<EntryPointNode> allEntryPoints = new ArrayList<>();

        // Pre-load ALL existing nodeId mappings (needed for Phase D cross-file resolution)
        Map<String, String> methodSignatureToNodeId = new HashMap<>();
        Map<String, String> methodFullKeyToNodeId = new HashMap<>();
        kgb.neo4jMethodNodeRepository.findByProjectPath(projectPath).forEach(node -> {
            String sigHash = node.getSignature() != null
                    ? kgb.signatureHash(node.getSignature()) : "0";
            methodSignatureToNodeId.put(
                    node.getClassName() + "." + node.getMethodName(), node.getNodeId());
            methodFullKeyToNodeId.put(
                    node.getClassName() + "." + node.getMethodName() + "." + sigHash,
                    node.getNodeId());
        });

        // Java: scan changed files
        if (!javaFiles.isEmpty()) {
            for (String file : javaFiles) {
                Path fp = Paths.get(projectPath, file);
                if (!Files.exists(fp)) continue;

                CompilationUnit cu = kgb.coreService.parseFile(fp.toFile(), javaParser);
                if (cu == null) continue;

                String normalizedFilePath = PathUtils.normalize(fp.toString());

                // Method nodes (full fields via parent's scanMethodNodes)
                List<MethodNode> methodNodes = kgb.scanMethodNodes(
                        cu, normalizedFilePath, projectPath);
                allRebuiltNodes.addAll(methodNodes);

                for (MethodNode node : methodNodes) {
                    rebuiltNodeIds.add(node.getNodeId());
                    String sigHash = node.getSignature() != null
                            ? kgb.signatureHash(node.getSignature()) : "0";
                    methodSignatureToNodeId.put(
                            node.getClassName() + "." + node.getMethodName(),
                            node.getNodeId());
                    methodFullKeyToNodeId.put(
                            node.getClassName() + "." + node.getMethodName() + "." + sigHash,
                            node.getNodeId());
                }

                // Entry points (full 7 types via parent's createEntryPoints)
                List<EntryPointNode> entryPoints = kgb.createEntryPoints(cu, projectPath);
                allEntryPoints.addAll(entryPoints);

                // Update GlobalCache implementation map
                kgb.coreService.buildImplementationMap(cu);
            }

            // Save method nodes via storageService (15 fields complete)
            kgb.storageService.saveMethodNodes(allRebuiltNodes);
            log.info("[IncRefresh] Java: {} method nodes rebuilt", allRebuiltNodes.size());

            // Save entry points
            kgb.storageService.saveEntryPoints(allEntryPoints);
            log.info("[IncRefresh] Java: {} entry points rebuilt", allEntryPoints.size());
        }

        // Python: scan changed files
        if (!pythonFiles.isEmpty()) {
            List<MethodNode> pyNodes = new ArrayList<>();
            List<EntryPointNode> pyEntryPoints = new ArrayList<>();

            for (String file : pythonFiles) {
                Path fp = Paths.get(projectPath, file);
                if (!Files.exists(fp)) continue;

                String normalizedFilePath = PathUtils.normalize(fp.toString());

                // Method nodes
                List<MethodNode> parsed = kgb.pythonKnowledgeGraphBuilder.parseFile(
                        normalizedFilePath, projectPath);
                pyNodes.addAll(parsed);
                parsed.forEach(n -> rebuiltNodeIds.add(n.getNodeId()));

                // Entry points
                pyEntryPoints.addAll(kgb.pythonKnowledgeGraphBuilder
                        .buildFileEntryPoints(normalizedFilePath, projectPath));
            }

            // Save via storageService (15 fields complete — same path as full build)
            if (!pyNodes.isEmpty()) {
                kgb.storageService.saveMethodNodes(pyNodes);
                log.info("[IncRefresh] Python: {} method nodes rebuilt", pyNodes.size());
            }
            if (!pyEntryPoints.isEmpty()) {
                kgb.storageService.saveEntryPoints(pyEntryPoints);
                log.info("[IncRefresh] Python: {} entry points rebuilt", pyEntryPoints.size());
            }
        }

        // ── Phase D: Call relations (full scan, filter to rebuiltNodeIds) ──
        log.info("[IncRefresh] Phase D: Rebuilding call relations");
        List<Map<String, Object>> allCallRelations = new ArrayList<>();

        // Java call relations
        if (!javaFiles.isEmpty()) {
            List<File> allJavaFiles = kgb.coreService.findJavaFiles(
                    projectPath, Collections.emptyList());
            for (File javaFile : allJavaFiles) {
                CompilationUnit cu = kgb.coreService.parseFile(javaFile, javaParser);
                if (cu == null) continue;
                allCallRelations.addAll(kgb.scanCallRelationsWithCoreService(
                        cu, projectPath, methodSignatureToNodeId,
                        methodFullKeyToNodeId, javaParser));
            }
        }

        // Python call relations
        if (!pythonFiles.isEmpty()) {
            List<PyModule> allModules = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(Paths.get(projectPath))) {
                paths.filter(p -> p.toString().endsWith(".py")).forEach(p -> {
                    try {
                        PythonKnowledgeGraphBuilder.ParsedFile parsed =
                                kgb.pythonKnowledgeGraphBuilder.parseFileWithModule(
                                        p.toString(), projectPath);
                        allModules.add(parsed.module());
                    } catch (Exception e) {
                        log.warn("[IncRefresh] Skip Python file {}: {}", p, e.getMessage());
                    }
                });
            }
            allCallRelations.addAll(pythonCallGraphResolver.resolveProject(
                    allModules, projectPath));
        }

        // Filter: only relations involving rebuilt nodes
        List<Map<String, Object>> filteredRelations = allCallRelations.stream()
                .filter(rel -> {
                    String callerId = (String) rel.get("callerId");
                    String calleeId = (String) rel.get("calleeId");
                    return rebuiltNodeIds.contains(callerId)
                            || rebuiltNodeIds.contains(calleeId);
                })
                .collect(Collectors.toList());

        log.info("[IncRefresh] Call relations: {} total, {} filtered ({} rebuilt nodeIds)",
                allCallRelations.size(), filteredRelations.size(), rebuiltNodeIds.size());

        // ── Phase E: Structural edges + bridge + dispatch ──
        log.info("[IncRefresh] Phase E: Rebuilding structural edges");

        // E0. Bridge call identification — MUST run before saveCallRelations
        // so that bridgeType/sqlId annotations are written to Neo4j on first save.
        List<Map<String, Object>> bridgeRelations = kgb.identifyBridgeCalls(
                filteredRelations, projectPath);

        // Now save filteredRelations WITH bridge annotations included
        if (!filteredRelations.isEmpty()) {
            kgb.storageService.saveCallRelations(filteredRelations);
        }

        // Save bridge-created MQ/Feign relations
        if (!bridgeRelations.isEmpty()) {
            filteredRelations.addAll(bridgeRelations);
            kgb.storageService.saveCallRelations(bridgeRelations);
            log.info("[IncRefresh] Bridge relations: {}", bridgeRelations.size());
        }

        // E1. IMPLEMENTS
        List<InterfaceImplementation> impls = kgb.convertFromGlobalCache(projectPath);
        kgb.storageService.saveInterfaceImplementations(impls);
        log.info("[IncRefresh] IMPLEMENTS: {}", impls.size());

        // E2. EXTENDS
        List<ClassExtends> extendsRelations = kgb.buildExtendsRelations(projectPath);
        kgb.storageService.saveClassExtends(extendsRelations);
        log.info("[IncRefresh] EXTENDS: {}", extendsRelations.size());

        // E3. Synthesize inherited method nodes (BEFORE OVERRIDE — matches full build order)
        List<MethodNode> allMethodNodes =
                kgb.neo4jMethodNodeRepository.findByProjectPath(projectPath);
        List<MethodNode> syntheticNodes = kgb.synthesizeInheritedMethodNodes(
                allMethodNodes, projectPath);
        if (!syntheticNodes.isEmpty()) {
            kgb.storageService.saveMethodNodes(syntheticNodes);
            // Re-fetch: synthetics are now in Neo4j, need them for OVERRIDE matching
            allMethodNodes = kgb.neo4jMethodNodeRepository.findByProjectPath(projectPath);
            log.info("[IncRefresh] Synthetic method nodes: {}", syntheticNodes.size());
        }

        // E4. OVERRIDE (now includes synthetics in allMethodNodes — same as full build)
        List<MethodOverride> overrides = kgb.buildOverrideRelations(
                projectPath, allMethodNodes);
        kgb.storageService.saveMethodOverrides(overrides);
        log.info("[IncRefresh] OVERRIDE: {}", overrides.size());

        // E5. PROXY
        List<ProxyRelation> proxies = kgb.buildProxyRelations(projectPath);
        kgb.storageService.saveProxyRelations(proxies);
        log.info("[IncRefresh] PROXY: {}", proxies.size());

        // E7. Dispatch edges (先删后建 — already deleted in Phase B)
        kgb.neo4jMethodNodeRepository.createImplDispatchEdges(projectPath);
        kgb.neo4jMethodNodeRepository.createFeignBridgeEdges(projectPath);
        log.info("[IncRefresh] Dispatch edges rebuilt");

        // ── Phase F: Post-processing ──
        log.info("[IncRefresh] Phase F: Post-processing");

        // F1. SQL nodes (full MyBatis XML rescan — cheap)
        List<com.huawei.hisi.neo4j.model.SqlNode> sqlNodes = myBatisResult.getSqlNodes();
        if (!sqlNodes.isEmpty()) {
            List<Map<String, Object>> sqlNodeMaps = sqlNodes.stream().map(s -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("nodeId", s.getNodeId());
                map.put("sqlId", s.getSqlId());
                map.put("statementType", s.getStatementType());
                map.put("sqlStatement", s.getSqlStatement());
                map.put("parameterType", s.getParameterType());
                map.put("resultType", s.getResultType());
                map.put("resultMap", s.getResultMap());
                map.put("mapperInterface", s.getMapperInterface());
                map.put("methodName", s.getMethodName());
                map.put("xmlFilePath", s.getXmlFilePath());
                map.put("projectPath", s.getProjectPath());
                map.put("language", s.getLanguage());
                map.put("framework", s.getFramework());
                return map;
            }).toList();
            kgb.neo4jSqlNodeRepository.mergeAll(sqlNodeMaps);
        }

        // F2. EXECUTES_SQL
        List<Map<String, Object>> execRelations = kgb.buildExecutesSqlRelations(
                filteredRelations, sqlNodes, methodSignatureToNodeId);
        if (!execRelations.isEmpty()) {
            kgb.neo4jSqlNodeRepository.createExecutesSqlRelations(execRelations);
            log.info("[IncRefresh] EXECUTES_SQL: {}", execRelations.size());
        }

        // F3. DataModel + USES_MODEL
        if (!javaFiles.isEmpty()) {
            List<DataModelNode> dataModels = new ArrayList<>();
            for (String file : javaFiles) {
                Path fp = Paths.get(projectPath, file);
                if (!Files.exists(fp)) continue;
                CompilationUnit cu = kgb.coreService.parseFile(fp.toFile(), javaParser);
                if (cu == null) continue;
                dataModels.addAll(kgb.javaDataModelScanner.scanDataModels(
                        cu, PathUtils.normalize(fp.toString()), projectPath));
            }

            // Build complete dmClassNames from ALL DataModel nodes in Neo4j
            // (changed + unchanged). This ensures cross-file USES_MODEL edges
            // from changed-file methods to unchanged DataModel classes are created.
            List<File> allJavaFiles = kgb.coreService.findJavaFiles(
                    projectPath, Collections.emptyList());
            // Scan ALL files for DataModel class names (className extraction only — cheap)
            Set<String> dmClassNames = new HashSet<>();
            for (DataModelNode dm : dataModels) {
                dmClassNames.add(dm.getClassName());
            }
            // Also include unchanged DataModel class names from full scan
            for (File javaFile : allJavaFiles) {
                CompilationUnit cu = kgb.coreService.parseFile(javaFile, javaParser);
                if (cu == null) continue;
                for (DataModelNode dm : kgb.javaDataModelScanner.scanDataModels(
                        cu, PathUtils.normalize(javaFile.getAbsolutePath()), projectPath)) {
                    dmClassNames.add(dm.getClassName());
                }
            }

            if (!dataModels.isEmpty()) {
                kgb.neo4jDataModelNodeRepository.saveAll(dataModels);
            }

            // USES_MODEL: full scan of ALL files, using complete dmClassNames.
            // Filter: only save relations where the caller methodNodeId is in rebuiltNodeIds
            // (unchanged-caller edges already survived Phase B).
            List<Map<String, Object>> usesRelations = new ArrayList<>();
            for (File javaFile : allJavaFiles) {
                CompilationUnit cu = kgb.coreService.parseFile(javaFile, javaParser);
                if (cu == null) continue;
                List<Map<String, Object>> fileRelations =
                        kgb.javaDataModelScanner.scanUsesModelRelations(
                                cu, projectPath, dmClassNames, methodSignatureToNodeId);
                for (Map<String, Object> rel : fileRelations) {
                    String methodNodeId = (String) rel.get("methodNodeId");
                    if (rebuiltNodeIds.contains(methodNodeId)) {
                        usesRelations.add(rel);
                    }
                }
            }
            if (!usesRelations.isEmpty()) {
                kgb.neo4jDataModelNodeRepository.createUsesModelRelations(usesRelations);
            }
            log.info("[IncRefresh] DataModel: {} nodes, {} USES_MODEL",
                    dataModels.size(), usesRelations.size());
        }

        // F4. Vector generation
        int totalRebuilt = allRebuiltNodes.size() + syntheticNodes.size();
        if (totalRebuilt > 0) {
            kgb.vectorGenerationService.startVectorGeneration(projectPath);
        }

        // F5. Save generation log
        kgb.saveGenerationLog(projectPath,
                (int) kgb.neo4jMethodNodeRepository.countByProjectPath(projectPath),
                filteredRelations.size() + bridgeRelations.size(),
                allEntryPoints.size(), impls.size(), startTime);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[IncRefresh] Complete: {} files, {} deleted, {} rebuilt, {} edges, {}ms",
                totalChangedFiles, deletedNodes, totalRebuilt,
                filteredRelations.size(), elapsed);

        return new RefreshResult(projectPath, null, currentCommit, totalChangedFiles,
                deletedNodes, totalRebuilt, filteredRelations.size(),
                allEntryPoints.size(), 0, true);
    }

    // ==================== Python Incremental Refresh ====================

    private RefreshResult pythonIncrementalRefresh(
            String projectPath, List<String> pythonFiles, String currentCommit) {

        long startTime = System.currentTimeMillis();
        PythonKnowledgeGraphBuilder pyKgb = kgb.pythonKnowledgeGraphBuilder;

        // Phase A: Detect primary framework via full module scan (needed for entry points)
        List<PyModule> allModules = new ArrayList<>();
        List<MethodNode> allMethodNodes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(projectPath))) {
            paths.filter(p -> p.toString().endsWith(".py")).forEach(p -> {
                try {
                    PythonKnowledgeGraphBuilder.ParsedFile parsed =
                            pyKgb.parseFileWithModule(p.toString(), projectPath);
                    allModules.add(parsed.module());
                    allMethodNodes.addAll(parsed.nodes());
                } catch (Exception e) {
                    log.warn("[IncRefresh] Skip: {}", p);
                }
            });
        } catch (IOException e) {
            log.error("[IncRefresh] Python walk failed: {}", e.getMessage());
        }

        // Phase B: Cleanup — delete entry points FIRST (same as Java path)
        for (String file : pythonFiles) {
            Path fp = Paths.get(projectPath, file);
            if (!Files.exists(fp)) continue;
            String normalizedFilePath = PathUtils.normalize(fp.toString());
            kgb.neo4jEntryPointNodeRepository.deleteByFilePathAndProjectPath(
                    normalizedFilePath, projectPath);
        }
        int deletedNodes = cleanupChangedNodes(projectPath, pythonFiles);

        // Phase C: Rebuild changed-file MethodNodes
        List<MethodNode> rebuiltNodes = new ArrayList<>();
        Set<String> rebuiltNodeIds = new HashSet<>();
        List<EntryPointNode> allEntryPoints = new ArrayList<>();

        for (String file : pythonFiles) {
            Path fp = Paths.get(projectPath, file);
            if (!Files.exists(fp)) continue;

            String normalizedFilePath = PathUtils.normalize(fp.toString());
            try {
                List<MethodNode> nodes = pyKgb.parseFile(normalizedFilePath, projectPath);
                rebuiltNodes.addAll(nodes);
                nodes.forEach(n -> rebuiltNodeIds.add(n.getNodeId()));
            } catch (Exception e) {
                log.warn("[IncRefresh] Python parse failed: {} - {}", file, e.getMessage());
            }
        }

        // Save via storageService (15-field complete MERGE)
        if (!rebuiltNodes.isEmpty()) {
            kgb.storageService.saveMethodNodes(rebuiltNodes);
        }

        // C2: Python EXTENDS/OVERRIDE — rebuild inheritance edges
        pyKgb.buildAndSaveInheritanceRelations(allModules, allMethodNodes, projectPath);

        // C3: Entry points — buildFileEntryPoints per changed file + Django includes
        Map<String, PyModule> modulesByPath = allModules.stream()
                .collect(Collectors.toMap(m -> m.getFilePath(), m -> m, (a, b) -> a));
        for (String file : pythonFiles) {
            Path fp = Paths.get(projectPath, file);
            if (!Files.exists(fp)) continue;
            try {
                allEntryPoints.addAll(pyKgb.buildFileEntryPoints(
                        PathUtils.normalize(fp.toString()), projectPath));
            } catch (IOException e) {
                log.warn("[IncRefresh] Entry points failed: {}", file);
            }
        }

        // Django include resolution (cross-module prefix)
        List<DjangoUrlScanner.IncludeMapping> djangoIncludes = new ArrayList<>();
        for (PyModule module : allModules) {
            djangoIncludes.addAll(pyKgb.djangoUrlScanner.scanIncludes(module));
        }
        if (!djangoIncludes.isEmpty()) {
            pyKgb.djangoUrlScanner.applyIncludes(allEntryPoints, djangoIncludes, modulesByPath);
        }

        // HTTP/MQ bridge entry points + MAIN entry points (full scan)
        String primaryFramework = null;
        for (PyModule m : allModules) {
            for (com.huawei.hisi.knowledgegraph.python.model.PyImport imp : m.getImports()) {
                String name = imp.getModuleName();
                if (name != null && (name.contains("fastapi") || name.contains("flask")
                        || name.contains("django") || name.contains("celery"))) {
                    primaryFramework = name.split("\\.")[0];
                    break;
                }
            }
            if (primaryFramework != null) break;
        }
        List<Map<String, Object>> bridgeRelations = new ArrayList<>();
        for (PyModule module : allModules) {
            for (PythonHttpCall hc : pyKgb.pythonHttpCallScanner.scanModule(
                    module, projectPath, primaryFramework)) {
                String epId = projectPath + ":HTTP_BRIDGE_" + hc.getUrl();
                allEntryPoints.add(EntryPointNode.builder()
                        .entryId(epId)
                        .entryKey("HTTP:" + hc.getHttpMethod() + " " + hc.getUrl())
                        .entryType("HTTP_CALL").projectPath(projectPath).language("python").build());
                Map<String, Object> bridge = pyKgb.buildHttpBridgeEdge(
                        hc, allModules, projectPath);
                if (bridge != null) bridgeRelations.add(bridge);
            }
            for (PythonMqCall mc : pyKgb.pythonMqCallScanner.scanModule(
                    module, projectPath, primaryFramework)) {
                String epId = projectPath + ":MQ_BRIDGE_" + mc.getTopic();
                allEntryPoints.add(EntryPointNode.builder()
                        .entryId(epId)
                        .entryKey("MQ:" + mc.getTopic()).entryType("MQ_PRODUCER")
                        .projectPath(projectPath).language("python").build());
                Map<String, Object> bridge = pyKgb.buildMqBridgeEdge(
                        mc, allModules, projectPath);
                if (bridge != null) bridgeRelations.add(bridge);
            }
            // MAIN entry points
            if (module.getCalls().stream().anyMatch(c -> c.isInMainBlock())) {
                String nid = projectPath + ":" + module.getModulePath() + ".__main__";
                allEntryPoints.add(EntryPointNode.builder()
                        .entryId(projectPath + ":MAIN_" + nid)
                        .entryKey("MAIN:" + module.getModulePath()).entryType("MAIN")
                        .projectPath(projectPath).language("python").methodNodeId(nid).build());
            }
        }

        if (!allEntryPoints.isEmpty()) {
            kgb.storageService.saveEntryPoints(allEntryPoints);
        }
        if (!bridgeRelations.isEmpty()) {
            pyKgb.neo4jStorageService.saveBridgeRelations(bridgeRelations);
            log.info("[IncRefresh] Python bridge relations: {}", bridgeRelations.size());
        }

        // C4: Python DataModel scanning
        try {
            List<DataModelNode> pyDataModels = pyKgb.pythonDataModelScanner.scanDataModels(
                    allModules, projectPath);
            if (!pyDataModels.isEmpty()) {
                pyKgb.neo4jDataModelNodeRepository.saveAll(pyDataModels);
            }
            List<Map<String, Object>> pyUsesModel = pyKgb.pythonDataModelScanner
                    .scanUsesModelRelations(allModules, allMethodNodes, projectPath,
                            pyDataModels.stream().map(DataModelNode::getClassName)
                                    .collect(Collectors.toSet()));
            if (!pyUsesModel.isEmpty()) {
                pyKgb.neo4jDataModelNodeRepository.createUsesModelRelations(pyUsesModel);
            }
        } catch (Exception e) {
            log.warn("[IncRefresh] Python DataModel scanning failed: {}", e.getMessage());
        }

        // Phase D: Call relations (full scan, filter)
        List<Map<String, Object>> allCallRelations =
                pythonCallGraphResolver.resolveProject(allModules, projectPath);

        List<Map<String, Object>> filteredRelations = allCallRelations.stream()
                .filter(rel -> {
                    String callerId = (String) rel.get("callerId");
                    String calleeId = (String) rel.get("calleeId");
                    return rebuiltNodeIds.contains(callerId)
                            || rebuiltNodeIds.contains(calleeId);
                })
                .collect(Collectors.toList());

        if (!filteredRelations.isEmpty()) {
            kgb.storageService.saveCallRelations(filteredRelations);
        }

        // Phase F: Vector generation
        if (!rebuiltNodes.isEmpty()) {
            kgb.vectorGenerationService.startVectorGeneration(projectPath);
        }

        // Save generation log
        kgb.saveGenerationLog(projectPath,
                (int) kgb.neo4jMethodNodeRepository.countByProjectPath(projectPath),
                filteredRelations.size(), allEntryPoints.size(), 0, startTime);

        // Update checkpoint
        String branch = kgb.gitStatusService.getCurrentBranch(projectPath);
        kgb.checkpointRepository.upsertCheckpoint(projectPath,
                currentCommit != null ? currentCommit : "NO_COMMIT", branch);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[IncRefresh] Python complete: {} files, {} nodes, {} edges, {} entry points, {}ms",
                pythonFiles.size(), rebuiltNodes.size(), filteredRelations.size(),
                allEntryPoints.size(), elapsed);

        return new RefreshResult(projectPath, null, currentCommit, pythonFiles.size(),
                deletedNodes, rebuiltNodes.size(), filteredRelations.size(),
                allEntryPoints.size(), 0, true);
    }

    // ==================== Helpers ====================

    /**
     * Full scan to initialize GlobalAnalysisCache (implementationMap, extendMap, typeSolver,
     * bridge endpoints).
     */
    private void initializeCaches(String projectPath) {
        kgb.globalCache.clearAll();

        List<Path> sourceRoots = kgb.coreService.findSourceRoots(Paths.get(projectPath));
        kgb.globalCache.setTypeSolver(kgb.buildSolver(sourceRoots));
        JavaParser javaParser = kgb.coreService.createJavaParser(kgb.globalCache.getTypeSolver());

        List<File> allJavaFiles = kgb.coreService.findJavaFiles(projectPath, Collections.emptyList());
        log.info("[IncRefresh] Cache init: {} Java files", allJavaFiles.size());

        for (File javaFile : allJavaFiles) {
            CompilationUnit cu = kgb.coreService.parseFile(javaFile, javaParser);
            if (cu == null) continue;
            kgb.coreService.buildImplementationMap(cu);
        }

        List<Path> allFilePaths = allJavaFiles.stream()
                .map(File::toPath).collect(Collectors.toList());
        kgb.scanBridgeEndpointsPublic(allFilePaths, projectPath);

        log.info("[IncRefresh] Cache init complete: implMap={}, extendMap={}",
                kgb.globalCache.getImplementationMap().size(),
                kgb.globalCache.getExtendMap().size());
    }

    /**
     * DETACH DELETE MethodNodes from changed files and remove related edges.
     */
    private int cleanupChangedNodes(String projectPath, List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) return 0;

        log.info("[IncRefresh] Cleaning up {} changed files", changedFiles.size());
        List<String> deletedFilePaths = new ArrayList<>();
        int deletedNodes = 0;

        for (String file : changedFiles) {
            Path filePath = Paths.get(projectPath, file);
            String normalizedPath = PathUtils.normalize(filePath.toString());

            List<MethodNode> nodesInFile = kgb.neo4jMethodNodeRepository
                    .findByProjectPathAndFilePath(projectPath, normalizedPath);
            deletedNodes += nodesInFile.size();
            deletedFilePaths.add(normalizedPath);
        }

        // DETACH DELETE (cascades all edges)
        for (String filePath : deletedFilePaths) {
            kgb.neo4jMethodNodeRepository.detachDeleteByFilePathAndProjectPath(
                    filePath, projectPath);
        }

        // Delete incoming CALLS from unchanged files
        kgb.neo4jMethodNodeRepository.deleteIncomingCallsToDeletedFiles(
                deletedFilePaths, projectPath);

        log.info("[IncRefresh] Deleted {} nodes", deletedNodes);
        return deletedNodes;
    }
}
