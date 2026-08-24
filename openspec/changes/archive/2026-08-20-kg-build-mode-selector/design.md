## Context

知识图谱构建当前有两条链路：全量（`KnowledgeGraphBuilder.buildJavaKnowledgeGraph`，`cleanOldData` 全删全插后 `startVectorGeneration` 全量重算向量）与增量（`IncrementalKnowledgeGraphBuilder`，依赖 `git diff` 只重建变更文件，未变更节点向量保留）。瓶颈是向量/NL 生成（万级节点 × LLM 描述 + 双 embedding）。

增量虽省向量，但用户担心其准确性（未 commit / untracked / 外部依赖变更会漏检）。需要一个「确定性强 + 跳过向量重算」的第三模式。`MethodNode.codeHash` 字段已存在（来自 `kg-vector-code-fingerprint` capability），但从未被填充或用于判定。

## Goals / Non-Goals

**Goals:**
- 提供三种互斥构建模式：`incremental` / `reuse` / `wipe`，前端下拉框统一选择，默认 `reuse`。
- `reuse` 模式全量扫描 + codeHash 判定，命中复用向量，未命中重算，构建末尾当场清理孤儿。
- 参数覆盖单项目（同步/异步）、批量、定时、远端五类入口。
- 落地 `codeHash` 字段的 Phase 2 skip 逻辑。

**Non-Goals:**
- 不实现软隐藏（`active=false`）与 30 天定时清理任务——由「当场清理孤儿」替代。
- 不改 `nodeId` 语义（仍为 `projectPath:className.methodName.signatureHash`），避免下游 `methodNodeId` / IMPLEMENTS / Feign 桥接假设崩坏。
- 不在本 change 内打通 `comment` 字段扫描（`scanMethodNodes` 当前未填充 comment，公式预留位）。
- 不删除增量链路。

## Decisions

### D1: codeHash 公式纳入 comment（而非仅 methodBody）
- **选择**：`SHA-256(className.methodName(signature) + "\n" + comment + "\n" + methodBody)`。
- **理由**：LLM 描述明确读 `node.getComment()`（`LLMDescriptionService` 的 `PROMPT_TEMPLATE_WITH_BODY`），若 hash 不含 comment，注释变更不会触发重算，产生陈旧描述。
- **备选**：仅签名+methodBody（会漏注释）；全含 glossary（术语表变更会全项目重算，不划算，术语表变更走显式 `regenerateAll`）。
- **现实约束**：`comment` 当前恒 null（`scanMethodNodes` 未填充），故当前 hash 退化为签名+methodBody，注释敏感性在将来接入 `CommentExtractor` 后自动生效，公式无需再改。

### D2: codeHash 作为独立属性，nodeId 保持不变
- **选择**：`MethodNode` 新增/启用 `codeHash` 属性，`mergeAll` 写入；复用判定比较 `codeHash`，不动 `nodeId`。
- **理由**：`nodeId` 被下游大量引用（入口点 `methodNodeId`、跨项目边、Feign 桥接、IMPLEMENTS 匹配）。改成 contentHash 会牵动全局。
- **备选**：改 nodeId 为 contentHash（语义干净但下游全崩，否决）。

### D3: reuse 模式「复用命中不覆盖向量」的实现方式
- **选择**：构建时先全量计算本轮 codeHash 集合；对命中节点，`mergeAll` 更新结构字段但**不覆盖** `description`/`descriptionEmbedding`/`codeEmbedding`；对未命中节点，覆盖并显式清空这三个向量字段，让 `startVectorGeneration` 的「断点续传」（`descriptionEmbedding == null`）自动只重算未命中节点。
- **理由**：复用已有的 `VectorGenerationService` 断点续传逻辑，无需改向量生成侧。
- **备选**：在 `VectorGenerationService` 增加 codeHash 参数做显式 skip——多改一处，收益低。

### D4: 孤儿清理「当场删」，不用墓碑
- **选择**：构建末尾 `DETACH DELETE` 该项目下「本轮 codeHash 集合之外」的历史 `Method` 节点。
- **理由**：`codeHash` 未命中即代表方法体/签名已变或方法已删，旧向量必然失效，保留无意义；软隐藏会制造 30 天「已删方法仍可搜到」的污染窗口。
- **备选**：软隐藏 + 定时清理（原提案），因制造窗口期污染且需给 60+ 查询方法加过滤，否决。

### D6: reuse 模式只保留 Method 节点（「不能精确判断复用的都重算」原则）
- **选择**：reuse 模式**仅保留 `Method` 节点**（唯一有 `codeHash` 可精确判定复用的节点）；其余 `EntryPoint` / `Sql` / `Class` / `DataModel` / `Module` / `Churn` / `Domain` / `Service` 全部照 WIPE 全删全插。
- **理由**：只有 `Method` 有 `codeHash`，能精确判断「向量是否仍有效」。其余节点要么无可靠 hash（`EntryPoint.entryId` 不含内容、`Sql` 的 `sqlId` 不含语句文本、`Class` 无 hash），要么无昂贵产物。为杜绝陈旧向量，凡不能精确判断复用的，一律重算（= 删了重建）。
- **推论（关键简化）**：reuse 模式 = WIPE 模式 + 「Method 级 codeHash 复用」。其余节点全删全插后，其 embedding 自然为 null，被 `startVectorGeneration` 断点续传自动重算，无需额外失效信号。
- **备选**：为 Sql 加 sqlHash、EntryPoint 加 content hash、Class 用方法命中信号——各自能精确化，但引入三套失效信号，复杂度高；用户拍板「不能精确判断的都重算」，故不引入。

