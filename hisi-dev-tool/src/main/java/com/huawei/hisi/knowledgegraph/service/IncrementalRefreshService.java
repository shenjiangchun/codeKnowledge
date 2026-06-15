package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.link.CrossServiceLinker;
import com.huawei.hisi.knowledgegraph.model.ClassExtends;
import com.huawei.hisi.knowledgegraph.model.InterfaceImplementation;
import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.scanner.JavaDataModelScanner;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import com.huawei.hisi.knowledgegraph.util.MethodBodyCompressor;
import com.huawei.hisi.knowledgegraph.vector.VectorWriter;
import com.huawei.hisi.neo4j.model.DataModelNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Incrementally refreshes the knowledge graph by detecting changed files
 * since the last checkpoint, deleting stale nodes, and rebuilding method
 * nodes, entry points, call relations, and data models from the updated
 * source files.
 *
 * <p>Supports both Java (.java) and Python (.py) files. Cross-service
 * linking and IMPLEMENTS/EXTENDS edges are re-materialized after rebuild.</p>
 */
@Service
@Slf4j
public class IncrementalRefreshService {

    private final GitStatusService gitStatusService;
    private final Neo4jGenerationCheckpointRepository checkpointRepository;
    private final VectorWriter vectorWriter;
    private final CrossServiceLinker crossServiceLinker;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointRepository;
    private final PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder;
    private final CodeAnalysisCoreService coreService;
    private final GlobalAnalysisCache globalCache;
    private final KnowledgeGraphStorageService storageService;
    private final JavaDataModelScanner javaDataModelScanner;
    private final Neo4jDataModelNodeRepository dataModelNodeRepository;
    private final VectorGenerationService vectorGenerationService;

    public IncrementalRefreshService(
            GitStatusService gitStatusService,
            Neo4jGenerationCheckpointRepository checkpointRepository,
            VectorWriter vectorWriter,
            CrossServiceLinker crossServiceLinker,
            Neo4jMethodNodeRepository methodNodeRepository,
            Neo4jEntryPointNodeRepository entryPointRepository,
            PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder,
            CodeAnalysisCoreService coreService,
            GlobalAnalysisCache globalCache,
            KnowledgeGraphStorageService storageService,
            JavaDataModelScanner javaDataModelScanner,
            Neo4jDataModelNodeRepository dataModelNodeRepository,
            VectorGenerationService vectorGenerationService) {
        this.gitStatusService = gitStatusService;
        this.checkpointRepository = checkpointRepository;
        this.vectorWriter = vectorWriter;
        this.crossServiceLinker = crossServiceLinker;
        this.methodNodeRepository = methodNodeRepository;
        this.entryPointRepository = entryPointRepository;
        this.pythonKnowledgeGraphBuilder = pythonKnowledgeGraphBuilder;
        this.coreService = coreService;
        this.globalCache = globalCache;
        this.storageService = storageService;
        this.javaDataModelScanner = javaDataModelScanner;
        this.dataModelNodeRepository = dataModelNodeRepository;
        this.vectorGenerationService = vectorGenerationService;
    }

    /**
     * Incrementally refresh the knowledge graph for a project.
     * @param projectPath the project path
     * @param preview if true, skip clean working directory check (allow uncommitted changes)
     */
    public RefreshResult refresh(String projectPath, boolean preview) throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");

        String normalizedProjectPath = com.huawei.hisi.knowledgegraph.util.ProjectPathResolver.normalize(projectPath);
        log.info("[IncrementalRefresh] Normalized path: {} -> {}", projectPath, normalizedProjectPath);

        // 1. Find checkpoint
        GenerationCheckpointNode checkpoint = checkpointRepository
                .findByProjectPath(normalizedProjectPath)
                .orElseThrow(() -> new NoCheckpointException(normalizedProjectPath));

        // 2. Assert clean working directory (skip if preview mode)
        if (!preview) {
            gitStatusService.assertClean(normalizedProjectPath);
        } else {
            log.info("[IncrementalRefresh] Preview mode - skipping clean working directory check");
        }

        // 3. Get current commit
        String currentCommit = gitStatusService.getCurrentCommitHash(normalizedProjectPath);
        String lastCommit = checkpoint.getLastCommit();

        if ("NO_COMMIT".equals(lastCommit) && currentCommit == null) {
            log.info("[IncrementalRefresh] No git history for project={}, returning noop", normalizedProjectPath);
            return RefreshResult.noop();
        }

        if (currentCommit != null && currentCommit.equals(lastCommit)) {
            return RefreshResult.noop();
        }

        // 4. Get changed files
        List<String> changedFiles = gitStatusService.getChangedFilesJgit(
                normalizedProjectPath, lastCommit, currentCommit);
        if (changedFiles.isEmpty()) {
            return RefreshResult.noop();
        }

        // Separate Java and Python files
        List<String> javaFiles = changedFiles.stream().filter(f -> f.endsWith(".java")).collect(Collectors.toList());
        List<String> pythonFiles = changedFiles.stream().filter(f -> f.endsWith(".py")).collect(Collectors.toList());

        // 5. Set up TypeSolver for Java call relation analysis
        List<Path> sourceRoots = coreService.findSourceRoots(Paths.get(normalizedProjectPath));
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        for (Path root : sourceRoots) {
            solver.add(new JavaParserTypeSolver(root));
        }
        JavaParser typeSolvingParser = coreService.createJavaParser(solver);

