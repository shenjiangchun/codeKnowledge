# Multi-Project & Cross-Service Refactoring — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove `publicProjectPath`, replace with multi-project selection (`projectPaths[]`), and add cross-service dependency build.

**Architecture:** Each project keeps its own `projectPath`-scoped KG. Queries use `WHERE n.projectPath IN $projectPaths` for multi-project scope. A new `CrossServiceBuildService` orchestrates incremental refresh → clean EXTERNAL_CALL → rebuild links.

**Tech Stack:** Spring Boot 3.2 + Java 17 + Neo4j 5.11+ + Spring Data Neo4j 7.x + Vue 3 + TypeScript + Element Plus

---

## Phase 1: Backend Data Model Cleanup (delete publicProjectPath)

### Task 1: Remove publicProjectPath from MethodNode

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/model/MethodNode.java:100-105`
- Test: `src/test/java/com/huawei/hisi/neo4j/model/NodeFieldsTest.java`

**Step 1: Delete the field from MethodNode**

In `MethodNode.java`, delete these lines (around 100-105):
```java
    /**
     * 公共项目路径 (用于跨项目共享/公共知识图谱定位)
     */
    @Property("publicProjectPath")
    private String publicProjectPath;
```

**Step 2: Fix any test referencing MethodNode.publicProjectPath**

In `NodeFieldsTest.java`, remove assertions checking `publicProjectPath` on MethodNode (around lines 60-63). In any test using `.publicProjectPath(...)` builder call on MethodNode, remove that builder call.

**Step 3: Compile to verify no immediate breakages**

Run: `mvn compile -pl hisi-dev-tool -q 2>&1 | head -40`
Expected: Compilation errors in files referencing `MethodNode.publicProjectPath` — these will be fixed in subsequent tasks.

**Step 4: Commit**
```
feat: remove publicProjectPath from MethodNode
```

---

### Task 2: Remove publicProjectPath from EntryPointNode, SqlNode, ServiceNode

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/model/EntryPointNode.java:55-61`
- Modify: `src/main/java/com/huawei/hisi/neo4j/model/SqlNode.java:95-99`
- Modify: `src/main/java/com/huawei/hisi/neo4j/model/ServiceNode.java:50-55`
- Test: `src/test/java/com/huawei/hisi/neo4j/model/NodeFieldsTest.java`

**Step 1:** Delete `publicProjectPath` field + annotation from each file.

**Step 2:** Fix `NodeFieldsTest` — remove all `publicProjectPath` assertions.

**Step 3: Commit**
```
feat: remove publicProjectPath from EntryPointNode, SqlNode, ServiceNode
```

---

### Task 3: Remove publicProjectPath from GenerationCheckpointNode

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/model/GenerationCheckpointNode.java`
- Modify: `src/test/java/com/huawei/hisi/neo4j/model/GenerationCheckpointNodeTest.java`
- Modify: `src/main/java/com/huawei/hisi/neo4j/repository/Neo4jGenerationCheckpointRepository.java`
- Test: `src/test/java/com/huawei/hisi/neo4j/repository/Neo4jGenerationCheckpointRepositoryTest.java`

**Step 1:** Remove `publicProjectPath` and `scope` fields from `GenerationCheckpointNode`.

**Step 2:** In `Neo4jGenerationCheckpointRepository`:
- Delete `findByPublicProjectPathAndProjectPath` method
- Delete `findByPublicProjectPath` method
- Update `upsertCheckpoint` Cypher: remove `publicProjectPath` from MERGE and SET clauses. MERGE key should be `{projectPath: $projectPath}` only.

**Step 3:** Fix tests: remove `.publicProjectPath(...)` from builder calls, remove references to deleted methods.

**Step 4: Commit**
```
feat: remove publicProjectPath from GenerationCheckpointNode and repository
```

---

### Task 4: Update Neo4jInitializer — remove publicProjectPath indexes, add cleanup migration

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/config/Neo4jInitializer.java:70-74, 91-92`
- Test: `src/test/java/com/huawei/hisi/neo4j/config/Neo4jInitializerIndexTest.java`

