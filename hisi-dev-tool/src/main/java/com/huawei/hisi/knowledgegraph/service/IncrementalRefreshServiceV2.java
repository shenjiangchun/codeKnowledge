package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.call.PythonCallGraphResolver;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import com.huawei.hisi.knowledgegraph.service.GitStatusService;
import com.huawei.hisi.utils.PathUtils;
import lombok.RequiredArgsConstructor;
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
 * V2 implementation of incremental graph refresh.
 *
 * Key improvements over V1:
 * 1. Full scan to initialize GlobalAnalysisCache (implementationMap, extendMap, typeSolver)
 * 2. Delete all edges involving changed nodes (including reverse dependencies)
 * 3. Full scan to generate edges, but only record those involving changed nodes
 * 4. Vector generation for all nodes with empty description
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalRefreshServiceV2 {

    private final GlobalAnalysisCache globalCache;
    private final CodeAnalysisCoreService coreService;
    private final GitStatusService gitStatusService;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jGenerationCheckpointRepository checkpointRepository;
    private final KnowledgeGraphStorageService storageService;
    private final KnowledgeGraphBuilder knowledgeGraphBuilder;
    private final PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder;
    private final Neo4jEntryPointNodeRepository entryPointRepository;
    private final PythonCallGraphResolver pythonCallGraphResolver;

    /**
     * Refresh result containing statistics.
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

    /**
     * Debug method to check checkpoint status.
     */
    public CheckpointDebugResult debugCheckpoint(String projectPath) {
        String normalizedPath = PathUtils.normalize(projectPath);
        log.info("[V2 Debug] Checking checkpoint for: {}", normalizedPath);
        var checkpoint = checkpointRepository.findByProjectPath(normalizedPath);
        if (checkpoint.isPresent()) {
            log.info("[V2 Debug] Checkpoint found: lastCommit={}, lastBranch={}",
                checkpoint.get().getLastCommit(), checkpoint.get().getLastBranch());
            return new CheckpointDebugResult(checkpoint.get().getLastCommit(), checkpoint.get().getLastBranch());
        } else {
            log.warn("[V2 Debug] No checkpoint found for: {}", normalizedPath);
            // Try to save a checkpoint manually for testing
            String currentCommit = gitStatusService.getCurrentCommitHash(normalizedPath);
            log.info("[V2 Debug] Current commit from git: {}", currentCommit);
            checkpointRepository.upsertCheckpoint(normalizedPath, currentCommit != null ? currentCommit : "DEBUG_COMMIT", "debug");
            log.info("[V2 Debug] Manually saved checkpoint for: {}", normalizedPath);

            // Query again
            var afterSave = checkpointRepository.findByProjectPath(normalizedPath);
            if (afterSave.isPresent()) {
                log.info("[V2 Debug] After manual save, checkpoint found: {}", afterSave.get().getLastCommit());
                return new CheckpointDebugResult(afterSave.get().getLastCommit(), afterSave.get().getLastBranch());
            } else {
                log.error("[V2 Debug] Manual save failed - checkpoint still not found!");
                return null;
            }
        }
    }

    public record CheckpointDebugResult(String lastCommit, String lastBranch) {}

    /**
     * Initialize GlobalAnalysisCache by scanning all project files.
     * This ensures implementationMap, extendMap, and typeSolver are populated.
     */
    private void initializeCaches(String projectPath) {
        log.info("[V2] Initializing caches for project: {}", projectPath);

        // Clear existing caches
        globalCache.clearAll();

        // Build TypeSolver
        List<Path> sourceRoots = coreService.findSourceRoots(Paths.get(projectPath));
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        for (Path root : sourceRoots) {
            solver.add(new JavaParserTypeSolver(root));
        }
        globalCache.setTypeSolver(solver);
        JavaParser javaParser = coreService.createJavaParser(solver);

        // Find all Java files
        List<File> allJavaFiles = coreService.findJavaFiles(projectPath, Collections.emptyList());
        log.info("[V2] Found {} Java files for cache initialization", allJavaFiles.size());

        // Scan all files for implementationMap and extendMap
        int scanned = 0;
        for (File javaFile : allJavaFiles) {
            CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
            if (cu == null) continue;
            coreService.buildImplementationMap(cu);
            scanned++;
        }

        // Scan bridge endpoints (Feign, MQ, HTTP)
        List<Path> allFilePaths = allJavaFiles.stream()
            .map(File::toPath)
            .collect(Collectors.toList());
        knowledgeGraphBuilder.scanBridgeEndpointsPublic(allFilePaths, projectPath);

        log.info("[V2] Cache initialization complete: {} files scanned, implementationMap size={}, extendMap size={}",
            scanned,
            globalCache.getImplementationMap().size(),
            globalCache.getExtendMap().size());
    }

    /**
     * Delete MethodNodes from changed files and all related edges.
     * Includes both outgoing edges and incoming edges (reverse dependencies).
     */
    private int cleanupChangedNodes(String projectPath, List<String> changedFiles) {
        // Null safety check
        if (changedFiles == null || changedFiles.isEmpty()) {
            log.info("[V2] No changed files to clean up");
            return 0;
        }

        log.info("[V2] Cleaning up {} changed files", changedFiles.size());

        List<String> deletedFilePaths = new ArrayList<>();
        int deletedNodes = 0;

        for (String file : changedFiles) {
            Path filePath = Paths.get(projectPath, file);
            String absoluteFilePath = filePath.toString();
            // Normalize to forward slashes for consistent matching with Neo4j
            String normalizedFilePath = PathUtils.normalize(absoluteFilePath);

            if (!filePath.toFile().exists()) {
                // File was deleted - count nodes BEFORE adding to delete list
                List<MethodNode> nodesInDeletedFile = methodNodeRepository.findByProjectPathAndFilePath(
                    projectPath, normalizedFilePath);
                deletedNodes += nodesInDeletedFile.size();
                deletedFilePaths.add(normalizedFilePath);
                continue;
            }

            // Delete nodes from this file (DETACH DELETE removes node + edges)
            List<MethodNode> nodesInFile = methodNodeRepository.findByProjectPathAndFilePath(
                projectPath, normalizedFilePath);
            deletedNodes += nodesInFile.size();
            deletedFilePaths.add(normalizedFilePath);
        }

        // Delete nodes and outgoing edges
        for (String filePath : deletedFilePaths) {
            methodNodeRepository.detachDeleteByFilePathAndProjectPath(filePath, projectPath);
        }

        // Delete incoming CALLS edges (reverse dependencies)
        methodNodeRepository.deleteIncomingCallsToDeletedFiles(deletedFilePaths, projectPath);

        log.info("[V2] Deleted {} nodes and their edges (including reverse dependencies)", deletedNodes);
        return deletedNodes;
    }

    /**
     * Rebuild MethodNodes from changed files using MERGE.
     * Returns Set of rebuilt nodeIds for edge generation filtering.
     */
    private Set<String> rebuildChangedNodes(String projectPath, List<String> changedJavaFiles, JavaParser javaParser) {
        log.info("[V2] Rebuilding nodes from {} changed Java files", changedJavaFiles.size());

        List<MethodNode> rebuiltNodes = new ArrayList<>();
        Set<String> rebuiltNodeIds = new HashSet<>();

        for (String file : changedJavaFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!filePath.toFile().exists()) continue;

            CompilationUnit cu = coreService.parseFile(filePath.toFile(), javaParser);
            if (cu == null) continue;

            // Normalize filePath for consistent storage in Neo4j
            String normalizedFilePath = PathUtils.normalize(filePath.toString());
            // Use KnowledgeGraphBuilder's scanMethodNodes logic
            List<MethodNode> nodes = scanMethodNodes(cu, normalizedFilePath, projectPath);
            for (MethodNode node : nodes) {
                rebuiltNodes.add(node);
                rebuiltNodeIds.add(node.getNodeId());
            }
        }

        // MERGE all nodes (creates new or updates existing)
        if (!rebuiltNodes.isEmpty()) {
            methodNodeRepository.mergeAll(rebuiltNodes.stream()
                .map(this::methodNodeToMap)
                .collect(Collectors.toList()));
        }

        log.info("[V2] Rebuilt {} method nodes", rebuiltNodes.size());
        return rebuiltNodeIds;
    }

    /**
     * Convert MethodNode to Map for mergeAll.
     */
    private Map<String, Object> methodNodeToMap(MethodNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", node.getNodeId());
        map.put("className", node.getClassName());
        map.put("methodName", node.getMethodName());
        map.put("signature", node.getSignature());
        map.put("filePath", node.getFilePath());
        map.put("startLine", node.getStartLine());
        map.put("endLine", node.getEndLine());
        map.put("description", node.getDescription());
        map.put("projectPath", node.getProjectPath());
        map.put("language", node.getLanguage() != null ? node.getLanguage() : "java");
        return map;
    }

    /**
     * Scan method nodes from CompilationUnit (reuse KnowledgeGraphBuilder logic).
     */
    private List<MethodNode> scanMethodNodes(CompilationUnit cu, String filePath, String projectPath) {
        List<MethodNode> nodes = new ArrayList<>();
        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ? clazz.getNameAsString()
                : packageName + "." + clazz.getNameAsString();

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                String nodeId = projectPath + ":" + className + "." + method.getNameAsString();
                String sigHash = signatureHash(method.getSignature().toString());
                nodeId += "." + sigHash;

                MethodNode node = MethodNode.builder()
                    .nodeId(nodeId)
                    .className(className)
                    .methodName(method.getNameAsString())
                    .signature(method.getSignature().toString())
                    .filePath(filePath)
                    .startLine(method.getBegin().map(p -> p.line).orElse(0))
                    .endLine(method.getEnd().map(p -> p.line).orElse(0))
                    .projectPath(projectPath)
                    .language("java")
                    .build();

                nodes.add(node);
            });
        });

        return nodes;
    }

    private String signatureHash(String signature) {
        return Integer.toHexString(signature.hashCode());
    }

    /**
     * Generate CALLS edges by full scanning all files.
     * Only record edges involving changed nodes (caller OR callee is in rebuiltNodeIds).
     */
    private int rebuildEdges(String projectPath, Set<String> rebuiltNodeIds, JavaParser javaParser) {
        log.info("[V2] Rebuilding edges using KnowledgeGraphBuilder logic, filtering for {} changed nodes", rebuiltNodeIds.size());

        // Build both maps from Neo4j (all project nodes) - same format as full generation
        Map<String, String> methodSignatureToNodeId = new HashMap<>();
        Map<String, String> methodFullKeyToNodeId = new HashMap<>();

        methodNodeRepository.findByProjectPath(projectPath).forEach(node -> {
            String sigHash = node.getSignature() != null ? signatureHash(node.getSignature()) : "0";
            String signatureKey = node.getClassName() + "." + node.getMethodName();
            String fullKey = signatureKey + "." + sigHash;

            methodSignatureToNodeId.put(signatureKey, node.getNodeId());
            methodFullKeyToNodeId.put(fullKey, node.getNodeId());
        });

        // Get all Java files
        List<File> allJavaFiles = coreService.findJavaFiles(projectPath, Collections.emptyList());
        log.info("[V2] Scanning {} files for call relations", allJavaFiles.size());

        // Reuse KnowledgeGraphBuilder's scanCallRelationsWithCoreService logic
        List<Map<String, Object>> allRelations = knowledgeGraphBuilder.scanCallRelationsPublic(
            allJavaFiles, projectPath, methodSignatureToNodeId, methodFullKeyToNodeId, javaParser);

        // Filter: only keep relations where caller or callee was rebuilt
        List<Map<String, Object>> filteredRelations = allRelations.stream()
            .filter(rel -> {
                String callerId = (String) rel.get("callerId");
                String calleeId = (String) rel.get("calleeId");
                return rebuiltNodeIds.contains(callerId) || rebuiltNodeIds.contains(calleeId);
            })
            .collect(Collectors.toList());

        if (!filteredRelations.isEmpty()) {
            storageService.saveCallRelations(filteredRelations);
        }

        log.info("[V2] Edge generation complete: {} total relations, {} filtered for changed nodes",
            allRelations.size(), filteredRelations.size());
        return filteredRelations.size();
    }

    /**
     * Incremental refresh with full cache initialization.
     * Supports both Java and Python files.
     */
    public RefreshResult refresh(String projectPath) {
        String normalizedProjectPath = PathUtils.normalize(projectPath);
        log.info("[V2] Starting incremental refresh for: {}", normalizedProjectPath);

        try {
            // 1. Get checkpoint
            Optional<GenerationCheckpointNode> checkpoint = checkpointRepository.findByProjectPath(normalizedProjectPath);
            if (checkpoint.isEmpty()) {
                log.warn("[V2] No checkpoint found for: {}", normalizedProjectPath);
                return RefreshResult.noop();
            }

            String lastCommit = checkpoint.get().getLastCommit();
            String currentCommit = gitStatusService.getCurrentCommitHash(normalizedProjectPath);

            if (currentCommit != null && currentCommit.equals(lastCommit)) {
                log.info("[V2] No changes detected (same commit)");
                return RefreshResult.noop();
            }

            // 2. Initialize caches (full scan) - Java only
            initializeCaches(normalizedProjectPath);

            // 3. Get changed files
            List<String> changedFiles = gitStatusService.getChangedFilesJgit(
                normalizedProjectPath, lastCommit, currentCommit);

            // Separate Java and Python files
            List<String> javaFiles = changedFiles.stream()
                .filter(f -> f.endsWith(".java"))
                .collect(Collectors.toList());

            List<String> pythonFiles = changedFiles.stream()
                .filter(f -> f.endsWith(".py"))
                .collect(Collectors.toList());

            if (javaFiles.isEmpty() && pythonFiles.isEmpty()) {
                log.info("[V2] No Java or Python files changed");
                return new RefreshResult(normalizedProjectPath, lastCommit, currentCommit,
                    changedFiles.size(), 0, 0, 0, 0, 0, true);
            }

            // Merge all changed files for cleanup
            List<String> allChangedFiles = new ArrayList<>();
            allChangedFiles.addAll(javaFiles);
            allChangedFiles.addAll(pythonFiles);

            // 4. Create JavaParser with initialized TypeSolver (for Java)
            JavaParser javaParser = null;
            if (!javaFiles.isEmpty()) {
                javaParser = coreService.createJavaParser(globalCache.getTypeSolver());
            }

            // 5. Cleanup changed nodes and edges (language-agnostic)
            int deletedNodes = cleanupChangedNodes(normalizedProjectPath, allChangedFiles);

            // Cleanup entry points for all changed files
            for (String file : allChangedFiles) {
                Path filePath = Paths.get(normalizedProjectPath, file);
                String normalizedFilePath = PathUtils.normalize(filePath.toString());
                entryPointRepository.deleteByFilePathAndProjectPath(normalizedFilePath, normalizedProjectPath);
            }

            // 6. Rebuild Java nodes (if any)
            Set<String> rebuiltJavaNodeIds = new HashSet<>();
            if (!javaFiles.isEmpty() && javaParser != null) {
                rebuiltJavaNodeIds = rebuildChangedNodes(normalizedProjectPath, javaFiles, javaParser);
            }

            // 7. Rebuild Python nodes (if any)
            Set<String> rebuiltPythonNodeIds = new HashSet<>();
            if (!pythonFiles.isEmpty()) {
                rebuiltPythonNodeIds = rebuildPythonNodes(normalizedProjectPath, pythonFiles);
            }

            // Merge all rebuilt node IDs
            Set<String> allRebuiltNodeIds = new HashSet<>();
            allRebuiltNodeIds.addAll(rebuiltJavaNodeIds);
            allRebuiltNodeIds.addAll(rebuiltPythonNodeIds);

            // 8. Rebuild Java edges (if any)
            int javaEdges = 0;
            if (!javaFiles.isEmpty() && javaParser != null) {
                javaEdges = rebuildEdges(normalizedProjectPath, rebuiltJavaNodeIds, javaParser);
            }

            // 9. Rebuild Python edges (if any)
            int pythonEdges = 0;
            if (!pythonFiles.isEmpty()) {
                pythonEdges = rebuildPythonEdges(normalizedProjectPath, pythonFiles, rebuiltPythonNodeIds);
            }

            // 10. Rebuild Java entry points (if any)
            int javaEntryPoints = 0;
            if (!javaFiles.isEmpty() && javaParser != null) {
                javaEntryPoints = rebuildJavaEntryPointNodes(normalizedProjectPath, javaFiles, javaParser);
            }

            // 11. Rebuild Python entry points (if any)
            int pythonEntryPoints = 0;
            if (!pythonFiles.isEmpty()) {
                pythonEntryPoints = rebuildPythonEntryPoints(normalizedProjectPath, pythonFiles);
            }

            // 12. Update checkpoint (use upsert to MERGE by projectPath)
            checkpointRepository.upsertCheckpoint(normalizedProjectPath, currentCommit, "main");

            int totalRebuiltNodes = rebuiltJavaNodeIds.size() + rebuiltPythonNodeIds.size();
            int totalRebuiltEdges = javaEdges + pythonEdges;
            int totalEntryPoints = javaEntryPoints + pythonEntryPoints;

            log.info("[V2] Incremental refresh complete: {} Java files, {} Python files, {} nodes deleted, {} nodes rebuilt, {} edges rebuilt, {} entry points",
                javaFiles.size(), pythonFiles.size(), deletedNodes, totalRebuiltNodes, totalRebuiltEdges, totalEntryPoints);

            return new RefreshResult(
                normalizedProjectPath,
                lastCommit,
                currentCommit,
                changedFiles.size(),
                deletedNodes,
                totalRebuiltNodes,
                totalRebuiltEdges,
                totalEntryPoints,
                0,
                true
            );
        } catch (java.io.IOException e) {
            log.error("[V2] Failed to get changed files: {}", e.getMessage(), e);
            return new RefreshResult(normalizedProjectPath, null, null, 0, 0, 0, 0, 0, 0, false);
        } catch (Exception e) {
            log.error("[V2] Refresh failed: {}", e.getMessage(), e);
            return new RefreshResult(normalizedProjectPath, null, null, 0, 0, 0, 0, 0, 0, false);
        }
    }

    /**
     * Rebuild Python MethodNodes from changed files.
     */
    private Set<String> rebuildPythonNodes(String projectPath, List<String> changedPythonFiles) {
        log.info("[V2] Rebuilding nodes from {} Python files", changedPythonFiles.size());

        List<MethodNode> rebuiltNodes = new ArrayList<>();
        Set<String> rebuiltNodeIds = new HashSet<>();

        for (String file : changedPythonFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!filePath.toFile().exists()) continue;

            try {
                String normalizedFilePath = PathUtils.normalize(filePath.toString());
                List<MethodNode> nodes = pythonKnowledgeGraphBuilder.parseFile(
                    normalizedFilePath, projectPath);
                for (MethodNode node : nodes) {
                    rebuiltNodes.add(node);
                    rebuiltNodeIds.add(node.getNodeId());
                }
            } catch (Exception e) {
                log.warn("[V2] Failed to parse Python file {}: {}", file, e.getMessage());
            }
        }

        if (!rebuiltNodes.isEmpty()) {
            List<Map<String, Object>> nodeMaps = rebuiltNodes.stream()
                .map(this::pythonMethodNodeToMap)
                .collect(Collectors.toList());
            methodNodeRepository.mergeAll(nodeMaps);
        }

        log.info("[V2] Rebuilt {} Python nodes", rebuiltNodes.size());
        return rebuiltNodeIds;
    }

    /**
     * Convert Python MethodNode to Map for mergeAll.
     */
    private Map<String, Object> pythonMethodNodeToMap(MethodNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", node.getNodeId());
        map.put("className", node.getClassName());
        map.put("methodName", node.getMethodName());
        map.put("signature", node.getSignature());
        map.put("filePath", node.getFilePath());
        map.put("startLine", node.getStartLine());
        map.put("endLine", node.getEndLine());
        map.put("projectPath", node.getProjectPath());
        map.put("language", "python");
        return map;
    }

    /**
     * Rebuild Python CALLS edges using PythonCallGraphResolver.
     * Similar to Java edge generation: full scan but filter for changed nodes.
     */
    private int rebuildPythonEdges(String projectPath, List<String> changedPythonFiles, Set<String> rebuiltNodeIds) {
        log.info("[V2] Rebuilding Python edges, filtering for {} changed nodes", rebuiltNodeIds.size());

        // Parse all Python files to get modules (needed for cross-module resolution)
        List<PyModule> allModules = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(projectPath))) {
            paths.filter(p -> p.toString().endsWith(".py"))
                .forEach(p -> {
                    try {
                        PythonKnowledgeGraphBuilder.ParsedFile parsed =
                            pythonKnowledgeGraphBuilder.parseFileWithModule(p.toString(), projectPath);
                        allModules.add(parsed.module());
                    } catch (Exception e) {
                        log.warn("[V2] Failed to parse Python file {}: {}", p, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.error("[V2] Failed to walk Python files: {}", e.getMessage());
            return 0;
        }

        // Resolve all call relations
        List<Map<String, Object>> allRelations = pythonCallGraphResolver.resolveProject(allModules, projectPath);

        // Filter: only keep edges where caller or callee was rebuilt
        List<Map<String, Object>> filteredRelations = allRelations.stream()
            .filter(rel -> {
                String callerId = (String) rel.get("callerId");
                String calleeId = (String) rel.get("calleeId");
                return rebuiltNodeIds.contains(callerId) || rebuiltNodeIds.contains(calleeId);
            })
            .collect(Collectors.toList());

        if (!filteredRelations.isEmpty()) {
            storageService.saveCallRelations(filteredRelations);
        }

        log.info("[V2] Python edge generation complete: {} total relations, {} filtered",
            allRelations.size(), filteredRelations.size());
        return filteredRelations.size();
    }

    /**
     * Rebuild Python entry points from changed files.
     */
    private int rebuildPythonEntryPoints(String projectPath, List<String> changedPythonFiles) {
        log.info("[V2] Rebuilding Python entry points from {} files", changedPythonFiles.size());

        List<EntryPointNode> allEntryPoints = new ArrayList<>();

        for (String file : changedPythonFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!filePath.toFile().exists()) continue;

            try {
                String normalizedFilePath = PathUtils.normalize(filePath.toString());
                List<EntryPointNode> entryPoints = pythonKnowledgeGraphBuilder.buildFileEntryPoints(
                    normalizedFilePath, projectPath);
                allEntryPoints.addAll(entryPoints);
            } catch (Exception e) {
                log.warn("[V2] Failed to build Python entry points from {}: {}", file, e.getMessage());
            }
        }

        if (!allEntryPoints.isEmpty()) {
            List<Map<String, Object>> entryMaps = allEntryPoints.stream()
                .map(this::pythonEntryPointToMap)
                .collect(Collectors.toList());
            entryPointRepository.mergeAll(entryMaps);
        }

        log.info("[V2] Rebuilt {} Python entry points", allEntryPoints.size());
        return allEntryPoints.size();
    }

    /**
     * Convert Python EntryPointNode to Map for mergeAll.
     */
    private Map<String, Object> pythonEntryPointToMap(EntryPointNode entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entryId", entry.getEntryId());
        map.put("entryType", entry.getEntryType());
        map.put("entryKey", entry.getEntryKey());
        map.put("entryInfo", entry.getEntryInfo());
        map.put("methodNodeId", entry.getMethodNodeId());
        map.put("projectPath", entry.getProjectPath());
        map.put("briefDescription", entry.getBriefDescription());
        map.put("detailedDescription", entry.getDetailedDescription());
        map.put("serviceName", entry.getServiceName());
        map.put("language", "python");
        return map;
    }

    /**
     * Rebuild Java entry points from changed files.
     */
    private int rebuildJavaEntryPointNodes(String projectPath, List<String> changedJavaFiles, JavaParser javaParser) {
        log.info("[V2] Rebuilding Java entry points from {} files", changedJavaFiles.size());

        List<EntryPointNode> allEntryPoints = new ArrayList<>();

        for (String file : changedJavaFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!filePath.toFile().exists()) continue;

            CompilationUnit cu = coreService.parseFile(filePath.toFile(), javaParser);
            if (cu == null) continue;

            String normalizedFilePath = PathUtils.normalize(filePath.toString());
            List<EntryPointNode> entryPoints = scanJavaEntryPoints(cu, normalizedFilePath, projectPath);
            allEntryPoints.addAll(entryPoints);
        }

        if (!allEntryPoints.isEmpty()) {
            List<Map<String, Object>> entryMaps = allEntryPoints.stream()
                .map(this::javaEntryPointToMap)
                .collect(Collectors.toList());
            entryPointRepository.mergeAll(entryMaps);
        }

        log.info("[V2] Rebuilt {} Java entry points", allEntryPoints.size());
        return allEntryPoints.size();
    }

    /**
     * Scan Java entry points from CompilationUnit.
     */
    private List<EntryPointNode> scanJavaEntryPoints(CompilationUnit cu, String filePath, String projectPath) {
        List<EntryPointNode> entryPoints = new ArrayList<>();
        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ? clazz.getNameAsString()
                : packageName + "." + clazz.getNameAsString();

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                String sigHash = signatureHash(method.getSignature().toString());
                String methodId = className + "." + method.getNameAsString() + "." + sigHash;
                String nodeId = projectPath + ":" + methodId;

                // Check for HTTP annotations
                method.getAnnotations().forEach(annotation -> {
                    String annName = annotation.getNameAsString();
                    if (isHttpAnnotation(annName)) {
                        String path = extractAnnotationValue(annotation).orElse("");
                        entryPoints.add(EntryPointNode.builder()
                            .entryId(projectPath + ":HTTP_" + methodId)
                            .entryType(EntryPointNode.TYPE_HTTP)
                            .entryKey(extractHttpMethod(annName) + " " + path)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .language("java")
                            .build());
                    } else if (annName.equals("Scheduled") || annName.contains("Scheduled")) {
                        entryPoints.add(EntryPointNode.builder()
                            .entryId(projectPath + ":SCHEDULED_" + methodId)
                            .entryType(EntryPointNode.TYPE_SCHEDULED)
                            .entryKey("SCHEDULED:" + methodId)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .language("java")
                            .build());
                    } else if (annName.equals("RabbitListener") || annName.equals("KafkaListener")) {
                        entryPoints.add(EntryPointNode.builder()
                            .entryId(projectPath + ":MQ_" + methodId)
                            .entryType(EntryPointNode.TYPE_MQ_CONSUMER)
                            .entryKey("MQ:" + methodId)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .language("java")
                            .build());
                    }
                });
            });
        });

        return entryPoints;
    }

    private Map<String, Object> javaEntryPointToMap(EntryPointNode entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entryId", entry.getEntryId());
        map.put("entryType", entry.getEntryType());
        map.put("entryKey", entry.getEntryKey());
        map.put("entryInfo", entry.getEntryInfo());
        map.put("methodNodeId", entry.getMethodNodeId());
        map.put("projectPath", entry.getProjectPath());
        map.put("briefDescription", entry.getBriefDescription());
        map.put("detailedDescription", entry.getDetailedDescription());
        map.put("serviceName", entry.getServiceName());
        map.put("language", "java");
        return map;
    }

    private boolean isHttpAnnotation(String annName) {
        return annName.equals("RequestMapping") || annName.equals("GetMapping")
            || annName.equals("PostMapping") || annName.equals("PutMapping")
            || annName.equals("DeleteMapping") || annName.equals("PatchMapping");
    }

    private String extractHttpMethod(String name) {
        return switch (name) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> "GET";
        };
    }

    private Optional<String> extractAnnotationValue(com.github.javaparser.ast.expr.AnnotationExpr annotation) {
        if (annotation instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr single) {
            return Optional.of(single.getMemberValue().toString().replace("\"", ""));
        }
        if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
                .findFirst()
                .map(p -> p.getValue().toString().replace("\"", ""));
        }
        return Optional.empty();
    }
}