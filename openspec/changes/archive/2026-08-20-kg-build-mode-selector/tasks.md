## 1. codeHash 计算与持久化

- [x] 1.1 在 `MethodNode` 侧新增 `codeHash` 计算工具方法：`SHA-256(className.methodName(signature) + "\n" + comment + "\n" + methodBody)`（复用 `MethodBodyCompressor` 压缩后的 methodBody；comment 为 null 时按空串参与）
- [x] 1.2 在 `KnowledgeGraphBuilder.scanMethodNodes`（含普通方法、构造方法、枚举方法、合成方法）的 builder 中填充 `codeHash`
- [x] 1.3 在 `Neo4jStorageService.saveMethodNodes` 的 nodeMap 中写入 `codeHash`，并更新 `Neo4jMethodNodeRepository.mergeAll` 的 Cypher 增加 `m.codeHash = n.codeHash`
- [x] 1.4 确认 `Neo4jInitializer` 已为 `codeHash` 建索引（若无则补）

## 2. buildMode 参数透传链路

- [x] 2.1 定义 `BuildMode` 枚举（`INCREMENTAL` / `REUSE` / `WIPE`），或在复用层用字符串常量承载
- [x] 2.2 `KnowledgeGraphBuilder.buildKnowledgeGraph` 新增 `buildMode` 重载（保持旧签名委托到默认 `REUSE` 或 `WIPE`，见决策）；`doBuildKnowledgeGraph` 分流处对 Python/TS 项目在 `reuse` 模式下强制降级 `wipe` 并记日志
- [x] 2.3 `KgGenerationQueue.QueueItem` 增加 `buildMode` 字段，`enqueue` / `enqueueBatch` 透传，`processItem` 分发到对应模式
- [x] 2.4 `KnowledgeGraphTaskServiceImpl.startTask` 增加 `buildMode` 入参并透传到 `enqueue`
- [x] 2.5 `KnowledgeGraphController` 的 `startTask`、`startTaskBatch`、`generate` 增加 `buildMode` 入参（默认 `reuse`），解析并下传
- [x] 2.6 `KgSchedulerService` 定时任务透传默认 `buildMode`（默认 `reuse`）
- [x] 2.7 前端构建触发 UI 增加「构建模式」下拉框（增量 / 全量-复用 / 全量-全删，默认全量-复用），请求体追加 `buildMode`

## 3. 全量-复用（REUSE）模式核心逻辑

- [x] 3.1 实现 REUSE 模式的清理：删除该项目下所有边类型（新增 `CALLS` 全删边 Cypher，复用现有 `deleteExtendsRelationsByProjectPath`/`deleteOverrideRelationsByProjectPath`/`deleteProxyRelationsByProjectPath`/`deleteImplementsRelationsByProjectPath`/`deleteExecutesSqlRelationsByProjectPath`），删除所有非 `Method` 节点（EntryPoint/Sql/Class/DataModel/Module/Churn/Domain/Service），仅保留 `Method` 节点
- [x] 3.2 实现 codeHash 命中判定：命中 → `mergeAll` 更新结构字段但不覆盖 `description`/`descriptionEmbedding`/`codeEmbedding`；未命中 → 覆盖并清空三个向量字段
- [x] 3.3 构建末尾实现孤儿清理：`DETACH DELETE` 该项目下 `projectPath` 匹配、但 `nodeId` 不在本轮重建集合内的历史 `Method` 节点（新增 Repository Cypher）
- [x] 3.4 确认 `startVectorGeneration` 断点续传（`descriptionEmbedding == null`）自动只重算未命中 Method，非 Method 节点因全删重建 embedding 为 null 也被自动重算，无需额外失效信号

## 4. WIPE 模式保持现状

- [x] 4.1 确认 `buildMode=WIPE` 走现有 `cleanOldData` 全删全插路径，无 codeHash 复用/保留逻辑

## 5. 测试与回归

- [x] 5.1 单元测试：codeHash 计算（含 comment 参与、comment 为 null 退化）
- [x] 5.2 单元测试：buildMode 参数在 controller → service → queue → builder 的透传，以及非 Java 项目 `reuse` 降级 `wipe`
- [x] 5.3 集成测试：REUSE 模式 Method 命中复用（向量不被覆盖）、未命中重算、孤儿清理、非 Method 节点全删重建
- [x] 5.4 回归：`mvn -pl hisi-dev-tool test` 全绿，增量与 WIPE 链路行为不变