**Step 1:** In `RANGE_INDEXES` array, remove the 4 index creation statements for `publicProjectPath` on Method, EntryPoint, Sql, Service.

**Step 2:** In `BACKFILL_STATEMENTS` array, remove the `SET n.publicProjectPath = n.projectPath` statements.

**Step 3:** Add new migration statements (run once):
```java
private static final String[] CLEANUP_MIGRATIONS = {
    "MATCH (n) WHERE n.publicProjectPath IS NOT NULL REMOVE n.publicProjectPath",
    "DROP INDEX idx_method_public_project_path IF EXISTS",
    "DROP INDEX idx_entry_public_project_path IF EXISTS",
    "DROP INDEX idx_sql_public_project_path IF EXISTS",
    "DROP INDEX idx_service_public_project_path IF EXISTS"
};
```
Execute these in the `onApplicationEvent` method.

**Step 4:** Fix `Neo4jInitializerIndexTest` — remove assertions checking publicProjectPath indexes/backfill.

**Step 5: Commit**
```
feat: remove publicProjectPath indexes, add cleanup migration
```

---

## Phase 2: Delete Public KG Infrastructure

### Task 5: Delete workspace scanning classes

**Files:**
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/workspace/WorkspaceScanner.java`
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/workspace/ManifestDetector.java`
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/workspace/ServiceManifest.java`
- Delete: any tests in `src/test/.../workspace/`

**Step 1:** Delete all 3 files and their tests.

**Step 2: Commit**
```
refactor: delete WorkspaceScanner, ManifestDetector, ServiceManifest
```

---

### Task 6: Delete dispatch layer

**Files:**
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/dispatch/LanguageGraphBuilder.java`
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/dispatch/ServiceDispatcher.java`
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/dispatch/JavaLanguageAdapter.java`
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/dispatch/PythonLanguageAdapter.java`
- Delete: tests in `src/test/.../dispatch/`

**Step 1:** Delete all files and tests.

**Step 2: Commit**
```
refactor: delete dispatch layer (LanguageGraphBuilder, ServiceDispatcher, adapters)
```

---

### Task 7: Delete PublicKnowledgeGraphController and PublicKnowledgeGraphService

**Files:**
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/controller/PublicKnowledgeGraphController.java`
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/service/PublicKnowledgeGraphService.java`
- Delete: any tests for these classes

**Step 1:** Delete both files and tests.

**Step 2: Commit**
```
refactor: delete PublicKnowledgeGraphController and PublicKnowledgeGraphService
```

---

### Task 8: Clean Neo4jStorageService and KnowledgeGraphStorageService interface

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/service/storage/KnowledgeGraphStorageService.java:96-105`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/service/storage/Neo4jStorageService.java:45, 99, 154-164`
- Delete: `src/test/.../service/storage/Neo4jStorageServiceCleanByPublicPathTest.java`
- Delete: `src/test/.../service/storage/Neo4jStorageServiceSetPublicProjectPathTest.java`
- Modify: `src/test/.../service/storage/Neo4jStorageServiceDefaultsTest.java`

**Step 1:** From `KnowledgeGraphStorageService` interface, delete `cleanByPublicPath()` and `setPublicProjectPath()`.

**Step 2:** From `Neo4jStorageService`:
- Delete `cleanByPublicPath()` method (line ~154)
- Delete `setPublicProjectPath()` method (line ~161)
- In `saveMethodNodes()` (line ~45): remove the `if (publicProjectPath == null)` defaulting logic
- In `saveEntryPoints()` (line ~99): same

**Step 3:** From `Neo4jMethodNodeRepository`:
- Delete `deleteByPublicProjectPath` method (lines 461-472)
- Delete `setPublicProjectPathByProjectPath` method (lines 475-488)
- Delete `detachDeleteByFilePathAndScope` method (lines 449-458)

**Step 4:** Delete the two test files. Fix `Neo4jStorageServiceDefaultsTest` to remove `.publicProjectPath(...)` builder calls.

**Step 5: Commit**
```
refactor: remove publicProjectPath methods from storage layer
```

---

### Task 9: Clean Python KG builder and scanners

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java`
- Modify: All 6 Python scanner files in `src/main/.../python/scanner/`
- Modify: `PythonHttpCall.java`, `PythonMqCall.java`
- Modify: related tests

