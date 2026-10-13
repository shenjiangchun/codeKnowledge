# 前后端完整图谱构建 + 绑定驱动 + 检索/总览适配：技术设计

## Context

当前知识图谱是后端视角为主，前端图由 `KgGenerationQueue.processItem` 在后端全量建图收尾处自动触发（`frontendGraphOrchestrator.run`），靠 `FrontendProjectDiscoverer` 的 `<后端名>-frontend` 命名约定自动探测前端目录。前端节点（Component/FrontendRoute/ApiClient）无向量字段，语义检索仅命中后端 `MethodNode`。跨服务构建（`CrossServiceBuildService`）存在先删边后建边 + 异常吞掉 + 恒 `completed` 的静默失败，且 `OpenApiLinkStrategy`/`GrpcLinkStrategy` 为空壳占位符。

本设计把「前后端联合」收敛为显式绑定驱动，并为前端节点补齐向量化与检索适配，同时修复跨服务构建缺陷。约束：复用现有智谱 embedding、Neo4j 原生 VECTOR INDEX、SQLite（`SQLiteSchemaInitializer` + `JdbcTemplate`），不新增外部依赖；`application-local.yml` 中的密钥保持只读。

## Goals / Non-Goals

**Goals:**
- 用 SQLite 绑定表把前后端目录显式绑定，检索/总览入口给定任一路径自动展开为绑定组内路径集合。
- 提供「构建前后端完整图谱」端点：绑定 → 前端实体化 → 前端向量化 → 跨层链接，返回结构化结果。
- 移除后端全量建图对前端图编排的自动触发。
- 前端节点向量化并可被语义检索命中，检索结果按 `nodeType` 区分前后端。
- 修复跨服务构建：失败显式化、移除空壳策略、空选择校验、远端路径规范化、先算后写避免数据丢失。

**Non-Goals:**
- 不重写 JavaParser/codegraph 后端建图核心，不改后端 Method/Class/EntryPoint 现有节点语义。
- 不引入 OpenAPI 契约关联或运行时链路追踪（留作后续校准层）。
- 不构建前端画布手动拖拽编辑器。
- 不改 MCP `language` 枚举（`typescript` 已放开），仅补前端节点类型展示。

## Decisions

### D1: 绑定关系存 SQLite，用 JdbcTemplate 访问
- **选择**：新增 `kg_project_binding` 表（`id`、`backend_path`、`frontend_path`、`created_at`、`updated_at`，`UNIQUE(backend_path, frontend_path)`），由 `SQLiteSchemaInitializer` 建表，仿 `GlossaryTermRepository` 用 `JdbcTemplate` 实现 CRUD。
- **理由**：绑定是轻量元数据（非图语义），放 SQLite 与 `glossary_term`/`fix_session` 同构；SQLite 已有索引/迁移基建，无迁移负担。
- **备选**：Neo4j Binding 节点（图语义重、增加 schema 复杂度）；前端节点复用 `publicProjectPath`（需给三类前端节点加字段且检索改造更大）。均不选。

### D2: 绑定范围展开在后端统一解析
- **选择**：新增 `ProjectBindingService.resolve(projectPath) -> List<String>`：给定路径，返回「自身 + 所有包含该路径的绑定对中的对侧路径」。检索/总览入口（`VectorSearchController` 及总览查询）调用 `resolve` 把单 `projectPath` 展开为 `projectPaths` 后执行；无绑定则退化为单项目（向后兼容）。
- **理由**：展开逻辑集中在后端，前端无需关心绑定解析；所有检索/总览入口复用同一 `resolve`，避免多处重复。
- **备选**：前端选目录时先查绑定再传 `projectPaths`——前端改动分散、易漏。

### D3: 前端节点向量化复用现有 embedding 链路
- **选择**：给 `ComponentNode`/`FrontendRouteNode`/`ApiClientNode` 增加 `descriptionEmbedding`（`double[]`，维度与后端一致）；新增前端节点描述生成器（Component→组件名+描述，FrontendRoute→路径+目标组件，ApiClient→method+URL+源文件+组件名），在「构建前后端完整图谱」流程中批量生成向量并写回；`Neo4jInitializer` 为三类前端节点建 VECTOR INDEX（cosine）。
- **理由**：复用 `EmbeddingService`（智谱 embedding-3, 2048d）与 Neo4j 原生向量索引，不新造向量设施；前端节点向量化后语义检索才可能命中前端节点。
- **备选**：不向量化、仅靠关键词/图遍历命中前端节点——无法满足「语义检索命中前端节点」目标（用户已选向量化）。

