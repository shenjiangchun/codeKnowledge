# Incremental Graph Generation Refactoring Design

**Date**: 2025-06-15
**Author**: Claude + User
**Status**: Approved

---

## Problem Statement

The current incremental graph generation system has a critical flaw: it does NOT initialize the GlobalAnalysisCache maps (`implementationMap`, `extendMap`, `typeSolver`) that are required for cross-file call resolution. This causes:

- Cross-file calls like `userService.findAll()` cannot be resolved
- Method nodes are correctly updated, but CALLS relations remain at 0
- Reverse dependencies (unchanged methods calling changed methods) are not rebuilt

---

## Solution Design

### Core Principle

**Full scan for cache + Smart edge generation for changes**

- Scan all project files to initialize caches (ensure completeness)
- Only generate edges involving changed nodes (avoid redundant writes)
- Clean all edges related to changed nodes, then rebuild

---

## Implementation Steps

### Step 1: Cache Initialization (Full Scan)

Before processing changes, initialize GlobalAnalysisCache by scanning all project files:

```java
// In IncrementalRefreshServiceV2.refresh()
globalCache.clearAll();

// Build TypeSolver
List<Path> sourceRoots = coreService.findSourceRoots(Paths.get(projectPath));
CombinedTypeSolver solver = buildSolver(sourceRoots);
globalCache.setTypeSolver(solver);

// Scan all Java files for implementationMap and extendMap
List<File> allJavaFiles = coreService.findJavaFiles(projectPath, excludePaths);
for (File javaFile : allJavaFiles) {
    CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
    if (cu == null) continue;
    coreService.buildImplementationMap(cu);
}

// Scan bridge endpoints (Feign, MQ, HTTP)
List<Path> allFilePaths = allJavaFiles.stream().map(File::toPath).collect(Collectors.toList());
scanBridgeEndpoints(allFilePaths, projectPath);
```

**Caches initialized:**
- `implementationMap` - interface to implementation classes mapping
- `extendMap` - class to parent class/interface mapping
- `typeSolver` - CombinedTypeSolver for symbol resolution
- Bridge caches - FeignClient, MQ endpoints, HTTP calls

---

### Step 2: Node Cleanup (DETACH DELETE)

Delete all MethodNodes from changed files and their edge relations:

```java
// Delete method nodes and their edges for each changed file
for (String file : changedFiles) {
    String absoluteFilePath = Paths.get(projectPath, file).toString();
    methodNodeRepository.deleteByFilePathAndProjectPath(absoluteFilePath, projectPath);
    // DETACH DELETE removes node + all connected edges
}
```

Also delete reverse edges (unchanged nodes pointing to deleted nodes):

```java
// Delete incoming CALLS edges to deleted nodes
// Query: MATCH (m:Method)-[c:CALLS]->(target:Method)
//        WHERE target.filePath IN $deletedFiles AND m.filePath NOT IN $deletedFiles
//        DELETE c
methodNodeRepository.deleteIncomingCallsToDeletedFiles(deletedFilePaths, projectPath);
```

---

### Step 3: Node Rebuild (MERGE)

Parse changed files and create new MethodNodes:

```java
List<MethodNode> newMethodNodes = new ArrayList<>();
for (String file : javaFiles) {
    Path filePath = Paths.get(projectPath, file);
    if (!Files.exists(filePath)) continue;
    CompilationUnit cu = coreService.parseFile(filePath.toFile(), javaParser);
    if (cu == null) continue;
    List<MethodNode> nodes = scanMethodNodes(cu, filePath.toString(), projectPath);
    newMethodNodes.addAll(nodes);
}
methodNodeRepository.mergeAll(newMethodNodes);
```

---

### Step 4: Edge Generation (Full Scan, Smart Record)

Full scan all files, but only record edges involving changed nodes:

