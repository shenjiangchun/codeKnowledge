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
        return String.valueOf(signature.hashCode());
    }

    /**
     * Incremental refresh with full cache initialization.
     * Implementation will be added in later tasks.
     */
    public RefreshResult refresh(String projectPath) {
        // Placeholder - implementation in Task 7
        return RefreshResult.noop();
    }
}