        // 6. Collect existing method nodeIds from Neo4j (for call relation nodeId lookup)
        // Key format: className.methodName.signatureHash to distinguish overloaded methods
        Map<String, String> methodSignatureToNodeId = new HashMap<>();
        methodNodeRepository.findByProjectPath(normalizedProjectPath)
                .forEach(node -> {
                    String sigHash = node.getSignature() != null ? signatureHash(node.getSignature()) : "0";
                    String key = node.getClassName() + "." + node.getMethodName() + "." + sigHash;
                    methodSignatureToNodeId.put(key, node.getNodeId());
                });

        // 7. Delete stale entry points for each changed file (keep method nodes - use MERGE)
        // Note: We no longer DETACH DELETE method nodes because MERGE preserves existing data
        // and vectorGenerationService will regenerate embeddings for updated nodes
        for (String file : changedFiles) {
            Path filePath = Paths.get(normalizedProjectPath, file);
            String absoluteFilePath = filePath.toString();  // 使用绝对路径，与 MethodNode.filePath 一致
            entryPointRepository.deleteByFilePathAndProjectPath(absoluteFilePath, normalizedProjectPath);
        }
        int deleted = changedFiles.size();

        // 8. Rebuild method nodes
        int rebuiltMethods = rebuildMethodNodes(normalizedProjectPath, javaFiles, pythonFiles);