**Step 1:** In `PythonKnowledgeGraphBuilder.buildAndSave()`:
- Remove `publicProjectPath` parameter
- Remove all `node.setPublicProjectPath(...)` calls
- Callers that used to pass `publicProjectPath` now only pass `projectPath`

**Step 2:** In each Python scanner (FastApi, Flask, Django, Celery, HttpCall, MqCall):
- Remove `publicProjectPath` parameter from `scanModule`/`scan` methods
- Remove `setPublicProjectPath(...)` calls on created nodes
- Remove `publicProjectPath` field from `PythonHttpCall` and `PythonMqCall` records

**Step 3:** Fix all related tests — remove publicProjectPath assertions and builder calls.

**Step 4: Commit**
```
refactor: remove publicProjectPath from Python KG builder and scanners
```

---

### Task 10: Clean IncrementalRefreshService and RefreshController

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalRefreshService.java:40-46`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/controller/RefreshController.java:38, 55`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/vector/VectorWriter.java:62-67`
- Modify: `src/test/.../controller/RefreshControllerTest.java`

**Step 1:** In `IncrementalRefreshService.refresh()`:
- Change signature from `refresh(String projectPath, String publicProjectPath)` to `refresh(String projectPath)`
- Remove internal `pubPath` variable; use `projectPath` directly everywhere

**Step 2:** In `RefreshController`:
- Change `RefreshRequest` record: remove `publicProjectPath` field
- Update handler to call `service.refresh(request.projectPath())`

**Step 3:** In `VectorWriter.deleteByFilePath()`:
- Change from `(String filePath, String publicProjectPath)` to `(String filePath, String projectPath)`
- Update Cypher to use `n.projectPath = $projectPath` (not publicProjectPath scope)

**Step 4:** Fix `RefreshControllerTest` — remove publicProjectPath from JSON bodies.

**Step 5: Commit**
```
refactor: remove publicProjectPath from refresh pipeline
```

---

### Task 11: Clean CheckpointMigrationService

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/migration/CheckpointMigrationService.java`

**Step 1:** Remove any references to `publicProjectPath` in migration logic. Simplify to only deal with `projectPath`.

**Step 2: Commit**
```
refactor: simplify CheckpointMigrationService
```

---

## Phase 3: Refactor Repository Queries (scope → projectPaths)

### Task 12: Replace ByScope queries with ByProjectPaths in Neo4jMethodNodeRepository

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java:1076-1372`

**Step 1:** For each of the 17 `*ByScope` methods, create a replacement `*ByProjectPaths` method:

Replace Cypher pattern:
```cypher
WHERE coalesce(m.publicProjectPath, m.projectPath) = $scope
```
With:
```cypher
WHERE m.projectPath IN $projectPaths
```

Change parameter from `@Param("scope") String scope` to `@Param("projectPaths") List<String> projectPaths`.

Example — `findByScope` becomes `findByProjectPaths`:
```java
@Query("MATCH (m:Method) WHERE m.projectPath IN $projectPaths RETURN m")
List<MethodNode> findByProjectPaths(@Param("projectPaths") List<String> projectPaths);
```

Do the same for all 17 methods: findByProjectPaths, findByProjectPathsAndClassNameContaining, findByProjectPathsAndMethodNameContaining, findByProjectPathsAndClassNameAndMethodName, findByProjectPathsAndClassName, findByProjectPathsAndAnnotation, findByProjectPathsAndExceptionType, findByDescriptionVectorSimilarityByProjectPaths, findByDescriptionVectorSimilarityWithScoreByProjectPaths, findByCodeVectorSimilarityByProjectPaths, findByCodeVectorSimilarityWithScoreByProjectPaths, findByDescriptionVectorIndexByProjectPaths, findByDescriptionVectorIndexWithScoreByProjectPaths, findByCodeVectorIndexByProjectPaths, findByCodeVectorIndexWithScoreByProjectPaths, findMethodByEntryPointMethodNodeIdByProjectPaths, findDistinctClassNamesByProjectPaths.

Delete the old `*ByScope` methods.

**Step 2: Commit**
```
refactor: replace ByScope queries with ByProjectPaths (IN $projectPaths)
```

---

### Task 13: Replace ByScope queries for link strategies in Repository

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java:949-1026`

