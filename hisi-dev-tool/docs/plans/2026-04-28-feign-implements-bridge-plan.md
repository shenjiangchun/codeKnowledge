# Feign IMPLEMENTS 同源桥接 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 当调用链遍历遇到 FeignClient 代理方法（无 CALLS 出边）时，通过 IMPLEMENTS 关系两跳桥接到 LOCAL 实现（ServiceImpl），使完整调用链从 gw 入口贯通到 service 层。同时支持上游反向查询穿透。

**Architecture:** 在 Neo4j Repository 新增两个 Cypher 查询（下游桥接 + 上游桥接），修改 `buildDownstreamGraph` 和 `getRootEntries` 方法的遍历逻辑，使其在遇到 FEIGN_PROXY 节点时自动两跳桥接。不持久化虚拟边，仅在查询时动态计算。

**Tech Stack:** Spring Boot 3.2.0 + Java 17 + Spring Data Neo4j 7.2.0 + Neo4j 5.11+

---

### Task 1: 新增 Feign 桥接 Cypher 查询

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java` (after line 306)

**Step 1: 添加 FeignBridgeTarget record**

在 `Neo4jMethodNodeRepository.java` 的 record 区域（约 line 752 附近的 `EntryPointInfo` 之前）添加：

```java
/**
 * Feign 桥接目标 DTO
 * 从 FEIGN_PROXY 实现通过共享接口找到 LOCAL 实现
 */
record FeignBridgeTarget(
    String implNodeId,
    String ifaceNodeId
) {}

/**
 * Feign 桥接调用者 DTO
 * 从 LOCAL 实现通过共享接口找到 FEIGN_PROXY 兄弟（用于上游查询）
 */
record FeignBridgeCaller(
    String feignNodeId,
    String ifaceNodeId
) {}
```

**Step 2: 添加下游桥接查询**

在 `findLocalImplementationMethods` 方法（line 306）之后添加：

```java
/**
 * 下游 Feign 桥接：从 FEIGN_PROXY 节点出发，通过共享接口找到 LOCAL 兄弟实现
 * 用于调用链遍历：当遇到 FeignClient 方法（无 CALLS 出边）时，
 * 两跳穿透到 ServiceImpl 方法继续遍历
 */
@Query("""
    MATCH (feign:Method {nodeId: $nodeId})-[r1:IMPLEMENTS]->(iface:Method)
    WHERE r1.implType = 'FEIGN_PROXY'
    MATCH (local:Method)-[r2:IMPLEMENTS]->(iface)
    WHERE coalesce(r2.implType, 'LOCAL') = 'LOCAL'
      AND local.nodeId <> $nodeId
    RETURN local.nodeId AS implNodeId, iface.nodeId AS ifaceNodeId
    """)
List<FeignBridgeTarget> findFeignBridgeTargets(@Param("nodeId") String nodeId);
```

**Step 3: 添加上游桥接查询**

紧接其后添加：

```java
/**
 * 上游 Feign 桥接：从 LOCAL 实现出发，通过共享接口找到 FEIGN_PROXY 兄弟
 * 用于上游查询：从 ServiceImpl 反向穿透找到 FeignClient，继续上溯到 gw
 */
@Query("""
    MATCH (local:Method {nodeId: $nodeId})-[r1:IMPLEMENTS]->(iface:Method)
    WHERE coalesce(r1.implType, 'LOCAL') = 'LOCAL'
    MATCH (feign:Method)-[r2:IMPLEMENTS]->(iface)
    WHERE r2.implType = 'FEIGN_PROXY'
      AND feign.nodeId <> $nodeId
    RETURN feign.nodeId AS feignNodeId, iface.nodeId AS ifaceNodeId
    """)
List<FeignBridgeCaller> findFeignBridgeCallers(@Param("nodeId") String nodeId);
```

**Step 4: 编译验证**

Run: `cd "C:\Users\47583\projects\hisi_dev_tool v4.0\hisi-dev-tool" && mvn compile -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java
git commit -m "feat: add Feign bridge Cypher queries for sibling implementation traversal"
```

---

### Task 2: 修改 buildDownstreamGraph 支持 FEIGN_PROXY 两跳桥接

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java` (lines 1143-1167)

