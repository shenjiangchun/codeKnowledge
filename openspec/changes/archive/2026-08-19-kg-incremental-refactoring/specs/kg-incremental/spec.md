# Spec: kg-incremental-refactoring

## ADDED Requirements

### Requirement: 增量构建与全量构建图状态等价

增量刷新后的 Neo4j 图 SHALL（同 git commit）必须与全量 `buildKnowledgeGraph()` 产生的结果等价。等价性覆盖：所有 MethodNode 节点及全部属性、所有 CALLS / IMPLEMENTS / EXTENDS / OVERRIDE / PROXY 边、IMPL_DISPATCH / IMPL_DISPATCH_FEIGN / FEIGN_BRIDGE dispatch 边、EntryPoint 节点（全部 7 种类型）、SqlNode 节点及 EXECUTES_SQL 边、DataModel 节点及 USES_MODEL 边。

#### Scenario: 增量后图与全量等价
- **WHEN** 同一 git commit 分别执行全量构建与增量刷新
- **THEN** 两者产生的 Neo4j 图（节点 + 边 + 属性）等价

### Requirement: 增量构建复用全量方法

增量构建 SHALL 直接调用 `KnowledgeGraphBuilder` 的 protected 方法进行节点扫描、入口点创建、调用关系解析、结构边构建。不允许复制简化版逻辑。

#### Scenario: 增量复用全量方法
- **WHEN** 增量构建执行节点扫描、入口点创建、调用关系解析、结构边构建
- **THEN** 直接复用 `KnowledgeGraphBuilder` 的 protected 方法，不复制简化版逻辑

### Requirement: 增量互斥排队

增量构建与全量构建 SHALL 通过同一个 `KgGenerationQueue` 串行排队。同一时刻最多一个 KG 构建（全量或增量）在执行。

#### Scenario: 增量全量互斥
- **WHEN** 增量构建与全量构建同时触发
- **THEN** 两者通过同一个 `KgGenerationQueue` 串行排队，同一时刻最多一个构建执行

### Requirement: Python 字段完整性

Python 增量路径 SHALL 走 `storageService.saveMethodNodes()`（15 字段完整 MERGE），不走手动拼 Map。EntryPoints 覆盖全量全部类型（含 Django include/HTTP bridge/MQ bridge/MAIN）。

#### Scenario: Python 增量字段完整
- **WHEN** Python 项目增量刷新
- **THEN** 走 `storageService.saveMethodNodes()` 15 字段完整 MERGE，EntryPoints 覆盖全量全部类型

### Requirement: dispatch 边先删后建

增量 Phase B SHALL 删除所有 dispatch-typed CALLS 边（IMPL_DISPATCH/IMPL_DISPATCH_FEIGN/FEIGN_BRIDGE），Phase E 基于完整 IMPLEMENTS 边重建。

#### Scenario: dispatch 边先删后建
- **WHEN** 增量构建执行
- **THEN** Phase B 删除所有 dispatch-typed CALLS 边，Phase E 基于完整 IMPLEMENTS 边重建

### Requirement: 异常安全

Checkpoint SHALL 仅在 Phase A-D 全部成功后更新。Phase E/F 失败 warn-and-continue，不阻塞 checkpoint。

#### Scenario: 异常安全
- **WHEN** Phase E/F 失败
- **THEN** 系统 warn-and-continue，不阻塞 checkpoint；Checkpoint SHALL 仅在 Phase A-D 全部成功后更新