### D4: 检索结果统一为带 `nodeType` 的命中模型
- **选择**：新增统一结果 DTO `KgSearchHit`（`nodeType`、`nodeId`、`displayName`、`description`、`filePath`、`startLine`、`similarityScore`，及类型特化字段如 `className`/`methodName`/`url`/`componentName`）。`HybridSearchService` 在给定范围命中后端 `MethodNode` 之外，追加前端三类节点的向量检索，结果统一映射为 `KgSearchHit` 返回；`QueryTypeDetector` 识别前端节点类型。
- **理由**：现有 `VectorSearchResult` 是 MethodNode 导向的，无法承载前端节点；统一 DTO 让前端按 `nodeType` 差异化渲染，且保留后端方法字段（向后兼容）。
- **备选**：在现有 DTO 上塞可选前端字段——字段爆炸、语义混乱。

### D5: 跨服务构建「先算后写」+ 移除空壳
- **选择**：
  - `LinkStrategy` 接口重构为 `List<Map<String,Object>> link(List<String> projectPaths)`（纯计算、返回命中关系、不写库）。
  - `CrossServiceLinker.link()` 汇总各策略命中关系并返回（结构化计数）。
  - `CrossServiceBuildService.build()` 顺序：校验 → 增量刷新 → 计算命中关系 → 删旧边 → 建新边；命中计数随结果返回；任一步失败即返回错误，不假 `completed`。
  - 删除 `OpenApiLinkStrategy` 与 `GrpcLinkStrategy` 空壳。
- **理由**：「先算后写」让「删除旧边数/各策略命中边数」可统计返回，且避免「先删边、建边失败丢数据」窗口；移除空壳避免 `CrossServiceLinker` 遍历 4 策略只有 2 个干活的误导。
- **备选**：`@Transactional` 包裹删+建依赖 Neo4j 事务回滚——能防丢数据，但无法自然产出结构化计数，且 SDN 事务边界需额外验证；故选「先算后写」。

### D6: 绑定与联合构建的触发链路
- **选择**：新增 `ProjectBindingController`（CRUD + `/build` 端点），前端在 `KnowledgeGraphView` 加「构建前后端完整图谱」按钮 + 绑定选择弹窗；`/build` 内部调用 `FrontendGraphOrchestrator`（改为显式路径入参，不再自动发现）+ 前端向量化 + `FrontendBackendLinker`。
- **理由**：把「选择目录→绑定→构建」收敛为一个显式端点，删除 `KgGenerationQueue` 的自动触发，用户对「何时构建前端图」有明确控制与反馈。
- **备选**：保留自动触发、仅加手动按钮——两套触发路径并存，状态机复杂、易重复建图；不选。

## Risks / Trade-offs

- **[风险] 前端节点向量化增加建图耗时与 embedding 成本** → 缓解：仅在显式「构建前后端完整图谱」时执行（非后端全量建图路径），异步队列 + 失败计数不阻断实体化。
- **[风险] 检索命中模型变更影响现有前端消费方** → 缓解：`KgSearchHit` 保留后端方法字段，`nodeType=Method` 时字段与旧 `VectorSearchResult` 兼容；前端语义检索面板增量适配。
- **[风险] 移除自动触发导致存量用户前端图不再更新** → 缓解：绑定构建端点 + 前端提示引导；已有前端节点数据不受影响（仅停止自动重建）。
- **[风险] LinkStrategy 接口重构波及现有单测** → 缓解：`HttpRestLinkStrategy`/`MqLinkStrategy` 逻辑不变、仅改返回；同步更新对应单测断言。
- **[风险] 绑定展开后检索范围变大，召回噪声上升** → 缓解：结果按 `nodeType` 分组展示，前端可切换只看后端/前端。
- **[风险] 删旧边建新边的幂等与并发** → 缓解：绑定构建端点串行执行（复用现有队列语义），避免并发写同一绑定组。

## Migration Plan

1. **建表**：`SQLiteSchemaInitializer` 新增 `kg_project_binding` 建表语句（`CREATE TABLE IF NOT EXISTS`，幂等，无手动迁移）。
2. **向量索引**：`Neo4jInitializer` 为三类前端节点建 VECTOR INDEX（幂等，启动自动执行）。
3. **数据兼容**：既有前端节点（无向量）不会被删除；后续「构建前后端完整图谱」时补向量。
4. **回滚**：还原 `KgGenerationQueue` 自动触发调用、还原 `LinkStrategy` 接口签名、删除绑定表建表语句即可；前端节点向量字段为可空，不阻塞回滚。

## Open Questions

- 绑定是否允许「一个后端绑定多个前端 / 一个前端绑定多个后端」？（本设计按「绑定对」模型支持多条记录；若需严格 1:1，需加 `backend_path` 或 `frontend_path` 侧唯一约束——待实现期确认。）
- 前端节点描述生成是否引入 LLM 摘要（`glm-4-flash`）还是仅用静态拼接？（本设计默认静态拼接以控成本；LLM 摘要可作为后续增强。）