**Step 1: 重构 IMPLEMENTS 回退逻辑**

将 `buildDownstreamGraph` 方法中 line 1143-1167 的代码替换为：

```java
        // 如果当前节点没有 CALLS 出边，尝试通过 IMPLEMENTS 关系桥接
        if (relations.isEmpty()) {
            // 路径1: 当前节点是接口方法 → 找 LOCAL 实现（排除 Feign 代理）
            List<String> implNodeIds = neo4jMethodNodeRepository.findLocalImplementationMethods(nodeId);

            // 路径2: 当前节点是 FEIGN_PROXY 实现 → 两跳找 LOCAL 兄弟
            if (implNodeIds.isEmpty()) {
                List<Neo4jMethodNodeRepository.FeignBridgeTarget> bridges =
                    neo4jMethodNodeRepository.findFeignBridgeTargets(nodeId);
                if (!bridges.isEmpty()) {
                    for (Neo4jMethodNodeRepository.FeignBridgeTarget bridge : bridges) {
                        // 添加 FEIGN_BRIDGE 边（FeignClient → ServiceImpl，经过接口桥接）
                        GraphEdge edge = GraphEdge.builder()
                            .source(nodeId)
                            .target(bridge.implNodeId())
                            .callType("FEIGN_BRIDGE")
                            .callLine(0)
                            .isCycleEdge(false)
                            .build();
                        edges.add(edge);

                        // 递归处理 LOCAL 实现方法
                        buildDownstreamGraph(bridge.implNodeId(), projectPath, currentDepth + 1, maxDepth,
                            visitedNodes, nodes, edges, nodesInCycle, cycles);
                    }
                    return;
                }
            }

            // 路径3 fallback: 没有 LOCAL 也没有 FEIGN_BRIDGE → 用全部实现（兼容旧数据）
            if (implNodeIds.isEmpty()) {
                implNodeIds = neo4jMethodNodeRepository.findImplementationMethodsByInterfaceMethod(nodeId);
            }

            // 展开找到的实现
            for (String implNodeId : implNodeIds) {
                GraphEdge edge = GraphEdge.builder()
                    .source(nodeId)
                    .target(implNodeId)
                    .callType("IMPLEMENTS")
                    .callLine(0)
                    .isCycleEdge(false)
                    .build();
                edges.add(edge);

                buildDownstreamGraph(implNodeId, projectPath, currentDepth + 1, maxDepth,
                    visitedNodes, nodes, edges, nodesInCycle, cycles);
            }
            return;
        }
```

**Step 2: 编译验证**

Run: `cd "C:\Users\47583\projects\hisi_dev_tool v4.0\hisi-dev-tool" && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java
git commit -m "feat: add FEIGN_BRIDGE two-hop traversal in buildDownstreamGraph"
```

---

### Task 3: 修改上游查询支持 FEIGN_PROXY 反向穿透

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java` (lines 478-491 in `getRootEntries`)

**Step 1: 在 getRootEntries 中添加 Feign 反向桥接**

在 `getRootEntries` 方法中，`// 2. 直接调用方` 部分（line 479-491）之后，添加反向 Feign 桥接逻辑：