**Step 1:** Refactor the 4 cross-service query methods:

`findOutboundHttpCalls`:
```cypher
MATCH (caller:Method)-[:CALLS]->(callee:Method)
WHERE caller.projectPath IN $projectPaths AND callee.methodName = 'execute'
...
```

`findHttpEntries`:
```cypher
MATCH (entry:EntryPoint)
WHERE entry.projectPath IN $projectPaths AND entry.entryType = 'HTTP'
...
```

Same for `findMqProducerCalls` and `findMqConsumerEntries`.

Change all from `$publicProjectPath` param to `$projectPaths` List param.

**Step 2: Commit**
```
refactor: replace publicProjectPath with projectPaths in link queries
```

---

### Task 14: Update HybridSearchService — scope → projectPaths

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/service/HybridSearchService.java`
- Modify: `src/test/java/com/huawei/hisi/neo4j/service/HybridSearchServiceTest.java`

**Step 1:** Replace `resolveScope` method with `resolveProjectPaths`:
```java
private List<String> resolveProjectPaths(String projectPath, List<String> projectPaths) {
    if (projectPaths != null && !projectPaths.isEmpty()) return projectPaths;
    if (projectPath != null && !projectPath.isBlank()) return List.of(projectPath);
    return List.of();
}
```

**Step 2:** Replace `matchesScope` method with `matchesProjectPaths`:
```java
private boolean matchesProjectPaths(MethodNode m, List<String> projectPaths) {
    if (projectPaths.isEmpty()) return true;
    return projectPaths.contains(m.getProjectPath());
}
```

**Step 3:** Update all overloads: replace `String scope` parameter with `List<String> projectPaths`. Update internal calls to use `*ByProjectPaths` repository methods.

**Step 4:** Fix `HybridSearchServiceTest` — replace scope-based test setups with projectPaths lists.

**Step 5: Commit**
```
refactor: HybridSearchService scope → projectPaths
```

---

### Task 15: Update VectorSearchController — SearchRequest DTO

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/controller/VectorSearchController.java`

**Step 1:** Update `SearchRequest` DTO:
```java
public record SearchRequest(
    @NotBlank String query,
    String projectPath,              // backward compat: single project
    List<String> projectPaths,       // new: multi-project
    Integer limit,
    Integer graphDepth,
    String language
) {}
```

**Step 2:** In the handler, resolve:
```java
List<String> paths = request.projectPaths() != null && !request.projectPaths().isEmpty()
    ? request.projectPaths()
    : request.projectPath() != null ? List.of(request.projectPath()) : List.of();
```

Pass `paths` to `HybridSearchService`.

**Step 3: Commit**
```
feat: VectorSearchController supports projectPaths[] for multi-project search
```

---

## Phase 4: Cross-Service Dependency Build

### Task 16: Refactor LinkStrategy interface and implementations

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/link/LinkStrategy.java`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/link/CrossServiceLinker.java`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/link/HttpRestLinkStrategy.java`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/link/MqLinkStrategy.java`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/link/GrpcLinkStrategy.java`
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/link/OpenApiLinkStrategy.java`
- Modify: `src/test/.../link/CrossServiceLinkerTest.java`

**Step 1:** Change `LinkStrategy` interface:
```java
public interface LinkStrategy {
    void link(List<String> projectPaths);
}
```

