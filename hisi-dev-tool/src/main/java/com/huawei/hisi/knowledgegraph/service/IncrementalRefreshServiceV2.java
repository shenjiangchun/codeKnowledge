package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import com.huawei.hisi.knowledgegraph.service.GitStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

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
        int vectorsGenerated,
        boolean success
    ) {
        public static RefreshResult noop() {
            return new RefreshResult(null, null, null, 0, 0, 0, 0, 0, true);
        }
    }

    /**
     * Debug method to check checkpoint status.
     */
    public CheckpointDebugResult debugCheckpoint(String projectPath) {
        log.info("[V2 Debug] Checking checkpoint for: {}", projectPath);
        var checkpoint = checkpointRepository.findByProjectPath(projectPath);
        if (checkpoint.isPresent()) {
            log.info("[V2 Debug] Checkpoint found: lastCommit={}, lastBranch={}",
                checkpoint.get().getLastCommit(), checkpoint.get().getLastBranch());
            return new CheckpointDebugResult(checkpoint.get().getLastCommit(), checkpoint.get().getLastBranch());
        } else {
            log.warn("[V2 Debug] No checkpoint found for: {}", projectPath);
            // Try to save a checkpoint manually for testing
            String currentCommit = gitStatusService.getCurrentCommitHash(projectPath);
            log.info("[V2 Debug] Current commit from git: {}", currentCommit);
            checkpointRepository.upsertCheckpoint(projectPath, currentCommit != null ? currentCommit : "DEBUG_COMMIT", "debug");
            log.info("[V2 Debug] Manually saved checkpoint for: {}", projectPath);

            // Query again
            var afterSave = checkpointRepository.findByProjectPath(projectPath);
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

            if (!filePath.toFile().exists()) {
                // File was deleted
                deletedFilePaths.add(absoluteFilePath);
                continue;
            }

            // Delete nodes from this file (DETACH DELETE removes node + edges)
            List<MethodNode> nodesInFile = methodNodeRepository.findByProjectPathAndFilePath(
                projectPath, absoluteFilePath);
            deletedNodes += nodesInFile.size();
            deletedFilePaths.add(absoluteFilePath);
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

            // Use KnowledgeGraphBuilder's scanMethodNodes logic
            List<MethodNode> nodes = scanMethodNodes(cu, filePath.toString(), projectPath);
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
     */
    public RefreshResult refresh(String projectPath) {
        String normalizedProjectPath = projectPath.replace('\\', '/');
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

            // 2. Initialize caches (full scan)
            initializeCaches(normalizedProjectPath);

            // 3. Get changed files
            List<String> changedFiles = gitStatusService.getChangedFilesJgit(
                normalizedProjectPath, lastCommit, currentCommit);

            List<String> javaFiles = changedFiles.stream()
                .filter(f -> f.endsWith(".java"))
                .collect(Collectors.toList());

            if (javaFiles.isEmpty()) {
                log.info("[V2] No Java files changed");
                return new RefreshResult(normalizedProjectPath, lastCommit, currentCommit,
                    changedFiles.size(), 0, 0, 0, 0, true);
            }

            // 4. Create JavaParser with initialized TypeSolver
            JavaParser javaParser = coreService.createJavaParser(globalCache.getTypeSolver());

            // 5. Cleanup changed nodes and edges
            int deletedNodes = cleanupChangedNodes(normalizedProjectPath, javaFiles);

            // 6. Rebuild changed nodes
            Set<String> rebuiltNodeIds = rebuildChangedNodes(normalizedProjectPath, javaFiles, javaParser);

            // 7. Rebuild edges (full scan, smart filter)
            int rebuiltEdges = rebuildEdges(normalizedProjectPath, rebuiltNodeIds, javaParser);

            // 8. Update checkpoint
            checkpointRepository.save(GenerationCheckpointNode.builder()
                .projectPath(normalizedProjectPath)
                .lastCommit(currentCommit)
                .generatedAt(java.time.Instant.now())
                .build());

            log.info("[V2] Incremental refresh complete: {} files, {} nodes deleted, {} nodes rebuilt, {} edges rebuilt",
                javaFiles.size(), deletedNodes, rebuiltNodeIds.size(), rebuiltEdges);

            return new RefreshResult(
                normalizedProjectPath,
                lastCommit,
                currentCommit,
                changedFiles.size(),
                deletedNodes,
                rebuiltNodeIds.size(),
                rebuiltEdges,
                0,
                true
            );
        } catch (java.io.IOException e) {
            log.error("[V2] Failed to get changed files: {}", e.getMessage(), e);
            return new RefreshResult(normalizedProjectPath, null, null, 0, 0, 0, 0, 0, false);
        } catch (Exception e) {
            log.error("[V2] Refresh failed: {}", e.getMessage(), e);
            return new RefreshResult(normalizedProjectPath, null, null, 0, 0, 0, 0, 0, false);
        }
    }
}