```java
            // 3. 通过 FEIGN_BRIDGE 反向穿透：ServiceImpl → FeignClient → 上游调用方
            List<Neo4jMethodNodeRepository.FeignBridgeCaller> feignBridges =
                neo4jMethodNodeRepository.findFeignBridgeCallers(node.getNodeId());
            for (Neo4jMethodNodeRepository.FeignBridgeCaller bridge : feignBridges) {
                // 从 FeignClient 继续找直接调用方
                List<Neo4jMethodNodeRepository.CallerWithRelation> feignCallers =
                    neo4jMethodNodeRepository.findCallersWithRelation(bridge.feignNodeId());
                for (Neo4jMethodNodeRepository.CallerWithRelation relation : feignCallers) {
                    Map<String, Object> callerInfo = new HashMap<>();
                    callerInfo.put("callerId", relation.callerId());
                    callerInfo.put("callerClassName", relation.callerClassName());
                    callerInfo.put("callerMethodName", relation.callerMethodName());
                    callerInfo.put("callType", "FEIGN_BRIDGE");
                    callerInfo.put("callLine", relation.callLine());
                    callerInfo.put("bridgedVia", bridge.feignNodeId());
                    directCallers.add(callerInfo);
                }

                // 从 FeignClient 继续找 root entries
                List<Neo4jMethodNodeRepository.EntryPointInfo> feignEntries =
                    neo4jMethodNodeRepository.findEntryPointsCallingMethodByPaths(bridge.feignNodeId(), paths);
                for (Neo4jMethodNodeRepository.EntryPointInfo entry : feignEntries) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("entryId", entry.entryId());
                    info.put("entryType", entry.entryType());
                    info.put("entryKey", entry.entryKey());
                    info.put("bridgedVia", bridge.feignNodeId());
                    rootEntries.add(info);
                }
            }
```

**Step 2: 在 getCallChainsAffecting 中添加 Feign 反向桥接**

在 `getCallChainsAffecting` 方法中（line 692 `if (node.getMethodName().equals(methodName))` 块内），在现有 `findEntryPointsCallingMethodByPaths` 调用之后添加：

```java
                // 通过 FEIGN_BRIDGE 反向穿透：如果当前方法是 LOCAL 实现，
                // 还要从其 FEIGN_PROXY 兄弟往上溯找 root entries
                List<Neo4jMethodNodeRepository.FeignBridgeCaller> feignBridges =
                    neo4jMethodNodeRepository.findFeignBridgeCallers(node.getNodeId());
                for (Neo4jMethodNodeRepository.FeignBridgeCaller bridge : feignBridges) {
                    List<Neo4jMethodNodeRepository.EntryPointInfo> bridgedEntries =
                        neo4jMethodNodeRepository.findEntryPointsCallingMethodByPaths(bridge.feignNodeId(), paths);
                    callingEntries.addAll(bridgedEntries);
                }
```

注意：需要将原来的 `callingEntries` 从不可变 List 改为可变的 ArrayList，或在声明时用 `new ArrayList<>(...)` 包装。检查原代码：

```java
List<Neo4jMethodNodeRepository.EntryPointInfo> callingEntries =
    neo4jMethodNodeRepository.findEntryPointsCallingMethodByPaths(node.getNodeId(), paths);
```

改为：

```java
List<Neo4jMethodNodeRepository.EntryPointInfo> callingEntries = new ArrayList<>(
    neo4jMethodNodeRepository.findEntryPointsCallingMethodByPaths(node.getNodeId(), paths));
```

**Step 3: 编译验证**

Run: `cd "C:\Users\47583\projects\hisi_dev_tool v4.0\hisi-dev-tool" && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java
git commit -m "feat: add upstream FEIGN_BRIDGE traversal in getRootEntries and getCallChainsAffecting"
```

---

### Task 4: 编译全量验证 + 集成确认

**Files:**
- No new files

**Step 1: Full compile**

Run: `cd "C:\Users\47583\projects\hisi_dev_tool v4.0\hisi-dev-tool" && mvn compile`
Expected: BUILD SUCCESS with no errors

**Step 2: Verify git log**

Run: `git log --oneline -5`
Expected: 3 new commits for Task 1-3

---

## 验证场景（部署后手动验证）

| 场景 | 操作 | 预期 |
|------|------|------|
| gw → service | `kg_callees_tree` 从 gw.Controller 查下游 | 穿透 FeignClient → FEIGN_BRIDGE → ServiceImpl → Repository |
| service → gw | `kg_root_entries` 查 ServiceImpl 方法 | rootEntries 包含 gw 层的 EntryPoint |
| common API → all | `kg_callees_tree` 从 API 接口方法查 | 展开所有 LOCAL 实现 |
| 无 FEIGN_PROXY（旧数据） | `kg_callees_tree` 查接口方法 | fallback 到全部实现（兼容） |
| affecting 查询 | `kg_affecting` 查 ServiceImpl 方法 | 返回 gw 入口点 |
