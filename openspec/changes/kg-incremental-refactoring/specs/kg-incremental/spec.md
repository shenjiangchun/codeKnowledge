# spec: kg-incremental-refactoring

## REQ-KG-INC-001: 增量构建与全量构建图状态等价

增量刷新后的 Neo4j 图（同 git commit）必须与全量 `buildKnowledgeGraph()` 产生的结果等价。等价性覆盖：
- 所有 MethodNode 节点及其全部属性
- 所有 CALLS / IMPLEMENTS / EXTENDS / OVERRIDE / PROXY 边
- IMPL_DISPATCH / IMPL_DISPATCH_FEIGN / FEIGN_BRIDGE dispatch 边
- EntryPoint 节点（全部 7 种类型）
- SqlNode 节点及 EXECUTES_SQL 边
- DataModel 节点及 USES_MODEL 边

## REQ-KG-INC-002: 增量构建复用全量方法

增量构建必须直接调用 `KnowledgeGraphBuilder` 的 protected 方法进行节点扫描、入口点创建、调用关系解析、结构边构建。不允许复制简化版逻辑。

## REQ-KG-INC-003: 增量互斥排队

增量构建与全量构建通过同一个 `KgGenerationQueue` 串行排队。同一时刻最多一个 KG 构建（全量或增量）在执行。

## REQ-KG-INC-004: Python 字段完整性

Python 增量路径走 `storageService.saveMethodNodes()`（15 字段完整 MERGE），不走手动拼 Map。EntryPoints 覆盖全量全部类型（含 Django include/HTTP bridge/MQ bridge/MAIN）。

## REQ-KG-INC-005: dispatch 边先删后建

增量 Phase B 删除所有 dispatch-typed CALLS 边（IMPL_DISPATCH/IMPL_DISPATCH_FEIGN/FEIGN_BRIDGE），Phase E 基于完整 IMPLEMENTS 边重建。

## REQ-KG-INC-006: 异常安全

Checkpoint 仅在 Phase A-D 全部成功后更新。Phase E/F 失败 warn-and-continue，不阻塞 checkpoint。