```java
Set<String> changedMethodNodeIds = newMethodNodes.stream()
    .map(MethodNode::getNodeId)
    .collect(Collectors.toSet());

List<Map<String, Object>> newCallRelations = new ArrayList<>();

// Full scan all files
for (File javaFile : allJavaFiles) {
    CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
    if (cu == null) continue;

    // Extract call relations
    List<CallRelation> relations = extractCallRelations(cu, projectPath);

    for (CallRelation relation : relations) {
        String callerNodeId = relation.getCallerNodeId();
        String calleeNodeId = relation.getCalleeNodeId();

        // Only record if involves changed nodes
        boolean callerChanged = changedMethodNodeIds.contains(callerNodeId);
        boolean calleeChanged = changedMethodNodeIds.contains(calleeNodeId);

        if (callerChanged || calleeChanged) {
            // Three scenarios:
            // 1. changed → changed (both nodes new)
            // 2. changed → unchanged (caller new)
            // 3. unchanged → changed (callee new, reverse dependency)
            newCallRelations.add(relation.toMap());
        }
    }
}

// Batch save new CALLS relations
callRelationRepository.saveAll(newCallRelations);
```

---

### Step 5: Vector Generation (All Empty Nodes)

Scan all nodes with empty description/embedding, generate vectors:

```java
// Find all nodes with empty description
List<MethodNode> emptyNodes = methodNodeRepository.findByProjectPathAndDescriptionEmpty(projectPath);

// Generate description and embedding in batches
vectorGenerationService.generateBatch(emptyNodes, projectPath);
```

---

## Edge Scenarios Summary

| Caller | Callee | Action | Discovery Method |
|---|---|---|---|
| Changed | Changed | ✅ Record | Parse changed file |
| Changed | Unchanged | ✅ Record | Parse changed file |
| Unchanged | Changed | ✅ Record | Full scan (reverse dependency) |
| Unchanged | Unchanged | ❌ Skip | Already accurate in Neo4j |

---

## Performance Considerations

| Operation | Cost | Acceptable? |
|---|---|---|
| Full scan for cache | O(n) files, parse only | ✅ Yes |
| Full scan for edges | O(n) files, parse + filter | ✅ Yes |
| Vector generation | O(k) empty nodes, LLM API | ⚠️ Main cost |

The main cost is vector generation (LLM API calls). Full scanning overhead is acceptable because:
- Parse-only operations are fast
- Only write edges involving changed nodes
- Avoid redundant database writes

---

## API Design

### New Endpoint: `/api/kg/incremental-refresh-v2`

```java
@RestController
@RequestMapping("/api/kg")
public class IncrementalRefreshControllerV2 {

    @PostMapping("/incremental-refresh-v2")
    public ApiResponse<RefreshResult> refresh(@RequestParam String projectPath) {
        return ApiResponse.success(incrementalRefreshServiceV2.refresh(projectPath));
    }
}
```

### Service: `IncrementalRefreshServiceV2`

New service implementing the design above. Old `IncrementalRefreshService` preserved for compatibility.

---

## Migration Plan

1. **Phase 1**: Implement `IncrementalRefreshServiceV2` with new logic
2. **Phase 2**: Add new API endpoint, keep old endpoint
3. **Phase 3**: Test new endpoint with real projects
4. **Phase 4**: Switch frontend to new endpoint
5. **Phase 5**: Remove old `IncrementalRefreshService` and old endpoint

---

## Files to Modify/Create

| File | Action | Description |
|---|---|---|
| `IncrementalRefreshServiceV2.java` | Create | New incremental refresh logic |
| `IncrementalRefreshControllerV2.java` | Create | New API endpoint |
| `Neo4jMethodNodeRepository.java` | Modify | Add `deleteIncomingCallsToDeletedFiles` method |
| `Neo4jMethodNodeRepository.java` | Modify | Add `findByProjectPathAndDescriptionEmpty` method |
| `IncrementalRefreshService.java` | Preserve | Keep for compatibility, mark deprecated |
| `IncrementalRefreshController.java` | Preserve | Keep for compatibility |

---

## Testing Strategy

1. **Unit tests**: Test each step with mock data
2. **Integration test**: Full workflow with test project
3. **Real project test**: Run on actual Spring Boot project
4. **Edge case test**: Multiple files changed, interface changes, deleted methods

---

## References

- Current implementation: `IncrementalRefreshService.java`
- Full generation: `KnowledgeGraphBuilder.java`
- Cache: `GlobalAnalysisCache.java`
- Call resolution: `CodeAnalysisCoreService.java`