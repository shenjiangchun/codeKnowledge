# KG Build Mode Selector

## Purpose
Provide three mutually-exclusive knowledge graph build modes — `incremental` (git-diff based), `reuse` (full rebuild with codeHash-based Method-node reuse), and `wipe` (full delete-and-insert) — selectable from the frontend and threaded through all build entry points.

## Requirements

### Requirement: 构建模式参数全链路透传
系统 SHALL 支持三种知识图谱构建模式：`incremental`（增量）、`reuse`（全量-复用）、`wipe`（全量-全删）。构建模式参数 MUST 从 API 入口透传至底层构建服务，覆盖单项目同步、单项目异步、批量、定时调度、远端项目（CodeHub clone 后）五类触发场景。

#### Scenario: 单项目异步构建携带构建模式
- **WHEN** 客户端调用 `POST /api/knowledge-graph/tasks/generate` 并传 `buildMode=reuse`
- **THEN** 后端 MUST 以「全量-复用」模式执行构建
- **AND** 该模式值 MUST 透传到 `KnowledgeGraphBuilder` 的实际构建逻辑

#### Scenario: 缺省构建模式向后兼容
- **WHEN** 客户端未传 `buildMode` 参数
- **THEN** 系统 MUST 采用默认构建模式 `reuse`（全量-复用）
- **AND** 现有仅传 `generateVector` / `generateArchitecture` 的调用 MUST 不受影响

#### Scenario: 批量构建逐项目透传构建模式
- **WHEN** 客户端调用 `POST /api/knowledge-graph/tasks/generate-batch` 并传统一的 `buildMode`
- **THEN** 批量队列 MUST 为每个项目携带该 `buildMode` 执行构建

#### Scenario: 非 Java 项目选择复用模式降级为全删
- **WHEN** 项目语言为 Python 或 TypeScript/JavaScript，且用户选择 `reuse` 模式
- **THEN** 系统 MUST 将该项目的构建降级为 `wipe`（全量-全删），并 MUST 在日志或返回结果中提示「当前项目暂不支持复用，已降级为全量-全删」

### Requirement: 全量-复用模式的节点复用判定
在全量-复用（`reuse`）模式下，系统 SHALL 全量扫描所有源文件并计算每个方法的 `codeHash`，当且仅当新计算出的 `codeHash` 与历史节点上的 `codeHash` 完全一致时，复用该历史节点的 `description`、`descriptionEmbedding`、`codeEmbedding`，不触发向量重算。

#### Scenario: codeHash 命中复用向量
- **WHEN** 全量-复用构建时某方法的 `codeHash` 与 Neo4j 中已有节点的 `codeHash` 相等
- **THEN** 系统 MUST 保留该节点的 `description` / `descriptionEmbedding` / `codeEmbedding`
- **AND** 后续向量生成 MUST 因 `descriptionEmbedding != null` 跳过该节点

#### Scenario: codeHash 未命中触发重算
- **WHEN** 全量-复用构建时某方法的 `codeHash` 与历史节点不相等（方法体/签名/注释变化）或历史节点无 `codeHash`
- **THEN** 系统 MUST 以新内容覆盖节点，并清空 `description` / `descriptionEmbedding` / `codeEmbedding`
- **AND** 后续向量生成 MUST 为该节点重新生成描述与向量

### Requirement: 全量-复用模式的孤儿节点清理
在全量-复用（`reuse`）模式下，构建末尾系统 SHALL 删除该项目下「本轮 codeHash 集合之外」的历史方法节点（即源码中已删除或逻辑已变更的孤儿节点），且不采用软隐藏或定时清理机制。

#### Scenario: 构建末尾清理孤儿节点
- **WHEN** 全量-复用构建完成一轮扫描与写入
- **THEN** 系统 MUST `DETACH DELETE` 该项目下 `projectPath` 匹配、但 `nodeId` 不在本轮重建集合内的历史 `Method` 节点
- **AND** 被删除节点的关联边 MUST 一并移除

### Requirement: 全量-复用模式仅精确复用 Method 节点
在全量-复用（`reuse`）模式下，系统 SHALL 仅保留 `Method` 节点做 codeHash 复用判定；其余节点类型（`EntryPoint` / `Sql` / `Class` / `DataModel` / `Module` / `Churn` / `Domain` / `Service`）因无法精确判断内容是否变更，MUST 全删全插（等价于 `wipe` 的清理行为），其向量由 `startVectorGeneration` 断点续传自动重算。

#### Scenario: 非 Method 节点全删重建
- **WHEN** 全量-复用构建开始清理阶段
- **THEN** 系统 MUST 删除该项目下所有非 `Method` 节点（含 `EntryPoint` / `Sql` / `Class` 等）
- **AND** 这些节点的向量/描述字段因删除而清空，后续向量生成 MUST 重新生成

#### Scenario: 仅 Method 节点保留并参与复用判定
- **WHEN** 全量-复用构建的清理阶段
- **THEN** 系统 MUST 保留该项目下的 `Method` 节点，仅对这些节点执行 codeHash 复用判定

### Requirement: 三种构建模式互斥且可自由切换
系统 SHALL 保证 `incremental`、`reuse`、`wipe` 三种模式在同一项目构建时互斥执行，用户 MUST 可在任意一次构建前自由选择模式，无需迁移数据。

#### Scenario: 全量-全删作为兜底
- **WHEN** 用户选择 `wipe`（全量-全删）模式
- **THEN** 系统 MUST 执行现有 `cleanOldData` 全删全插行为
- **AND** 不进行任何 codeHash 复用或节点保留
