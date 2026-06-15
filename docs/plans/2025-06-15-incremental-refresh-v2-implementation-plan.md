# Incremental Graph Generation V2 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Implement a new incremental graph refresh system that properly initializes GlobalAnalysisCache caches and rebuilds all edges involving changed nodes.

**Architecture:** Full scan for cache initialization + Smart edge generation (only record edges involving changed nodes). Create new service/controller V2, preserve old ones for compatibility.

**Tech Stack:** Spring Boot 3.2.0 + Java 17 + Spring Data Neo4j 7.x + JavaParser

---

## Task 1: Add Repository Methods for Edge Cleanup

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java`

**Step 1: Add deleteIncomingCallsToDeletedFiles method**

Find the location after existing delete methods (around line 2047), add:

```java
/**
 * Delete incoming CALLS edges pointing to nodes from deleted files.
 * This handles reverse dependencies - unchanged methods calling changed methods.
 * 
 * Query: MATCH (m:Method)-[c:CALLS]->(target:Method)
 *        WHERE target.filePath IN $deletedFilePaths 
 *        AND m.filePath NOT IN $deletedFilePaths
 *        DELETE c
 */
@Query("""
    MATCH (m:Method)-[c:CALLS]->(target:Method)
    WHERE target.filePath IN $deletedFilePaths 
    AND NOT m.filePath IN $deletedFilePaths
    AND m.projectPath = $projectPath
    DELETE c
    """)
void deleteIncomingCallsToDeletedFiles(
    @Param("deletedFilePaths") List<String> deletedFilePaths,
    @Param("projectPath") String projectPath);
```

**Step 2: Add findByProjectPathAndDescriptionEmpty method**

```java
/**
 * Find all MethodNodes in project with empty description or descriptionEmbedding.
 * Used for incremental vector generation.
 */
@Query("""
    MATCH (m:Method)
    WHERE m.projectPath = $projectPath
    AND (m.description IS NULL OR m.description = '' OR m.descriptionEmbedding IS NULL)
    RETURN m
    """)
List<MethodNode> findByProjectPathAndDescriptionEmpty(@Param("projectPath") String projectPath);
```

**Step 3: Run compilation to verify**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java
git commit -m "feat(repository): add deleteIncomingCallsToDeletedFiles and findByProjectPathAndDescriptionEmpty methods"
```

---

## Task 2: Create IncrementalRefreshServiceV2 - Cache Initialization

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java`

**Step 1: Create service skeleton with dependencies**

```java
package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
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
import com.huawei.hisi.service.GitStatusService;
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
     * Incremental refresh with full cache initialization.
     */
    public RefreshResult refresh(String projectPath) {
        // ... implementation in next tasks
    }
}
```

**Step 2: Run compilation to verify skeleton**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit skeleton**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java
git commit -m "feat(service): create IncrementalRefreshServiceV2 skeleton"
```

---

## Task 3: Implement Cache Initialization Method

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java`

**Step 1: Add initializeCaches method**

Add after the class declaration, before refresh():

```java
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
```

**Step 2: Expose scanBridgeEndpoints in KnowledgeGraphBuilder**

Need to make `scanBridgeEndpoints` public or create a wrapper. Check KnowledgeGraphBuilder and make the method accessible:

Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`

Find `private void scanBridgeEndpoints` (around line 542), change to:

```java
/**
 * Scan bridge endpoints (Feign, MQ, HTTP) and populate GlobalCache.
 * Made public for V2 incremental refresh to reuse.
 */
public void scanBridgeEndpointsPublic(List<Path> javaFilePaths, String projectPath) {
    scanBridgeEndpoints(javaFilePaths, projectPath);
}

// Keep original private method unchanged
private void scanBridgeEndpoints(List<Path> javaFilePaths, String projectPath) {
    // ... existing code unchanged
}
```

**Step 3: Run compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java
git commit -m "feat(service): implement cache initialization for V2 incremental refresh"
```

---

## Task 4: Implement Node Cleanup Method

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java`

**Step 1: Add cleanupChangedNodes method**