**Step 2:** Update `CrossServiceLinker.link()`:
```java
public void link(List<String> projectPaths) {
    for (LinkStrategy strategy : strategies) {
        try {
            strategy.link(projectPaths);
        } catch (Exception e) {
            log.warn("Strategy {} failed: {}", strategy.getClass().getSimpleName(), e.getMessage(), e);
        }
    }
}
```

**Step 3:** Update `HttpRestLinkStrategy` and `MqLinkStrategy`:
- Change `link(String publicProjectPath)` → `link(List<String> projectPaths)`
- Call updated repository methods with `projectPaths` parameter

**Step 4:** Update stubs (GrpcLinkStrategy, OpenApiLinkStrategy) signatures.

**Step 5:** Fix `CrossServiceLinkerTest`.

**Step 6: Commit**
```
refactor: LinkStrategy and CrossServiceLinker accept projectPaths[]
```

---

### Task 17: Add EXTERNAL_CALL cleanup query to repository

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java`

**Step 1:** Add method:
```java
@Query("""
    MATCH (a:Method)-[r:EXTERNAL_CALL]->(b:Method)
    WHERE a.projectPath IN $projectPaths AND b.projectPath IN $projectPaths
    DELETE r
    RETURN count(r) AS deleted
    """)
long deleteExternalCallsBetween(@Param("projectPaths") List<String> projectPaths);
```

**Step 2: Commit**
```
feat: add deleteExternalCallsBetween query for cross-service cleanup
```

---

### Task 18: Create CrossServiceBuildController and CrossServiceBuildService

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/controller/CrossServiceBuildController.java`
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/service/CrossServiceBuildService.java`
- Create: `src/test/java/com/huawei/hisi/knowledgegraph/controller/CrossServiceBuildControllerTest.java`
- Create: `src/test/java/com/huawei/hisi/knowledgegraph/service/CrossServiceBuildServiceTest.java`

**Step 1: Write tests first**

`CrossServiceBuildServiceTest.java`:
```java
@ExtendWith(MockitoExtension.class)
class CrossServiceBuildServiceTest {
    @Mock Neo4jMethodNodeRepository methodRepo;
    @Mock IncrementalRefreshService refreshService;
    @Mock CrossServiceLinker linker;
    @Mock GenerationTaskRepository taskRepo;
    
    CrossServiceBuildService service;
    
    @BeforeEach
    void setUp() { service = new CrossServiceBuildService(methodRepo, refreshService, linker, taskRepo); }
    
    @Test
    void build_validatesAllProjectsHaveKG() {
        when(methodRepo.countByProjectPath("a")).thenReturn(0L);
        assertThatThrownBy(() -> service.build(List.of("a", "b")))
            .isInstanceOf(IllegalArgumentException.class);
    }
    
    @Test
    void build_refreshesThenLinksProjects() {
        when(methodRepo.countByProjectPath("a")).thenReturn(10L);
        when(methodRepo.countByProjectPath("b")).thenReturn(5L);
        service.build(List.of("a", "b"));
        verify(refreshService).refresh("a");
        verify(refreshService).refresh("b");
        verify(methodRepo).deleteExternalCallsBetween(List.of("a", "b"));
        verify(linker).link(List.of("a", "b"));
    }
}
```

**Step 2: Run tests — expect FAIL**

**Step 3: Implement CrossServiceBuildService**
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossServiceBuildService {
    private final Neo4jMethodNodeRepository methodRepo;
    private final IncrementalRefreshService refreshService;
    private final CrossServiceLinker linker;
    private final GenerationTaskRepository taskRepo;
    
    public Long build(List<String> projectPaths) {
        // 1. Validate
        for (String path : projectPaths) {
            long count = methodRepo.countByProjectPath(path);
            if (count == 0) {
                throw new IllegalArgumentException("Project has no KG: " + path);
            }
        }
        // 2. Incremental refresh each project
        for (String path : projectPaths) {
            try {
                refreshService.refresh(path);
            } catch (Exception e) {
                log.warn("Refresh failed for {}: {}", path, e.getMessage());
            }
        }
        // 3. Clean existing EXTERNAL_CALL between these projects
        long deleted = methodRepo.deleteExternalCallsBetween(projectPaths);
        log.info("Deleted {} EXTERNAL_CALL relations", deleted);
        // 4. Rebuild cross-service links
        linker.link(projectPaths);
        // 5. Record task
        GenerationTask task = new GenerationTask();
        task.setTaskType("CROSS_SERVICE_BUILD");
        task.setStatus("COMPLETED");
        task.setProjectPath(String.join(",", projectPaths));
        return taskRepo.save(task).getId();
    }
}
```

