# 技术设计 — KG 增量图谱构建重构

> 路由: spec-driven | 风险: Medium

---

## 1. 增量流程完整步骤

### Phase A: 全量扫描初始化 GlobalAnalysisCache

```
A1. 共享 kgb.generationSemaphore（与全量互斥）
A2. globalCache.clearAll()
A3. buildSolver(sourceRoots) → setTypeSolver
A4. 遍历 ALL Java 文件: coreService.buildImplementationMap(cu)
A5. scanBridgeEndpoints(allFilePaths, projectPath)
A6. myBatisXmlScanner.scanProjectForNeo4j() → SqlNodes
```

### Phase B: 选择性清理

```
B1. DETACH DELETE 变更文件 Method 节点（用 kgb.neo4jMethodNodeRepository）
    级联清 CALLS/IMPLEMENTS/EXTENDS/OVERRIDE/PROXY/USES_MODEL/EXECUTES_SQL
B2. 删 entry points: entryPointRepository.deleteByFilePathAndProjectPath()
B3. 删 dispatch-typed CALLS 边:
    MATCH ()-[c:CALLS]->() WHERE c.projectPath = $projectPath
    AND c.callType IN ['IMPL_DISPATCH','IMPL_DISPATCH_FEIGN','FEIGN_BRIDGE']
    DELETE c
B4. 删 incoming CALLS: deleteIncomingCallsToDeletedFiles()
```

### Phase C: 节点 + 入口点重建（仅变更文件）

**Java**:
```
C1. 仅解析变更 Java 文件: coreService.parseFile()
C2. kgb.scanMethodNodes(cu, filePath, projectPath) ← 完整字段
C3. kgb.createEntryPoints(cu, projectPath) ← 全部 7 种类型
C4. coreService.buildImplementationMap(cu) ← 更新 cache
C5. 更新 methodSignatureToNodeId / methodFullKeyToNodeId maps
C6. kgb.storageService.saveMethodNodes(rebuiltNodes) ← 15 字段完整 MERGE
C7. kgb.storageService.saveEntryPoints(rebuiltEntryPoints)
```

**Python**:
```
C1. pythonKnowledgeGraphBuilder.parseFile() → MethodNodes
C2. kgb.storageService.saveMethodNodes(nodes) ← 走完整 15 字段路径
C3. pythonKnowledgeGraphBuilder.buildFileEntryPoints()
C4. scanIncludes() + applyIncludes()（Django include 前缀补全）
C5. pythonHttpCallScanner/pyMqCallScanner → bridge entry points
C6. buildMainEntryPoints() for __main__ blocks
C7. kgb.storageService.saveEntryPoints()
```

### Phase D: 调用关系全量重扫

```
D1. Java: 遍历 ALL Java 文件 → kgb.scanCallRelationsWithCoreService()
D2. 过滤: callerId ∈ rebuiltNodeIds OR calleeId ∈ rebuiltNodeIds
D3. kgb.storageService.saveCallRelations(filteredRelations)
D4. Python: 遍历 ALL .py 文件 → parseFileWithModule()
D5. pythonCallGraphResolver.resolveProject(allModules) → filter → saveCallRelations
```

### Phase E: 结构边 + dispatch 边

```
E1. kgb.convertFromGlobalCache() → storageService.saveInterfaceImplementations()
E2. kgb.buildExtendsRelations() → storageService.saveClassExtends()
E3. kgb.buildOverrideRelations(allMethodNodes) → storageService.saveMethodOverrides()
E4. kgb.buildProxyRelations() → storageService.saveProxyRelations()
E5. kgb.synthesizeInheritedMethodNodes() → saveMethodNodes()（如有合成节点）
E6. kgb.identifyBridgeCalls(allCallRelations) → saveCallRelations()
E7. createImplDispatchEdges() + createFeignBridgeEdges()
```

### Phase F: 后处理

```
F1. myBatisXmlScanner.scanProjectForNeo4j() → mergeAll SqlNodes
F2. kgb.buildExecutesSqlRelations() → createExecutesSqlRelations
F3. 扫描变更文件 DataModel → mergeAll
F4. USES_MODEL: 全量扫描，过滤引用变更 DataModel 的边
F5. vectorGenerationService.startVectorGeneration()
F6. kgb.saveGenerationLog() → checkpointRepository.upsertCheckpoint()
```

---

## 2. Neo4j 新增 Repository 方法

### deleteDispatchCallsByProject

```java
@Query("MATCH ()-[c:CALLS]->() WHERE c.projectPath = $projectPath " +
       "AND c.callType IN ['IMPL_DISPATCH', 'IMPL_DISPATCH_FEIGN', 'FEIGN_BRIDGE'] " +
       "DELETE c")
void deleteDispatchCallsByProject(@Param("projectPath") String projectPath);
```

---

## 3. 排队策略

增量走同一个 `KgGenerationQueue`。新增 `enqueueIncremental(String projectPath)`：

- `QueueItem` 加 `boolean incremental` 标记
- `processItem()` 根据标记分发：
  - `true` → `incrementalBuilder.incrementalRefresh()`
  - `false` → `knowledgeGraphBuilder.buildKnowledgeGraph()`
- 单消费线程保证全量与增量严格串行

---

## 4. Python 字段完整性

增量 Python 路径不再手动拼 `pythonMethodNodeToMap()`，改为走 `kgb.storageService.saveMethodNodes()`，与全量 `PythonKnowledgeGraphBuilder.buildAndSave()` 相同路径。`parseFile()` 返回的 MethodNode 上 complexity 和 methodBody 已正确设置，`saveMethodNodes()` 构建完整 15 字段 Map。

---

## 5. 异常策略

| Phase | 失败策略 |
|-------|---------|
| A (cache 初始化) | 抛异常，不更新 checkpoint |
| B (清理) | 抛异常，图形可能部分清理，不更新 checkpoint |
| C (节点重建) | 抛异常，不更新 checkpoint |
| D (边重建) | 抛异常，不更新 checkpoint |
| E (结构边) | warn-and-continue（匹配全量策略），不更新 checkpoint |
| F (SQL/DataModel/向量) | warn-and-continue，不更新 checkpoint |
| Checkpoint | 仅 Phase A-D 全部成功 + E/F 不阻塞时更新 |

---

## 6. 文件影响

| 文件 | 变更 |
|------|------|
| `KnowledgeGraphBuilder.java` | 已改 protected（Step 1 完成）|
| `IncrementalKnowledgeGraphBuilder.java` | **新建**，组合模式编排增量流程 |
| `KgGenerationQueue.java` | **修改**，加 `enqueueIncremental` + `QueueItem.incremental` |
| `Neo4jMethodNodeRepository.java` | **修改**，加 `deleteDispatchCallsByProject` |
| `RefreshController.java` | **修改**，加 `/refresh-v3` 端点 |
| `IncrementalRefreshService.java` | **修改**，加 `@Deprecated` |
| `IncrementalRefreshServiceV2.java` | **修改**，加 `@Deprecated` |