```java
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
        methodNodeRepository.deleteByFilePathAndProjectPath(filePath, projectPath);
    }
    
    // Delete incoming CALLS edges (reverse dependencies)
    methodNodeRepository.deleteIncomingCallsToDeletedFiles(deletedFilePaths, projectPath);
    
    log.info("[V2] Deleted {} nodes and their edges (including reverse dependencies)", deletedNodes);
    return deletedNodes;
}
```

**Step 2: Add deleteByFilePathAndProjectPath to repository if missing**

Check if method exists. If not, add to Neo4jMethodNodeRepository:

```java
@Query("""
    MATCH (m:Method)
    WHERE m.filePath = $filePath AND m.projectPath = $projectPath
    DETACH DELETE m
    """)
void deleteByFilePathAndProjectPath(@Param("filePath") String filePath, @Param("projectPath") String projectPath);
```

**Step 3: Run compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java
git commit -m "feat(service): implement node cleanup for V2 incremental refresh"
```

---

## Task 5: Implement Node Rebuild Method

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java`

**Step 1: Add rebuildChangedNodes method**

```java
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
    // Delegate to KnowledgeGraphBuilder's method (need to make it accessible)
    // For now, implement inline similar to KnowledgeGraphBuilder.scanMethodNodes
    List<MethodNode> nodes = new ArrayList<>();
    String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    
    cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(clazz -> {
        String className = packageName.isEmpty() ? clazz.getNameAsString()
            : packageName + "." + clazz.getNameAsString();
        
        clazz.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(method -> {
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
```

**Step 2: Run compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java
git commit -m "feat(service): implement node rebuild for V2 incremental refresh"
```

---

## Task 6: Implement Edge Generation Method

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java`

**Step 1: Add rebuildEdges method**

```java
/**
 * Generate CALLS edges by full scanning all files.
 * Only record edges involving changed nodes (caller OR callee is in rebuiltNodeIds).
 */
private int rebuildEdges(String projectPath, Set<String> rebuiltNodeIds, JavaParser javaParser) {
    log.info("[V2] Rebuilding edges, filtering for {} changed nodes", rebuiltNodeIds.size());
    
    // Build methodSignatureToNodeId from Neo4j (all project nodes)
    Map<String, String> methodSignatureToNodeId = new HashMap<>();
    methodNodeRepository.findByProjectPath(projectPath).forEach(node -> {
        String sigHash = node.getSignature() != null ? signatureHash(node.getSignature()) : "0";
        String key = node.getClassName() + "." + node.getMethodName() + "." + sigHash;
        methodSignatureToNodeId.put(key, node.getNodeId());
    });
    
    List<File> allJavaFiles = coreService.findJavaFiles(projectPath, Collections.emptyList());
    List<Map<String, Object>> newCallRelations = new ArrayList<>();
    int totalScanned = 0;
    
    for (File javaFile : allJavaFiles) {
        CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
        if (cu == null) continue;
        totalScanned++;
        
        // Extract call relations using coreService
        String filePath = javaFile.getAbsolutePath();
        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + "." + clazz.getNameAsString())
                .orElse(clazz.getNameAsString());
            
            clazz.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(method -> {
                String sigHash = signatureHash(method.getSignature().toString());
                String callerNodeId = projectPath + ":" + className + "." + method.getNameAsString() + "." + sigHash;
                
                method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(methodCall -> {
                    // Resolve callee using coreService.findMethodCallTargets
                    List<com.github.javaparser.ast.body.MethodDeclaration> targets = 
                        coreService.findMethodCallTargets(methodCall, clazz, method, javaParser);
                    
                    for (com.github.javaparser.ast.body.MethodDeclaration target : targets) {
                        String targetClassName = getMethodClassName(target, clazz);
                        String targetSigHash = signatureHash(target.getSignature().toString());
                        String calleeKey = targetClassName + "." + target.getNameAsString() + "." + targetSigHash;
                        String calleeNodeId = methodSignatureToNodeId.get(calleeKey);
                        
                        if (calleeNodeId == null) {
                            // Try fallback query to Neo4j
                            calleeNodeId = methodNodeRepository.findNodeIdByClassNameAndMethodNameAndSignatureAndProjectPath(
                                targetClassName, target.getNameAsString(), target.getSignature().toString(), projectPath)
                                .orElse(null);
                        }
                        
                        if (calleeNodeId != null) {
                            // Only record if involves changed nodes
                            boolean callerChanged = rebuiltNodeIds.contains(callerNodeId);
                            boolean calleeChanged = rebuiltNodeIds.contains(calleeNodeId);
                            
                            if (callerChanged || calleeChanged) {
                                Map<String, Object> relation = new LinkedHashMap<>();
                                relation.put("callerId", callerNodeId);
                                relation.put("calleeId", calleeNodeId);
                                relation.put("callType", "DIRECT");
                                relation.put("filePath", filePath);
                                newCallRelations.add(relation);
                            }
                        }
                    }
                });
            });
        });
    }
    
    // Batch save new CALLS relations
    if (!newCallRelations.isEmpty()) {
        storageService.saveCallRelations(newCallRelations);
    }
    
    log.info("[V2] Edge generation complete: {} files scanned, {} edges recorded", 
        totalScanned, newCallRelations.size());
    return newCallRelations.size();
}

private String getMethodClassName(com.github.javaparser.ast.body.MethodDeclaration method, 
    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration contextClass) {
    // Helper to get method's class name
    return contextClass.getFullyQualifiedName().orElse(contextClass.getNameAsString());
}
```