### D7: （已并入 D6）EntryPoint/Sql/Class 不单独复用
- 原「Class 级联失效」策略因 D6 的「非 Method 全删全插」而不再需要：`Class` / `EntryPoint` / `Sql` 每次 reuse 都删除重建，天然无陈旧向量问题。

### D8: reuse 模式仅覆盖 Java 链路，Python/TS 降级为 wipe
- **选择**：本 change 的 codeHash 复用只在 `buildJavaKnowledgeGraph` 落地；`doBuildKnowledgeGraph` 分流到 Python（`buildPythonKnowledgeGraph`）或 TS/JS（`buildCodegraphKnowledgeGraph`）时，若 `buildMode=reuse`，强制降级为 `wipe` 并记日志提示。
- **理由**：Python（原生 AST + callgraph）与 TS/JS（codegraph sidecar）的构建链路、`methodBody` 语义与 Java 完全不同，强行在同一 change 内实现三套 codeHash 会爆炸。Java 大仓（万级方法）才是向量瓶颈的主战场。
- **备选**：三语言同步实现 codeHash（工作量三倍）；静默不一致（用户以为复用实则全删，误导）。

### D5: buildMode 参数透传路径
- **选择**：`KnowledgeGraphController`（新增 `buildMode` 入参）→ `KnowledgeGraphTaskServiceImpl.startTask` / `KgGenerationQueue.enqueue` + `QueueItem`（新增 `buildMode` 字段）→ `KnowledgeGraphBuilder.buildKnowledgeGraph`（新增 `buildMode` 重载）。定时 `KgSchedulerService` 透传默认 `reuse`；远端项目经 `CodeHubFetchService` clone 后复用普通 `projectPath` 触发，无需独立改造。
- **理由**：三条入口最终收敛到 `buildKnowledgeGraph` 与 `enqueue` 两个方法，最小改动面。

## Risks / Trade-offs

- **[reuse 模式边全量重建的 I/O 开销]** → 相比增量，reuse 每次全量重写所有边；但向量/NL 才是真正瓶颈，边 MERGE 的 CPU 开销远小于万级 embedding 调用，可接受。增量模式仍保留给「变更文件少」的场景。
- **[comment 字段当前为 null 导致公式退化]** → 短期无注释敏感性，不影响复用正确性（仅影响注释变更时的重算触发）；公式已预留 comment 位，未来接入 `CommentExtractor` 后自动生效。
- **[孤儿清理误删风险]** → 若某次扫描因解析异常漏了部分文件，其历史节点会因「不在本轮 codeHash 集合」被误删；缓解：codeHash 集合以「成功解析并写入的节点」为准，解析失败文件应记日志告警，且 `wipe` 模式作为彻底兜底可随时重建。
- **[历史节点无 codeHash 的首次构建]** → 首次 `reuse` 构建时所有节点 codeHash 为 null，全量回填并重算向量一次（等价于一次 wipe），属一次性迁移成本。

## 实现审查修复记录（2026-08-20 并行子 agent 审查后修复）

| # | 严重度 | 问题 | 修复 |
|---|---|---|---|
| 1 | CRITICAL | miss 分支 `mergeAll` 只清 `description` 不清 `descriptionEmbedding`/`codeEmbedding`，陈旧向量永不重算 | 新增 `clearEmbeddingsByNodeIds` Cypher，miss 分支 `mergeAll` 后显式清空向量 |
| 2 | HIGH | `computeCodeHash` 用 `String.format("%02x", byte)` 符号扩展，产出非规范 hex | 改用 `HexFormat.of().formatHex(digest)` |
| 3 | HIGH | `deleteOrphansByProjectPathAndNotInNodeIds` 传空 `$nodeIds` 时 `NOT IN []` 恒真 → 全删 | `saveMethodNodesForReuse` 改签名为 `(nodes, projectPath)`，空列表走 `deleteByProjectPath` 全删，非空走差集删孤儿 |
| 4 | MEDIUM | `Service` 节点在 REUSE 清理中被遗漏 | `Neo4jServiceNodeRepository` 新增 `deleteByProjectPath`，`cleanProjectDataForReuse` 调用 |
| 5 | MEDIUM | 0 方法场景 early-return 跳过孤儿清理 | 移除 early-return，空列表 → `deleteByProjectPath` 全删 |
| 6 | MEDIUM | `buildMode=incremental` 走全量 API 静默退化为 WIPE | `buildKnowledgeGraph` 入口对 `INCREMENTAL` 抛异常，强制走增量刷新入口 |

### 遗留已知限制（不修，记录备查）

- **[comment 恒为 null]**：`scanMethodNodes` 未填充 `comment`，codeHash 实际不含注释。design D1 明示「本 change 不打通 comment 扫描，公式预留位」，属独立功能增强。
- **[跨项目入射 CALLS 边未清理]**：`deleteCallRelationsByProjectPath` 只删 caller 在本项目的出边；多项目 + 跨服务链接场景下，其他项目指向本项目的入射 CALLS 边（EXTERNAL_CALL / Feign 桥接）在 REUSE 模式不会清理。WIPE 模式因 `DETACH DELETE Method` 会连带清理。属多项目跨服务链接语义，建议单独 follow-up。

## Migration Plan

1. 部署后 `Neo4jInitializer` 自动创建 `codeHash` 索引（若尚未存在），无需手动脚本。
2. 首次 `reuse` 构建会回填全部历史节点 codeHash 并重算向量一次。
3. 回滚：`wipe` 模式（全量-全删）在任何时刻可重建出与旧版本一致的全新图谱；`incremental` 与 `wipe` 链路未改动，向后兼容。

## Open Questions

- 无。前端「构建模式」下拉框三项文案（增量 / 全量-复用 / 全量-全删）已与用户确认，默认「全量-复用」。
