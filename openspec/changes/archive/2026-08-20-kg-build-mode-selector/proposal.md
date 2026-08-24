## Why

知识图谱全量重建对万级节点的项目极慢，瓶颈在于 LLM 自然语言描述 + 双向量生成（每个方法 2 次 embedding + 1 次 LLM 调用）。现有增量构建虽可复用未变更节点的向量，但其依赖 `git diff`，在「未 commit / untracked / 外部依赖变更」等场景会漏检，用户对其准确性不放心。需要一个既「确定性强」又能「跳过向量重算」的构建模式，并让用户在增量 / 全量复用 / 全量全删 三种模式间自由选择。

## What Changes

- 前端「构建模式」下拉框：`增量` / `全量-复用` / `全量-全删`，默认 `全量-复用`。
- 后端构建链路增加一个 `buildMode` 参数，经 `KnowledgeGraphController` → `KnowledgeGraphTaskServiceImpl` / `KgGenerationQueue` → `KnowledgeGraphBuilder` 透传；单项目（同步/异步）、批量、定时调度、远端项目（CodeHub clone 后）全部支持选择构建模式。
- **全量-复用模式**（核心新增）：全量扫描计算 `codeHash`，命中历史节点则复用其 `description` / `descriptionEmbedding` / `codeEmbedding`（不重算向量）；未命中则 MERGE 覆盖并清空向量字段触发重算；构建末尾当场 `DETACH DELETE` 本轮 `codeHash` 集合之外的历史孤儿节点（无软隐藏、无定时清理任务）。
- **全量-全删模式**：保持现有行为（`cleanOldData` 全删全插），作为确定性兜底。
- **增量模式**：保持现有 `IncrementalKnowledgeGraphBuilder` 行为不变。
- 复活 `MethodNode.codeHash` 字段（schema 已存在，`mergeAll` 与 `scanMethodNodes` 未填充/使用），落地「Phase 2 delta skip」逻辑。

## Capabilities

### New Capabilities
- `kg-build-mode-selector`: 知识图谱构建的三模式选择（增量 / 全量-复用 / 全量-全删），模式参数在全链路透传，前端下拉框统一选择，全量-复用模式实现基于 codeHash 的节点复用与孤儿清理。

### Modified Capabilities
- `kg-vector-code-fingerprint`: 将 `codeHash` 的计算公式从 `className.methodName(signature)\nmethodBody` 调整为纳入 `comment`（`签名 + "\n" + comment + "\n" + methodBody`），并将「Phase 2 delta skip 延期」推进为「本 change 落地 codeHash 复用判定」。

## Impact

- **代码**：
  - `KnowledgeGraphBuilder.java`（新增 buildMode 分支 + codeHash 计算 + 复用/清理逻辑）
  - `Neo4jStorageService.java` / `Neo4jMethodNodeRepository.java`（`mergeAll` 填充 codeHash；新增「复用命中不覆盖向量」与「删除本轮 codeHash 集合外孤儿」的 Cypher）
  - `MethodNode.java`（codeHash 字段已存在，确认索引/迁移由 `Neo4jInitializer` 覆盖）
  - `KnowledgeGraphController.java` / `KnowledgeGraphTaskServiceImpl.java` / `KgGenerationQueue.java`（`QueueItem` 增加 buildMode 字段并透传）
  - `KgSchedulerService.java`（定时任务透传 buildMode）
- **前端**：构建触发 UI 增加「构建模式」下拉框，请求体/参数追加 `buildMode` 字段。
- **API**：`POST /api/knowledge-graph/tasks/generate`、`POST /api/knowledge-graph/tasks/generate-batch` 等新增可选 `buildMode` 参数（`incremental` / `reuse` / `wipe`，默认 `reuse`），向后兼容（缺省按 `reuse` 处理，或沿用现有 generateVector/generateArchitecture 语义）。
- **数据**：`MethodNode.codeHash` 字段正式启用；历史节点 `codeHash` 为 null 时视为「未指纹」，首次全量-复用构建会回填。
- **风险**：`comment` 字段在 Java 扫描链路当前恒为 null（`scanMethodNodes` 未填充），codeHash 公式预留 comment 位，注释敏感性在将来接入 `CommentExtractor` 后自动生效；当前不引入额外注释敏感性回归。