**Step 2: Run compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java
git commit -m "feat(service): implement smart edge generation for V2 incremental refresh"
```

---

## Task 7: Implement Main refresh() Method

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java`

**Step 1: Implement refresh() method**

Replace placeholder with full implementation:

```java
public RefreshResult refresh(String projectPath) {
    String normalizedProjectPath = projectPath.replace('\\', '/');
    log.info("[V2] Starting incremental refresh for: {}", normalizedProjectPath);
    
    // 1. Get checkpoint
    Optional<GenerationCheckpointNode> checkpoint = checkpointRepository.findByProjectPath(normalizedProjectPath);
    if (checkpoint.isEmpty()) {
        log.warn("[V2] No checkpoint found for: {}", normalizedProjectPath);
        return RefreshResult.noop();
    }
    
    String lastCommit = checkpoint.get().getLastCommitHash();
    String currentCommit = gitStatusService.getCurrentCommitJgit(normalizedProjectPath);
    
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
        .lastCommitHash(currentCommit)
        .lastGenerationTime(System.currentTimeMillis())
        .build());
    
    // 9. Trigger vector generation for empty nodes (handled separately by VectorGenerationService)
    // This is called by the controller after refresh completes
    
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
        0,  // vectorsGenerated - will be filled by vector generation
        true
    );
}
```

**Step 2: Run compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2.java
git commit -m "feat(service): implement main refresh() method for V2 incremental refresh"
```

---

## Task 8: Create RefreshControllerV2

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/RefreshController.java`

**Step 1: Add V2 endpoint to existing RefreshController**

Add new endpoint alongside existing one:

```java
// Add field for V2 service
private final IncrementalRefreshServiceV2 refreshServiceV2;

// Add V2 endpoint
@PostMapping("/refresh-v2")
public ResponseEntity<ApiResponse<IncrementalRefreshServiceV2.RefreshResult>> refreshV2(
        @RequestBody RefreshRequest request) {

    if (request.projectPath() == null || request.projectPath().isBlank()) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "projectPath is required"));
    }

    try {
        var result = refreshServiceV2.refresh(request.projectPath());
        
        // Trigger vector generation for empty nodes
        if (result.success() && result.rebuiltNodes() > 0) {
            vectorGenerationService.startVectorGeneration(request.projectPath());
        }
        
        return ResponseEntity.ok(ApiResponse.success(result));
    } catch (Exception e) {
        log.error("V2 Refresh failed unexpectedly", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "V2 Refresh failed: " + e.getMessage()));
    }
}
```

**Step 2: Add VectorGenerationService dependency**

```java
// Add to existing imports and constructor
private final VectorGenerationService vectorGenerationService;
```