        // Update methodSignatureToNodeId with newly rebuilt nodes
        for (String file : javaFiles) {
            Path filePath = Paths.get(normalizedProjectPath, file);
            if (!Files.exists(filePath)) continue;
            try {
                CompilationUnit cu = coreService.parseFile(filePath.toFile(), typeSolvingParser);
                if (cu == null) continue;
                String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                    String className = packageName.isEmpty() ? clazz.getNameAsString()
                            : packageName + "." + clazz.getNameAsString();
                    clazz.findAll(MethodDeclaration.class).forEach(method -> {
                        String sigHash = signatureHash(method.getSignature().toString());
                        String nodeId = normalizedProjectPath + ":" + className + "."
                                + method.getNameAsString() + "." + sigHash;
                        String key = className + "." + method.getNameAsString() + "." + sigHash;
                        methodSignatureToNodeId.put(key, nodeId);
                    });
                });
            } catch (Exception e) {
                log.warn("Failed to update signature map from {}: {}", file, e.getMessage());
            }
        }

        // 9. Rebuild call relations for changed Java files (with TypeSolver)
        int callRelations = 0;
        if (!javaFiles.isEmpty()) {
            callRelations = rebuildCallRelations(normalizedProjectPath, javaFiles, typeSolvingParser, methodSignatureToNodeId);
        }

        // 10. Rebuild entry points
        int rebuiltEntryPoints = rebuildJavaEntryPoints(normalizedProjectPath, javaFiles);
        rebuiltEntryPoints += rebuildPythonEntryPoints(normalizedProjectPath, pythonFiles);

        // 11. Rebuild DataModel nodes for changed Java files
        int dataModels = rebuildDataModels(normalizedProjectPath, javaFiles, typeSolvingParser, methodSignatureToNodeId);

        // 12. Re-materialize IMPLEMENTS/EXTENDS edges from GlobalCache
        reMaterializeImplementsExtends(normalizedProjectPath);

        // 13. Cross-service linking
        try {
            crossServiceLinker.link(List.of(normalizedProjectPath));
        } catch (Exception e) {
            log.warn("Cross-service re-linking failed: {}", e.getMessage());
        }

        // 14. Trigger vector generation for updated nodes
        if (rebuiltMethods > 0 || rebuiltEntryPoints > 0) {
            log.info("[IncrementalRefresh] Starting vector generation for updated nodes: {}", normalizedProjectPath);
            vectorGenerationService.startVectorGeneration(normalizedProjectPath);
        }

        // 15. Update checkpoint
        String currentBranch = gitStatusService.getCurrentBranch(normalizedProjectPath);
        checkpointRepository.upsertCheckpoint(normalizedProjectPath, currentCommit, currentBranch);

        log.info("[IncrementalRefresh] Done: {} files changed, {} deleted, {} methods, {} calls, {} entries, {} models",
                changedFiles.size(), deleted, rebuiltMethods, callRelations, rebuiltEntryPoints, dataModels);
        return new RefreshResult(false, changedFiles.size(), deleted, rebuiltMethods,
                rebuiltEntryPoints, callRelations, dataModels);
    }

    /**
     * Convenience overload - refresh without preview mode.
     */
    public RefreshResult refresh(String projectPath) throws IOException {
        return refresh(projectPath, false);
    }

    // ==================== Method Node Rebuild ====================

    private int rebuildMethodNodes(String projectPath, List<String> javaFiles, List<String> pythonFiles) {
        int total = 0;
        if (!javaFiles.isEmpty()) total += rebuildJavaMethodNodes(projectPath, javaFiles);
        if (!pythonFiles.isEmpty()) total += rebuildPythonMethodNodes(projectPath, pythonFiles);
        log.info("Rebuilt {} method nodes from changed files", total);
        return total;
    }

    private int rebuildJavaMethodNodes(String projectPath, List<String> javaFiles) {
        int created = 0;
        int updated = 0;
        int deleted = 0;
        int unchanged = 0;
        int lineUpdated = 0;  // 行号更新计数
        List<MethodNode> nodesToCreate = new ArrayList<>();
        List<String> nodeIdsToDelete = new ArrayList<>();
        List<Map<String, Object>> nodesToUpdateLineNumbers = new ArrayList<>();  // 需要更新行号的节点
        Map<String, String> oldToNewNodeIdMap = new HashMap<>();  // 用于重建跨文件调用关系
        JavaParser javaParser = new JavaParser();

        for (String file : javaFiles) {
            Path filePath = Paths.get(projectPath, file);
            String absoluteFilePath = filePath.toString();  // 使用绝对路径查询
            if (!Files.exists(filePath)) {
                // 文件被删除 → 删除该文件的所有方法节点
                List<MethodNode> deletedFileNodes = methodNodeRepository.findByProjectPathAndFilePath(projectPath, absoluteFilePath);
                deletedFileNodes.forEach(n -> nodeIdsToDelete.add(n.getNodeId()));
                deleted += deletedFileNodes.size();
                continue;
            }
            try {
                // 解析变更文件得到新节点列表
                List<MethodNode> newNodes = parseJavaFile(javaParser, absoluteFilePath, projectPath);

                // 查询变更文件中的所有旧节点（用绝对路径 filePath 查询）
                List<MethodNode> oldNodes = methodNodeRepository.findByProjectPathAndFilePath(projectPath, absoluteFilePath);

                // 构建旧节点查找 Map: className.methodName.signature -> MethodNode
                Map<String, MethodNode> oldNodeMap = oldNodes.stream()
                        .collect(Collectors.toMap(
                                n -> n.getClassName() + "." + n.getMethodName() + "." + n.getSignature(),
                                n -> n));

                // 构建新节点查找 Map: className.methodName.signature -> MethodNode
                Map<String, MethodNode> newNodeMap = newNodes.stream()
                        .collect(Collectors.toMap(
                                n -> n.getClassName() + "." + n.getMethodName() + "." + n.getSignature(),
                                n -> n));

                // 处理新节点：新增或更新
                for (MethodNode newNode : newNodes) {
                    String key = newNode.getClassName() + "." + newNode.getMethodName() + "." + newNode.getSignature();
                    MethodNode oldNode = oldNodeMap.get(key);

                    if (oldNode == null) {
                        // 新方法 → CREATE
                        nodesToCreate.add(newNode);
                        created++;
                    } else {
                        boolean methodBodySame = methodBodyEquals(newNode.getMethodBody(), oldNode.getMethodBody());
                        boolean lineChanged = newNode.getStartLine() != oldNode.getStartLine()
                                || newNode.getEndLine() != oldNode.getEndLine();

                        if (methodBodySame && !lineChanged) {
                            // methodBody 相同且行号不变 → 不处理
                            unchanged++;
                        } else if (methodBodySame && lineChanged) {
                            // methodBody 相同但行号变化 → 使用 mergeAll 更新行号
                            nodesToUpdateLineNumbers.add(methodNodeToMap(newNode));
                            lineUpdated++;
                        } else {
                            // methodBody 变化 → DELETE + CREATE
                            nodeIdsToDelete.add(oldNode.getNodeId());
                            nodesToCreate.add(newNode);
                            // 记录 oldNodeId → newNodeId 映射，用于重建跨文件调用关系
                            oldToNewNodeIdMap.put(oldNode.getNodeId(), newNode.getNodeId());
                            updated++;
                        }
                    }
                }

                // 处理删除：旧节点不在新节点列表中 → DELETE
                for (MethodNode oldNode : oldNodes) {
                    String key = oldNode.getClassName() + "." + oldNode.getMethodName() + "." + oldNode.getSignature();
                    if (!newNodeMap.containsKey(key)) {
                        nodeIdsToDelete.add(oldNode.getNodeId());
                        deleted++;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to rebuild methods from {}: {}", file, e.getMessage());
            }
        }

        // 在删除前查询 incoming CALLS（来自未变更文件的调用关系）
        List<Map<String, Object>> incomingCalls = new ArrayList<>();
        if (!nodeIdsToDelete.isEmpty() && !oldToNewNodeIdMap.isEmpty()) {
            // 只查询有新节点映射的 incoming calls（被更新的方法）
            List<String> updatedNodeIds = new ArrayList<>(oldToNewNodeIdMap.keySet());
            incomingCalls = methodNodeRepository.findIncomingCallsByNodeIds(updatedNodeIds);
            log.info("Found {} incoming CALLS to updated methods (need to rebuild)", incomingCalls.size());
        }

        // 执行删除
        if (!nodeIdsToDelete.isEmpty()) {
            methodNodeRepository.deleteByNodeIds(nodeIdsToDelete);
            log.info("Deleted {} Java method nodes ({} updated, {} deleted)", nodeIdsToDelete.size(), updated, deleted - updated);
        }

        // 执行创建
        if (!nodesToCreate.isEmpty()) {
            methodNodeRepository.saveAll(nodesToCreate);
            log.info("Created {} Java method nodes ({} new, {} updated)", nodesToCreate.size(), created, updated);
        }

        // 执行行号更新（methodBody 相同但行号变化）
        if (!nodesToUpdateLineNumbers.isEmpty()) {
            methodNodeRepository.mergeAll(nodesToUpdateLineNumbers);
            log.info("Updated {} Java method node line numbers", lineUpdated);
        }

        // 重建 incoming CALLS（来自未变更文件的调用关系）
        if (!incomingCalls.isEmpty()) {
            List<Map<String, Object>> relationsToRebuild = new ArrayList<>();
            for (Map<String, Object> call : incomingCalls) {
                String oldCalleeId = (String) call.get("calleeId");
                String newCalleeId = oldToNewNodeIdMap.get(oldCalleeId);
                if (newCalleeId != null) {
                    Map<String, Object> relation = new LinkedHashMap<>();
                    relation.put("callerId", call.get("callerId"));
                    relation.put("calleeId", newCalleeId);
                    relation.put("callType", call.get("callType"));
                    relation.put("callLine", call.get("callLine"));
                    relationsToRebuild.add(relation);
                }
            }
            if (!relationsToRebuild.isEmpty()) {
                methodNodeRepository.createCallRelations(relationsToRebuild);
                log.info("Rebuilt {} incoming CALLS from unchanged files", relationsToRebuild.size());
            }
        }

        log.info("Java method nodes: created={}, updated={}, deleted={}, unchanged={}, lineUpdated={}", created, updated, deleted, unchanged, lineUpdated);
        return created + updated + deleted + lineUpdated;
    }

    /**
     * 判断两个 methodBody 是否相等
     * null 和空字符串视为相等
     */
    private boolean methodBodyEquals(String body1, String body2) {
        if (body1 == null || body1.isEmpty()) return body2 == null || body2.isEmpty();
        if (body2 == null || body2.isEmpty()) return false;
        return body1.equals(body2);
    }

    /**
     * 将 MethodNode 转换为 mergeAll 需要的 Map 格式
     */
    private Map<String, Object> methodNodeToMap(MethodNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", node.getNodeId());
        map.put("className", node.getClassName());
        map.put("methodName", node.getMethodName());
        map.put("signature", node.getSignature());
        map.put("description", node.getDescription());
        map.put("filePath", node.getFilePath());
        map.put("startLine", node.getStartLine());
        map.put("endLine", node.getEndLine());
        map.put("complexity", node.getComplexity());
        map.put("methodBody", node.getMethodBody());
        map.put("projectPath", node.getProjectPath());
        map.put("serviceName", node.getServiceName());
        map.put("comment", node.getComment());
        map.put("thrownExceptions", node.getThrownExceptions());
        map.put("caughtExceptions", node.getCaughtExceptions());
        return map;
    }

    private int rebuildPythonMethodNodes(String projectPath, List<String> pythonFiles) {
        int created = 0;
        int updated = 0;
        int deleted = 0;
        int unchanged = 0;
        List<MethodNode> nodesToCreate = new ArrayList<>();
        List<String> nodeIdsToDelete = new ArrayList<>();
        Map<String, String> oldToNewNodeIdMap = new HashMap<>();  // 用于重建跨文件调用关系

        for (String file : pythonFiles) {
            Path filePath = Paths.get(projectPath, file);
            String absoluteFilePath = filePath.toString();  // 使用绝对路径查询
            if (!Files.exists(filePath)) {
                // 文件被删除 → 删除该文件的所有方法节点
                List<MethodNode> deletedFileNodes = methodNodeRepository.findByProjectPathAndFilePath(projectPath, absoluteFilePath);
                deletedFileNodes.forEach(n -> nodeIdsToDelete.add(n.getNodeId()));
                deleted += deletedFileNodes.size();
                continue;
            }
            try {
                // 解析变更文件得到新节点列表
                List<MethodNode> newNodes = pythonKnowledgeGraphBuilder.parseFile(absoluteFilePath, projectPath);

                // 查询变更文件中的所有旧节点（用绝对路径查询）
                List<MethodNode> oldNodes = methodNodeRepository.findByProjectPathAndFilePath(projectPath, absoluteFilePath);

                // 构建旧节点查找 Map: className.methodName.signature -> MethodNode
                Map<String, MethodNode> oldNodeMap = oldNodes.stream()
                        .collect(Collectors.toMap(
                                n -> n.getClassName() + "." + n.getMethodName() + "." + n.getSignature(),
                                n -> n));

                // 构建新节点查找 Map: className.methodName.signature -> MethodNode
                Map<String, MethodNode> newNodeMap = newNodes.stream()
                        .collect(Collectors.toMap(
                                n -> n.getClassName() + "." + n.getMethodName() + "." + n.getSignature(),
                                n -> n));

                // 处理新节点：新增或更新
                for (MethodNode newNode : newNodes) {
                    String key = newNode.getClassName() + "." + newNode.getMethodName() + "." + newNode.getSignature();
                    MethodNode oldNode = oldNodeMap.get(key);

                    if (oldNode == null) {
                        // 新方法 → CREATE
                        nodesToCreate.add(newNode);
                        created++;
                    } else {
                        if (methodBodyEquals(newNode.getMethodBody(), oldNode.getMethodBody())) {
                            // methodBody 相同 → 不处理
                            unchanged++;
                        } else {
                            // methodBody 变化 → DELETE + CREATE
                            nodeIdsToDelete.add(oldNode.getNodeId());
                            nodesToCreate.add(newNode);
                            // 记录 oldNodeId → newNodeId 映射，用于重建跨文件调用关系
                            oldToNewNodeIdMap.put(oldNode.getNodeId(), newNode.getNodeId());
                            updated++;
                        }
                    }
                }

                // 处理删除：旧节点不在新节点列表中 → DELETE
                for (MethodNode oldNode : oldNodes) {
                    String key = oldNode.getClassName() + "." + oldNode.getMethodName() + "." + oldNode.getSignature();
                    if (!newNodeMap.containsKey(key)) {
                        nodeIdsToDelete.add(oldNode.getNodeId());
                        deleted++;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to rebuild Python methods from {}: {}", file, e.getMessage());
            }
        }

        // 在删除前查询 incoming CALLS（来自未变更文件的调用关系）
        List<Map<String, Object>> incomingCalls = new ArrayList<>();
        if (!nodeIdsToDelete.isEmpty() && !oldToNewNodeIdMap.isEmpty()) {
            List<String> updatedNodeIds = new ArrayList<>(oldToNewNodeIdMap.keySet());
            incomingCalls = methodNodeRepository.findIncomingCallsByNodeIds(updatedNodeIds);
            log.info("Found {} incoming CALLS to updated Python methods", incomingCalls.size());
        }

        // 执行删除
        if (!nodeIdsToDelete.isEmpty()) {
            methodNodeRepository.deleteByNodeIds(nodeIdsToDelete);
            log.info("Deleted {} Python method nodes ({} updated, {} deleted)", nodeIdsToDelete.size(), updated, deleted - updated);
        }

        // 执行创建
        if (!nodesToCreate.isEmpty()) {
            methodNodeRepository.saveAll(nodesToCreate);
            log.info("Created {} Python method nodes ({} new, {} updated)", nodesToCreate.size(), created, updated);
        }

        // 重建 incoming CALLS（来自未变更文件的调用关系）
        if (!incomingCalls.isEmpty()) {
            List<Map<String, Object>> relationsToRebuild = new ArrayList<>();
            for (Map<String, Object> call : incomingCalls) {
                String oldCalleeId = (String) call.get("calleeId");
                String newCalleeId = oldToNewNodeIdMap.get(oldCalleeId);
                if (newCalleeId != null) {
                    Map<String, Object> relation = new LinkedHashMap<>();
                    relation.put("callerId", call.get("callerId"));
                    relation.put("calleeId", newCalleeId);
                    relation.put("callType", call.get("callType"));
                    relation.put("callLine", call.get("callLine"));
                    relationsToRebuild.add(relation);
                }
            }
            if (!relationsToRebuild.isEmpty()) {
                methodNodeRepository.createCallRelations(relationsToRebuild);
                log.info("Rebuilt {} incoming CALLS to Python methods from unchanged files", relationsToRebuild.size());
            }
        }

        log.info("Python method nodes: created={}, updated={}, deleted={}, unchanged={}", created, updated, deleted, unchanged);
        return created + updated + deleted;
    }

    // ==================== Call Relation Rebuild ====================

    /**
     * Rebuild call relations for changed Java files using TypeSolver.
     * Mirrors KnowledgeGraphBuilder.scanCallRelationsWithCoreService.
     */
    private int rebuildCallRelations(String projectPath, List<String> javaFiles,
                                     JavaParser javaParser, Map<String, String> methodSignatureToNodeId) {
        // 1. 先删除变更文件中所有方法的 outgoing CALLS 关系
        List<String> nodeIdsToDeleteCalls = new ArrayList<>();
        for (String file : javaFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!Files.exists(filePath)) continue;
            try {
                CompilationUnit cu = coreService.parseFile(filePath.toFile(), javaParser);
                if (cu == null) continue;
                String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                    String className = packageName.isEmpty() ? clazz.getNameAsString()
                            : packageName + "." + clazz.getNameAsString();
                    clazz.findAll(MethodDeclaration.class).forEach(method -> {
                        String sigHash = signatureHash(method.getSignature().toString());
                        String key = className + "." + method.getNameAsString() + "." + sigHash;
                        String nodeId = methodSignatureToNodeId.get(key);
                        if (nodeId != null) {
                            nodeIdsToDeleteCalls.add(nodeId);
                        }
                    });
                });
            } catch (Exception e) {
                log.warn("Failed to collect nodeIds from {}: {}", file, e.getMessage());
            }
        }
        if (!nodeIdsToDeleteCalls.isEmpty()) {
            methodNodeRepository.deleteOutgoingCallsByNodeIds(nodeIdsToDeleteCalls);
            log.info("Deleted {} outgoing CALLS from changed files", nodeIdsToDeleteCalls.size());
        }

        // 2. 创建新调用关系
        int total = 0;
        for (String file : javaFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!Files.exists(filePath)) continue;
            try {
                CompilationUnit cu = coreService.parseFile(filePath.toFile(), javaParser);
                if (cu == null) continue;

                List<Map<String, Object>> relations = scanCallRelations(cu, projectPath, methodSignatureToNodeId, javaParser);
                if (!relations.isEmpty()) {
                    storageService.saveCallRelations(relations);
                    total += relations.size();
                }
                log.debug("Rebuilt {} call relations from: {}", relations.size(), file);
            } catch (Exception e) {
                log.warn("Failed to rebuild call relations from {}: {}", file, e.getMessage());
            }
        }
        log.info("Rebuilt {} call relations from changed files", total);
        return total;
    }

    /**
     * Scan call relations from a CompilationUnit using coreService.findMethodCallTargets.
     * Mirrors KnowledgeGraphBuilder.scanCallRelationsWithCoreService.
     */
    private List<Map<String, Object>> scanCallRelations(CompilationUnit cu, String projectPath,
                                                         Map<String, String> methodSignatureToNodeId,
                                                         JavaParser javaParser) {
        List<Map<String, Object>> relations = new ArrayList<>();
        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ? clazz.getNameAsString()
                    : packageName + "." + clazz.getNameAsString();

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                String callerSigHash = signatureHash(method.getSignature().toString());
                String callerKey = className + "." + method.getNameAsString() + "." + callerSigHash;
                String callerNodeId = methodSignatureToNodeId.get(callerKey);
                if (callerNodeId == null) return;

                method.findAll(MethodCallExpr.class).forEach(call -> {
                    List<MethodDeclaration> targets = coreService.findMethodCallTargets(call, clazz, method, javaParser);

                    for (MethodDeclaration target : targets) {
                        if (target.getNameAsString().startsWith("no match:")) continue;

                        String targetClassName = getMethodClassName(target);
                        String targetSigHash = signatureHash(target.getSignature().toString());
                        String targetKey = targetClassName + "." + target.getNameAsString() + "." + targetSigHash;
                        String calleeNodeId = methodSignatureToNodeId.get(targetKey);

                        if (calleeNodeId != null && !calleeNodeId.equals(callerNodeId)) {
                            Map<String, Object> relation = new LinkedHashMap<>();
                            relation.put("callerId", callerNodeId);
                            relation.put("calleeId", calleeNodeId);
                            relation.put("callType", "DIRECT");
                            relation.put("callLine", call.getBegin().map(p -> p.line).orElse(0));
                            relations.add(relation);
                        }
                    }
                });
            });
        });
        return relations;
    }

    private String getMethodClassName(MethodDeclaration method) {
        return method.findCompilationUnit()
                .map(cu -> {
                    String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString() + ".").orElse("");
                    return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                            .filter(c -> c.getMethods().contains(method))
                            .findFirst()
                            .map(c -> pkg + c.getNameAsString())
                            .orElse(pkg + "Unknown");
                })
                .orElse("Unknown");
    }

    // ==================== Entry Point Rebuild ====================

    private int rebuildJavaEntryPoints(String projectPath, List<String> javaFiles) {
        List<EntryPointNode> allEntryPoints = new ArrayList<>();
        JavaParser javaParser = new JavaParser();
        for (String file : javaFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!Files.exists(filePath)) continue;
            try {
                ParseResult<CompilationUnit> result = javaParser.parse(filePath.toFile());
                if (!result.isSuccessful() || result.getResult().isEmpty()) continue;
                CompilationUnit cu = result.getResult().get();
                List<EntryPointNode> entryPoints = createEntryPointsSimple(cu, projectPath, filePath.toString());
                allEntryPoints.addAll(entryPoints);
            } catch (Exception e) {
                log.warn("Failed to rebuild entry points from {}: {}", file, e.getMessage());
            }
        }
        if (!allEntryPoints.isEmpty()) {
            storageService.saveEntryPoints(allEntryPoints);
            log.info("Rebuilt {} Java entry points from {} files using MERGE", allEntryPoints.size(), javaFiles.size());
        }
        return allEntryPoints.size();
    }

    private int rebuildPythonEntryPoints(String projectPath, List<String> pythonFiles) {
        List<EntryPointNode> allEntryPoints = new ArrayList<>();
        for (String file : pythonFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!Files.exists(filePath)) continue;
            try {
                List<EntryPointNode> entryPoints = pythonKnowledgeGraphBuilder.buildFileEntryPoints(
                        filePath.toString(), projectPath);
                allEntryPoints.addAll(entryPoints);
            } catch (Exception e) {
                log.warn("Failed to rebuild Python entry points from {}: {}", file, e.getMessage());
            }
        }
        if (!allEntryPoints.isEmpty()) {
            storageService.saveEntryPoints(allEntryPoints);
            log.info("Rebuilt {} Python entry points from {} files using MERGE", allEntryPoints.size(), pythonFiles.size());
        }
        return allEntryPoints.size();
    }

    // ==================== DataModel Rebuild ====================

    /**
     * Scan changed Java files for DataModel nodes and USES_MODEL relations.
     */
    private int rebuildDataModels(String projectPath, List<String> javaFiles,
                                   JavaParser javaParser, Map<String, String> methodSignatureToNodeId) {
        if (javaFiles.isEmpty()) return 0;

        List<DataModelNode> dataModelNodes = new ArrayList<>();
        for (String file : javaFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!Files.exists(filePath)) continue;
            try {
                CompilationUnit cu = coreService.parseFile(filePath.toFile(), javaParser);
                if (cu == null) continue;
                dataModelNodes.addAll(javaDataModelScanner.scanDataModels(cu, filePath.toString(), projectPath));
            } catch (Exception e) {
                log.warn("Failed to scan data models from {}: {}", file, e.getMessage());
            }
        }

        if (dataModelNodes.isEmpty()) return 0;

        // Save data model nodes
        Set<String> classNames = dataModelNodes.stream().map(DataModelNode::getClassName).collect(Collectors.toSet());
        dataModelNodeRepository.saveAll(dataModelNodes);

        // Scan USES_MODEL relations
        List<Map<String, Object>> usesRelations = new ArrayList<>();
        for (String file : javaFiles) {
            Path filePath = Paths.get(projectPath, file);
            if (!Files.exists(filePath)) continue;
            try {
                CompilationUnit cu = coreService.parseFile(filePath.toFile(), javaParser);
                if (cu == null) continue;
                usesRelations.addAll(javaDataModelScanner.scanUsesModelRelations(
                        cu, projectPath, classNames, methodSignatureToNodeId));
            } catch (Exception e) {
                log.warn("Failed to scan USES_MODEL from {}: {}", file, e.getMessage());
            }
        }

        if (!usesRelations.isEmpty()) {
            dataModelNodeRepository.createUsesModelRelations(usesRelations);
        }

        log.info("Rebuilt {} data model nodes, {} USES_MODEL relations", dataModelNodes.size(), usesRelations.size());
        return dataModelNodes.size();
    }

    private static String signatureHash(String signature) {
        return Integer.toHexString(signature.hashCode());
    }

    // ==================== IMPLEMENTS/EXTENDS Re-materialization ====================

    /**
     * Re-materialize IMPLEMENTS and EXTENDS edges from GlobalCache.
     * Deletes all IMPLEMENTS/EXTENDS/OVERRIDE for the project and recreates from the
     * interface-implementation and extends maps that were populated during the last full generation.
     */
    private void reMaterializeImplementsExtends(String projectPath) {
        try {
            // Delete existing edges
            methodNodeRepository.deleteImplementsRelationsByProjectPath(projectPath);
            methodNodeRepository.deleteExtendsRelationsByProjectPath(projectPath);
            methodNodeRepository.deleteOverrideRelationsByProjectPath(projectPath);

            // Rebuild IMPLEMENTS from GlobalCache
            List<InterfaceImplementation> impls = new ArrayList<>();
            Map<String, Set<String>> implMap = globalCache.getImplementationMap();
            implMap.forEach((interfaceName, implNames) -> {
                for (String implName : implNames) {
                    impls.add(InterfaceImplementation.builder()
                            .interfaceName(interfaceName)
                            .implementationName(implName)
                            .projectPath(projectPath)
                            .implType("LOCAL")
                            .build());
                }
            });
            if (!impls.isEmpty()) {
                storageService.saveInterfaceImplementations(impls);
            }

            // Rebuild EXTENDS from GlobalCache
            List<ClassExtends> extendsRelations = new ArrayList<>();
            Map<String, Set<String>> extendMap = globalCache.getExtendMap();
            extendMap.forEach((subclass, superclasses) -> {
                for (String superclass : superclasses) {
                    extendsRelations.add(ClassExtends.builder()
                            .subclass(subclass)
                            .superclass(superclass)
                            .projectPath(projectPath)
                            .build());
                }
            });
            if (!extendsRelations.isEmpty()) {
                storageService.saveClassExtends(extendsRelations);
            }

            log.info("Re-materialized {} IMPLEMENTS, {} EXTENDS edges", impls.size(), extendsRelations.size());
        } catch (Exception e) {
            log.warn("Failed to re-materialize IMPLEMENTS/EXTENDS: {}", e.getMessage());
        }
    }

    // ==================== Java File Parsing ====================

    private List<MethodNode> parseJavaFile(JavaParser javaParser, String filePath, String projectPath) {
        List<MethodNode> nodes = new ArrayList<>();
        File file = new File(filePath);
        if (!file.exists()) return nodes;

        try {
            ParseResult<CompilationUnit> result = javaParser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return nodes;

            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = packageName.isEmpty() ? clazz.getNameAsString()
                        : packageName + "." + clazz.getNameAsString();

                clazz.findAll(MethodDeclaration.class).forEach(method -> {
                    String nodeId = projectPath + ":" + className + "." +
                            method.getNameAsString() + "." +
                            signatureHash(method.getSignature().toString());

                    MethodNode node = MethodNode.builder()
                            .nodeId(nodeId)
                            .className(className)
                            .methodName(method.getNameAsString())
                            .signature(method.getSignature().toString())
                            .filePath(filePath)
                            .startLine(method.getBegin().map(p -> p.line).orElse(0))
                            .endLine(method.getEnd().map(p -> p.line).orElse(0))
                            .complexity(calculateComplexity(method))
                            .methodBody(MethodBodyCompressor.compress(method))
                            .projectPath(projectPath)
                            .serviceName(extractServiceName(className, projectPath))
                            .language("java")  // 增量生成也需要设置 language 字段
                            .build();
                    nodes.add(node);
                });
            });
        } catch (Exception e) {
            log.error("Failed to parse Java file: {}", filePath, e);
        }
        return nodes;
    }

    // ==================== Entry Point Extraction (Java) ====================

    private List<EntryPointNode> createEntryPointsSimple(CompilationUnit cu, String projectPath, String filePath) {
        List<EntryPointNode> entryPoints = new ArrayList<>();
        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ? clazz.getNameAsString()
                    : packageName + "." + clazz.getNameAsString();

            String classLevelPath = extractClassLevelPath(clazz);
            boolean isFeignClient = clazz.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("FeignClient"));

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                String methodId = className + "." + method.getNameAsString() + "." +
                        signatureHash(method.getSignature().toString());
                String nodeId = projectPath + ":" + methodId;

                if (isFeignClient) {
                    String entryId = projectPath + ":FEIGN_" + className + "." + method.getNameAsString();
                    entryPoints.add(EntryPointNode.builder()
                            .entryId(entryId).entryType(EntryPointNode.TYPE_FEIGN_CLIENT)
                            .entryKey(className + "." + method.getNameAsString())
                            .projectPath(projectPath).methodNodeId(nodeId).build());
                    return;
                }

                for (AnnotationExpr annotation : method.getAnnotations()) {
                    String annName = annotation.getNameAsString();
                    if (isHttpAnnotation(annName)) {
                        String fullPath = combinePaths(classLevelPath, extractMethodLevelPath(annotation));
                        String entryId = projectPath + ":HTTP_" + className + "." + method.getNameAsString();
                        entryPoints.add(EntryPointNode.builder()
                                .entryId(entryId).entryType(EntryPointNode.TYPE_HTTP)
                                .entryKey(extractHttpMethod(annName) + " " + fullPath)
                                .projectPath(projectPath).methodNodeId(nodeId).build());
                    } else if (annName.equals("Scheduled")) {
                        String entryId = projectPath + ":SCHEDULED_" + className + "." + method.getNameAsString();
                        entryPoints.add(EntryPointNode.builder()
                                .entryId(entryId).entryType(EntryPointNode.TYPE_SCHEDULED)
                                .entryKey("SCHEDULED:" + className + "." + method.getNameAsString())
                                .projectPath(projectPath).methodNodeId(nodeId).build());
                    } else if (annName.equals("RabbitListener") || annName.equals("KafkaListener")
                            || annName.equals("RocketMQMessageListener")) {
                        String entryId = projectPath + ":MQ_" + className + "." + method.getNameAsString();
                        entryPoints.add(EntryPointNode.builder()
                                .entryId(entryId).entryType(EntryPointNode.TYPE_MQ_CONSUMER)
                                .entryKey("MQ:" + className + "." + method.getNameAsString())
                                .projectPath(projectPath).methodNodeId(nodeId).build());
                    } else if (annName.equals("EventListener") || annName.equals("TransactionalEventListener")) {
                        String entryId = projectPath + ":EVENT_" + className + "." + method.getNameAsString();
                        entryPoints.add(EntryPointNode.builder()
                                .entryId(entryId).entryType("EVENT")
                                .entryKey("EVENT:" + className + "." + method.getNameAsString())
                                .projectPath(projectPath).methodNodeId(nodeId).build());
                    } else if (annName.equals("PostConstruct") || annName.equals("PreDestroy")
                            || annName.equals("AfterConstruct")) {
                        String entryId = projectPath + ":LIFECYCLE_" + className + "." + method.getNameAsString();
                        entryPoints.add(EntryPointNode.builder()
                                .entryId(entryId).entryType("LIFECYCLE")
                                .entryKey("LIFECYCLE:" + className + "." + method.getNameAsString())
                                .projectPath(projectPath).methodNodeId(nodeId).build());
                    }
                }
            });
        });
        return entryPoints;
    }

    // ==================== Utility Methods ====================

    private int calculateComplexity(MethodDeclaration method) {
        int c = 1;
        c += method.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
        c += method.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();
        c += method.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();
        c += method.findAll(com.github.javaparser.ast.stmt.SwitchStmt.class).size();
        c += method.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class).size();
        c += method.findAll(com.github.javaparser.ast.expr.ConditionalExpr.class).size();
        return c;
    }

    private String extractServiceName(String className, String projectPath) {
        if (className == null || className.isEmpty()) return "unknown";
        int lastDot = className.lastIndexOf('.');
        String simpleClassName = lastDot > 0 ? className.substring(lastDot + 1) : className;
        String coreName = simpleClassName.replaceAll("(Controller|Service|Impl|Repository|Handler|Endpoint)$", "");
        String projectShortName = extractProjectShortName(projectPath);
        return projectShortName + ":" + coreName;
    }

    private String extractProjectShortName(String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) return "default";
        java.nio.file.Path p = java.nio.file.Paths.get(projectPath);
        String name = p.getFileName() != null ? p.getFileName().toString() : "default";
        return name.replaceAll("(^hisi-|-dev-tool$|-backend$|-service$|-api$)", "");
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

    private String extractClassLevelPath(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("RequestMapping"))
                .findFirst().flatMap(this::extractAnnotationValue).orElse("");
    }

    private String extractMethodLevelPath(AnnotationExpr annotation) {
        return extractAnnotationValue(annotation).orElse("");
    }

    private java.util.Optional<String> extractAnnotationValue(AnnotationExpr annotation) {
        if (annotation instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr single) {
            return java.util.Optional.of(single.getMemberValue().toString().replace("\"", ""));
        }
        if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
                    .findFirst().map(p -> p.getValue().toString().replace("\"", ""));
        }
        return java.util.Optional.empty();
    }

    private String combinePaths(String base, String sub) {
        if (base == null || base.isEmpty()) return sub == null ? "" : sub;
        if (sub == null || sub.isEmpty()) return base;
        String a = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String b = sub.startsWith("/") ? sub : "/" + sub;
        return a + b;
    }

    // ==================== Result ====================

    public record RefreshResult(boolean isNoop, int changedFiles, int deleted, int rebuiltMethods,
                                int rebuiltEntryPoints, int callRelations, int dataModels) {
        public static RefreshResult noop() {
            return new RefreshResult(true, 0, 0, 0, 0, 0, 0);
        }
    }
}