**Step 4: Implement CrossServiceBuildController**
```java
@RestController
@RequestMapping("/api/knowledge-graph/cross-service")
@RequiredArgsConstructor
@Slf4j
public class CrossServiceBuildController {
    private final CrossServiceBuildService buildService;
    
    public record BuildRequest(@NotEmpty List<String> projectPaths) {}
    
    @PostMapping("/build")
    public ApiResponse<Map<String, Object>> build(@Valid @RequestBody BuildRequest request) {
        try {
            Long taskId = buildService.build(request.projectPaths());
            return ApiResponse.success(Map.of("taskId", taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Cross-service build failed", e);
            return ApiResponse.error(500, "Build failed: " + e.getMessage());
        }
    }
}
```

**Step 5: Run tests — expect PASS**

**Step 6: Commit**
```
feat: add CrossServiceBuildController and CrossServiceBuildService
```

---

### Task 19: Add countByProjectPath to repository

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java`

**Step 1:** Add:
```java
@Query("MATCH (m:Method) WHERE m.projectPath = $projectPath RETURN count(m)")
long countByProjectPath(@Param("projectPath") String projectPath);
```

**Step 2: Commit**
```
feat: add countByProjectPath query
```

---

## Phase 5: Frontend — Multi-Project Selection & Cross-Service Build

### Task 20: Update knowledgeGraph.ts API

**Files:**
- Modify: `src/api/knowledgeGraph.ts`

**Step 1:** Delete: `publicScan`, `publicGenerate`, `publicStatus`, `publicRefresh` methods.

**Step 2:** Remove `publicProjectPath` from `refresh()` method signature.

**Step 3:** Add:
```typescript
crossServiceBuild(projectPaths: string[]) {
    return request.post<{ taskId: number }>('/knowledge-graph/cross-service/build', { projectPaths })
}
```

**Step 4: Commit**
```
feat(frontend): replace public KG API with crossServiceBuild
```

---

### Task 21: Update vectorSearch.ts

**Files:**
- Modify: `src/api/vectorSearch.ts`

**Step 1:** Update `VectorSearchRequest`:
```typescript
interface VectorSearchRequest {
    query: string
    projectPath?: string       // backward compat
    projectPaths?: string[]    // new: multi-project
    limit?: number
    graphDepth?: number
    language?: string
}
```

**Step 2: Commit**
```
feat(frontend): vectorSearch supports projectPaths[]
```

---

### Task 22: Refactor ProjectList.vue — multi-select + cross-service build

**Files:**
- Modify: `src/views/project/ProjectList.vue`

**Step 1:** Delete all public KG dialog code:
- Delete `showPublicKgDialog`, `publicScanning`, `publicGenerating`, `publicRefreshing`, `scannedManifests`, `selectedManifests` refs
- Delete `handleOpenPublicKgDialog`, `handlePublicGenerate`, `handlePublicRefresh` functions
- Delete the `<el-dialog v-model="showPublicKgDialog">` template section
- Delete the "公共知识图谱生成" button
- Delete the "图谱刷新" button (public refresh)

**Step 2:** Add multi-select to project table:
```vue
<el-table :data="projectList" @selection-change="handleSelectionChange">
  <el-table-column type="selection" width="55" />
  <!-- existing columns -->
</el-table>
```

Add state:
```typescript
const selectedProjects = ref<ProjectItem[]>([])
function handleSelectionChange(selection: ProjectItem[]) {
    selectedProjects.value = selection
}
```

**Step 3:** Add "跨服务依赖构建" button:
```vue
<el-button
    type="warning"
    @click="handleCrossServiceBuild"
    :disabled="selectedProjectsWithKg.length < 2"
    :loading="crossServiceBuilding"