**Step 3: Run compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/RefreshController.java
git commit -m "feat(controller): add /refresh-v2 endpoint with vector generation trigger"
```

---

## Task 9: Create Unit Tests for V2 Service

**Files:**
- Create: `hisi-dev-tool/src/test/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2Test.java`

**Step 1: Create test skeleton**

```java
package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IncrementalRefreshServiceV2Test {

    @Mock
    private GlobalAnalysisCache globalCache;
    
    @Mock
    private Neo4jMethodNodeRepository methodNodeRepository;
    
    @Mock
    private CodeAnalysisCoreService coreService;
    
    @Mock
    private GitStatusService gitStatusService;
    
    private IncrementalRefreshServiceV2 service;

    @BeforeEach
    void setUp() {
        service = new IncrementalRefreshServiceV2(globalCache, coreService, 
            gitStatusService, methodNodeRepository, null, null, null);
    }

    @Test
    @DisplayName("refresh returns noop when no checkpoint")
    void refresh_noCheckpoint_returnsNoop() {
        // Given: no checkpoint exists
        
        // When: refresh called
        var result = service.refresh("/test/project");
        
        // Then: returns noop
        assertThat(result.success()).isTrue();
        assertThat(result.changedFiles()).isEqualTo(0);
    }
}
```

**Step 2: Run test**

Run: `cd hisi-dev-tool && mvn test -Dtest=IncrementalRefreshServiceV2Test -q`
Expected: Test passes

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/test/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshServiceV2Test.java
git commit -m "test(service): add unit tests for IncrementalRefreshServiceV2"
```

---

## Task 10: Integration Test with Real Project

**Files:**
- Manual verification with test project

**Step 1: Start Neo4j and application**

Run: Start Neo4j locally, then start Spring Boot application

**Step 2: Full generation on test project**

Run: POST `/api/knowledge-graph/generate?projectPath=<test-project-path>`

**Step 3: Modify a file in test project**

Modify a method in the test project, commit the change

**Step 4: Call V2 refresh endpoint**

Run: POST `/api/knowledge-graph/refresh-v2` with body `{"projectPath": "<test-project-path>"}`

**Step 5: Verify Neo4j results**

Check:
1. Changed method nodes updated (line numbers correct)
2. CALLS relations rebuilt (including cross-file calls)
3. Reverse dependencies captured

**Step 6: Document verification results**

Record in: `docs/plans/2025-06-15-v2-integration-test-results.md`

---

## Task 11: Deprecate Old IncrementalRefreshService

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshService.java`

**Step 1: Add @Deprecated annotation**

```java
/**
 * @deprecated Use IncrementalRefreshServiceV2 instead. 
 * This implementation does not initialize GlobalAnalysisCache caches,
 * causing cross-file call resolution failures.
 * Will be removed after V2 is validated.
 * @see IncrementalRefreshServiceV2
 */
@Deprecated(since = "5.0", forRemoval = true)
@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalRefreshService {
    // ... existing code unchanged
}
```

**Step 2: Run compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS with deprecation warning

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshService.java
git commit -m "deprecated(service): mark IncrementalRefreshService as deprecated, use V2"
```

---

## Summary

| Task | Description | Key Changes |
|---|---|---|
| 1 | Repository methods | `deleteIncomingCallsToDeletedFiles`, `findByProjectPathAndDescriptionEmpty` |
| 2 | Service skeleton | `IncrementalRefreshServiceV2` class |
| 3 | Cache initialization | `initializeCaches()` - full scan |
| 4 | Node cleanup | `cleanupChangedNodes()` - DETACH DELETE |
| 5 | Node rebuild | `rebuildChangedNodes()` - MERGE |
| 6 | Edge generation | `rebuildEdges()` - smart filter |
| 7 | Main refresh | Complete workflow |
| 8 | Controller | `/refresh-v2` endpoint |
| 9 | Unit tests | Mock-based tests |
| 10 | Integration test | Real project verification |
| 11 | Deprecation | Mark old service deprecated |

---

## Verification Commands

```bash
# Compile
cd hisi-dev-tool && mvn compile

# Test
cd hisi-dev-tool && mvn test

# Integration test
# POST /api/knowledge-graph/refresh-v2 with projectPath
```