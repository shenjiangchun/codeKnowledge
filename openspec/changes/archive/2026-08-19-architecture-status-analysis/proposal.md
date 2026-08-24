# 架构现状分析（Architecture Status Analysis）

## Why

当前领域划分（DomainNode）是「Louvain 社区逐社区命名」的结果，在稀疏依赖图上产生碎片化领域（235 类拆成 189 领域，修复后仍 23 个），且领域节点与 MethodNode 之间**没有任何关系边**（`BELONGS_TO` 只在注释里、从未创建），导致前端无法从领域下钻到类/方法。同时，语义描述、向量、架构现状三者各自独立触发，缺少统一的编排入口与定时自动刷新能力。

## What Changes

- **领域划分改用「LLM 全局归纳」**：139 类一次性输入 LLM，按业务语义归纳领域（忽略技术分层 controller/service/repository），领域数由 LLM 自定，取代 Louvain 逐社区命名。
- **领域归属改为「BELONGS_TO 边为唯一真相」**：`DomainNode -[:BELONGS_TO]-> MethodNode`，删除 `MethodNode.businessNoun` 属性（**BREAKING**：领域归属从属性撞名迁移为图边）。
- **领域下钻用「虚拟类节点」**：查询时按 `className` 聚合生成虚拟类节点（不落库），前端领域→类→方法三层下钻。
- **领域绝不进入语义检索/调用链查询路径**：`BELONGS_TO` 边仅用于领域视图下钻；语义检索、调用链上游查询的 Cypher 全程只走 `Method`/`CALLS` 边。
- **架构现状分析编排**：图谱生成、语义&向量、架构现状三者可独立勾选，支持四种编排（都不选=只图谱；只语义=图谱→语义；只架构=图谱→架构；都选=图谱→语义→架构串行）。
- **架构现状输入分层降级**：优先「类名+类注释+方法自然语言描述」；逐方法 COALESCE，无描述时降级用方法签名。
- **独立「架构现状分析」触发按钮**（前端项目管理页「语义&向量」按钮右侧）。
- **DSM 展示增强**：Top N 可配置 + 分层钻取（勾选模块→聚焦模块内类依赖），替换硬截断 Top 20。
- **ModuleNode 补 CONTAINS 边**：`ModuleNode -[:CONTAINS]-> MethodNode`，支撑 DSM 下钻。
- **定时任务增强**：支持定时 git pull 拉最新分支 + 增量生成，且增量时「是否刷新语义&向量」「是否刷新架构现状」可配置。

## Capabilities

### New Capabilities

- `architecture-status-analysis`: 架构现状分析能力，覆盖领域划分（LLM 全局归纳 + BELONGS_TO 边）、领域下钻（虚拟类节点）、架构现状编排、DSM 展示增强、ModuleNode CONTAINS 边、定时任务增强。
- `class-node-reservation`: ClassNode 实体节点预案（本次不实现，仅记录扩展性设计约束，供后续 spec 落地时参考）。

### Modified Capabilities

（无。现有 openspec/specs/ 下无领域/架构现状相关能力。）

## Impact

- **后端**：
  - `MultiDimensionCommunityDetector`：从「按社区命名」改为「LLM 全局归纳」。
  - `DomainNameGenerator`：领域归属从 `businessNoun` 属性改为 `BELONGS_TO` 边；删除 `businessNoun` 写库逻辑。
  - `MethodNode`：删除 `businessNoun` 字段（**BREAKING**，需同步调整读该字段的 dashboard/domains 端点）。
  - `KnowledgeGraphController`：`/dashboard`、`/domains` 端点改为走 `BELONGS_TO` 边，新增领域下钻端点（虚拟类节点聚合）。
  - `ModuleStatsAggregator`：新增 `CONTAINS` 边写入。
  - `KgSchedulerService` / `KgSchedule`：新增 git pull 开关、refreshDescription、refreshArchitecture 字段。
  - `IncrementalKnowledgeGraphBuilder`：增量支持 git pull + 语义/架构现状刷新。
  - `KnowledgeGraphTaskServiceImpl`：图谱生成编排入口，支持四种编排。
- **前端**：
  - `ProjectList.vue`：图谱生成弹窗（勾选语义/架构现状）+「架构现状分析」按钮。
  - `DomainBoundaryView.vue`：领域下钻（虚拟类节点展开）。
  - `DsmMatrix.vue`：Top N 可配置 + 分层钻取。
- **明确不做**（留给下一个 spec，连 ClassNode 一起）：语义检索类型化（`searchType` 字段）、实体 ClassNode 及类级语义检索、MCP 适配。