>
    跨服务依赖构建
</el-button>
```

```typescript
const crossServiceBuilding = ref(false)
const selectedProjectsWithKg = computed(() =>
    selectedProjects.value.filter(p => knowledgeGraphStatusMap.value[p.path]?.status === 'generated')
)

async function handleCrossServiceBuild() {
    crossServiceBuilding.value = true
    try {
        const paths = selectedProjectsWithKg.value.map(p => p.path)
        const res = await knowledgeGraphApi.crossServiceBuild(paths)
        ElMessage.success(`跨服务依赖构建完成，taskId=${(res as any)?.taskId}`)
    } catch (e: unknown) {
        ElMessage.error('跨服务依赖构建失败')
    } finally {
        crossServiceBuilding.value = false
    }
}
```

**Step 4: Commit**
```
feat(frontend): replace public KG with multi-select + cross-service build
```

---

### Task 23: Update knowledge graph page for multi-project

**Files:**
- Modify: `src/views/knowledge-graph/KnowledgeGraphView.vue` (or equivalent)

**Step 1:** Add multi-project selector (dropdown with checkboxes) allowing user to pick multiple projects.

**Step 2:** Pass selected `projectPaths` to vector search and call chain APIs.

**Step 3: Commit**
```
feat(frontend): KG page supports multi-project selection
```

---

### Task 24: Update vector search panel for multi-project

**Files:**
- Modify: `src/views/knowledge-graph/SemanticSearchPanel.vue` (or equivalent vector search component)

**Step 1:** Update search request to pass `projectPaths` instead of single `projectPath`.

**Step 2: Commit**
```
feat(frontend): semantic search supports multi-project scope
```

---

## Phase 6: Regression & Cleanup

### Task 25: Full backend regression

**Step 1:** Run: `mvn -pl hisi-dev-tool test`
Expected: All tests pass (zero failures).

**Step 2:** Fix any remaining compilation errors from publicProjectPath references.

**Step 3: Commit any fixes**
```
fix: resolve remaining publicProjectPath references
```

---

### Task 26: Cleanup dead code scan

**Step 1:** Search for any remaining `publicProjectPath` references:
```bash
grep -rn "publicProjectPath" src/
```
Expected: Zero results.

**Step 2:** Search for any remaining `ByScope` method references:
```bash
grep -rn "ByScope" src/
```
Expected: Zero results (all replaced by ByProjectPaths).

**Step 3: Commit any final cleanup**
```
chore: final publicProjectPath cleanup
```

---

### Task 27: Update CLAUDE.md and documentation

**Files:**
- Modify: `.claude/CLAUDE.md` (both main and worktree versions)
- Modify: `docs/plans/2026-04-26-multi-project-cross-service-design.md` — mark as IMPLEMENTED

**Step 1:** Remove all references to `publicProjectPath` from CLAUDE.md. Update:
- "范围查询" section: `WHERE n.projectPath IN $projectPaths`
- Remove `coalesce(n.publicProjectPath, n.projectPath)` pattern
- Update architecture table

**Step 2: Commit**
```
docs: update CLAUDE.md for multi-project architecture
```

---

## Task Dependency Graph

```
Phase 1: T1 → T2 → T3 → T4 (sequential — model changes)
Phase 2: T5, T6, T7 (parallel — independent deletions) → T8 → T9 → T10 → T11
Phase 3: T12 → T13 → T14 → T15 (sequential — repository then service then controller)
Phase 4: T19 → T16 → T17 → T18 (repo first, then link layer, then controller)
Phase 5: T20 → T21 → T22 → T23 → T24 (frontend sequential)
Phase 6: T25 → T26 → T27
```

Phase 1-2 can be done first, then Phase 3-4 can proceed. Phase 5 can start after Phase 3-4 backend is stable. Phase 6 is final.
