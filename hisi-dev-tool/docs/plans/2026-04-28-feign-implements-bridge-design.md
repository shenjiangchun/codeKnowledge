# Feign IMPLEMENTS 同源桥接设计

## 问题

微服务多模块项目结构（common/client/gw/service/web），API 接口定义在 common 模块，
FeignClient（client 模块）和 ServiceImpl（service 模块）都实现/继承同一 API 接口。

当前 `buildDownstreamGraph` 遍历调用链时：
- 从 gw.Controller 出发 → CALLS → FeignClient.method() → **卡住**
- FeignClient 没有 CALLS 出边（HTTP 调用是运行时隐式的）
- 现有 IMPLEMENTS 回退把 FeignClient 当作"接口"去找实现 → 找不到
- ServiceImpl 无法被发现，调用链断裂

同理，上游查询时从 ServiceImpl 出发也无法回溯到 gw.Controller。

## 方案：IMPLEMENTS 同源桥接（两跳遍历）

### 核心思路

利用已有 IMPLEMENTS 关系的 `implType` 属性做"兄弟节点"桥接：

```
FeignClient.getUser() -[IMPLEMENTS, FEIGN_PROXY]-> UserApi.getUser()
ServiceImpl.getUser() -[IMPLEMENTS, LOCAL]-------> UserApi.getUser()
```

当调用链遍历遇到 FEIGN_PROXY 节点（无 CALLS 出边）：
1. **上溯一跳**：找到它 IMPLEMENTS 的接口方法
2. **下沉一跳**：找到该接口方法的 LOCAL 实现
3. 创建虚拟 `FEIGN_BRIDGE` 边连接

### 完整调用链效果

**下游（从 gw 入口）**：
```
gw.Controller.handleRequest()
  → [CALLS] → FeignClient.getUser()
    → [FEIGN_BRIDGE] → ServiceImpl.getUser()    ← 两跳桥接
      → [CALLS] → UserRepository.findById()
```

**上游（从 ServiceImpl 出发）**：
```
ServiceImpl.getUser()
  ← [FEIGN_BRIDGE] ← FeignClient.getUser()     ← 反向两跳桥接
    ← [CALLS] ← gw.Controller.handleRequest()
```

## 技术设计

### 1. 新增 Repository 查询

```java
// 下游：从 FEIGN_PROXY 节点找 LOCAL 兄弟实现
@Query("""
    MATCH (feign:Method {nodeId: $nodeId})-[r1:IMPLEMENTS]->(iface:Method)
    WHERE r1.implType = 'FEIGN_PROXY'
    MATCH (local:Method)-[r2:IMPLEMENTS]->(iface)
    WHERE coalesce(r2.implType, 'LOCAL') = 'LOCAL'
      AND local.nodeId <> $nodeId
    RETURN local.nodeId AS implNodeId, iface.nodeId AS ifaceNodeId
    """)
List<FeignBridgeTarget> findFeignBridgeTargets(@Param("nodeId") String nodeId);

// 上游：从 LOCAL 实现找 FEIGN_PROXY 兄弟（反向桥接）
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

### 2. 修改 buildDownstreamGraph 逻辑

```
当节点没有 CALLS 出边：
  1. 尝试路径1：当前节点是接口 → 找 LOCAL 实现（现有逻辑）
  2. 尝试路径2：当前节点是 FEIGN_PROXY → 两跳找 LOCAL 兄弟（新逻辑）
  3. 如果路径1和2都为空 → fallback 到全部实现（兼容旧数据）
```

伪代码：
```java
if (relations.isEmpty()) {
    // 路径1: 当前节点是接口方法 → 找 LOCAL 实现
    List<String> implNodeIds = findLocalImplementationMethods(nodeId);
    
    // 路径2: 当前节点是 FEIGN_PROXY → 两跳桥接
    if (implNodeIds.isEmpty()) {
        List<FeignBridgeTarget> bridges = findFeignBridgeTargets(nodeId);
        if (!bridges.isEmpty()) {
            // 添加 FEIGN_BRIDGE 边，递归到 LOCAL 实现
            for (FeignBridgeTarget target : bridges) {
                addEdge(nodeId, target.implNodeId, "FEIGN_BRIDGE");
                recurse(target.implNodeId, depth + 1);
            }
            return;
        }
    }
    
    // fallback: 全部实现（兼容旧数据）
    if (implNodeIds.isEmpty()) {
        implNodeIds = findImplementationMethodsByInterfaceMethod(nodeId);
    }
    // ... 现有递归逻辑
}
```

### 3. 修改上游查询逻辑

在 `getRootEntries` / `getCallChainsAffecting` 中：
- 当从 ServiceImpl 方法上溯时，除了找直接 callers
- 还要通过反向 FEIGN_BRIDGE 找到 FeignClient 节点
- 再从 FeignClient 继续上溯找 callers

```java
// 在获取直接调用者之后：
List<CallerWithRelation> directCallers = findCallersWithRelation(nodeId);

// 新增：通过 FEIGN_BRIDGE 反向找调用者
List<FeignBridgeCaller> feignCallers = findFeignBridgeCallers(nodeId);
for (FeignBridgeCaller fc : feignCallers) {
    // 从 FeignClient 节点继续上溯
    List<CallerWithRelation> feignUpstream = findCallersWithRelation(fc.feignNodeId);
    // 合并到结果中
}
```

### 4. Cypher 变长路径查询增强

对于使用 `[:CALLS*]` 变长路径的 Cypher（如 `findEntryPointsCallingMethodByPaths`），
需要在路径中加入 FEIGN_BRIDGE 支持：

```cypher
-- 现有
MATCH path = (entry)-[:CALLS*]->(target:Method {nodeId: $nodeId})

-- 增强（支持穿透 IMPLEMENTS 桥接）
-- 方案: 在 Java 层分段查询，不改 Cypher 变长路径语法
-- 因为 FEIGN_BRIDGE 是虚拟边（查询时计算），无法用 Cypher * 遍历
```

**决策**：FEIGN_BRIDGE 作为**查询时虚拟边**而非持久化边。好处是：
- 不增加存储复杂度
- 重新扫描不会产生脏数据
- 逻辑集中在遍历代码中

对于需要变长路径的场景（`findEntryPointsCallingMethodByPaths`），
改为 Java 分段查询：先从 target 反向找 FEIGN 兄弟，再对每个兄弟执行原有 Cypher。

### 5. 边界条件

| 场景 | 处理方式 |
|------|---------|
| 接口有多个 LOCAL 实现 | 全部展开（用户可在前端选择） |
| FeignClient 无 IMPLEMENTS 边（旧数据） | fallback 到全部实现 |
| 同一节点既有 CALLS 又有 IMPLEMENTS | 优先 CALLS，IMPLEMENTS 仅在无 CALLS 时触发 |
| 循环引用 | visitedNodes 集合阻断 |
| 跨项目桥接 | 由 CrossServiceLinker + HttpRestLinkStrategy 处理 EXTERNAL_CALL 边 |

## 不需要改动的部分

- `FeignClientScanner` — 已正确填充 proxyIndex
- `KnowledgeGraphBuilder.convertFromGlobalCache` — 已正确分类 implType
- `Neo4jStorageService` — 已正确传递 implType
- `createImplementsRelations` Cypher — 已正确存储 implType
- `CrossServiceLinker` / `HttpRestLinkStrategy` — 跨项目桥接独立于本方案

## 验证

1. `mvn compile` 通过
2. 扫描含 common/client/service 结构的微服务
3. 从 gw.Controller 查下游 → 链路穿透 FeignClient 到 ServiceImpl
4. 从 ServiceImpl 查上游 → 链路穿透 FeignClient 到 gw.Controller
5. 从 common API 接口查 → 同时看到所有实现
6. `kg_affecting` 查 ServiceImpl 方法 → 能找到 gw 入口
