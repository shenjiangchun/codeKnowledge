# KG 向量生成效率优化：实施任务清单

## 执行规则
- 权威状态源：`openspec/changes/kg-vector-optimization/`
- 风险/闸门：Standard / Medium
- 禁止范围：KnowledgeGraphBuilder、HybridSearchService、向量维度/索引结构、embedding 提供商
- 必须执行的最终验证：`mvn test -pl hisi-dev-tool` 全绿 + 日志 [性能报告] 对比

## 任务

- [ ] T1：批量 Embedding API — `UnifiedEmbeddingService.generateEmbeddings(List<String>)`
  - 对应需求/场景：kg-vector-batch-embedding → Batch embedding returns vectors in input order / Empty input / Backward compatibility
  - 前置依赖：无
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/service/UnifiedEmbeddingService.java` — 新增 `generateEmbeddings(List<String>): List<float[]>`
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/service/EmbeddingService.java` — 新增 `batchGenerateEmbeddings(List<String>): List<float[]>` 委托方法
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/config/EmbeddingModelConfig.java` — 新增 `batchSize` 字段（默认 20）
  - 允许修改：上述 3 个文件的上述方法/字段
  - 禁止修改：现有 `generateEmbedding(String)` 单条路径；其他文件
  - 实施步骤：
    1. EmbeddingModelConfig 加 `private int batchSize = 20;`
    2. UnifiedEmbeddingService 新增方法：构建 `{"input": ["t1", "t2", ...]}` → 解析 `data[i].embedding` → 逐元素维度/NaN 校验 → 返回
    3. 失败处理：整批重试 1 次（复用现有重试逻辑）→ 仍失败拆单条 `generateEmbedding(text)` 降级
    4. EmbeddingService 门面加 `batchGenerateEmbeddings` 委托
  - 失败测试或已批准替代验证：单元测试 mock RestTemplate，验证 3 条输入 → 3 条输出顺序一致
  - 验证命令/动作：
    - `mvn test -pl hisi-dev-tool -Dtest='UnifiedEmbeddingServiceTest'` 通过
    - `mvn test -pl hisi-dev-tool -Dtest='EmbeddingServiceTest'` 通过
  - 预期结果：3 个测试用例通过（正常批量 / 空输入抛异常 / 维度不匹配降级单条）
  - 迁移/回滚：无迁移；配置 `embedding.batch-size: 1` 回退单条
  - 完成定义：`generateEmbeddings` 方法实现 + 单元测试 3 用例全绿 + 现有测试零回归
  - 负责人/冲突说明：无冲突

- [ ] T2：批量 LLM 描述 — `LLMDescriptionService.generateDescriptionsBatch(List<MethodNode>)`
  - 对应需求/场景：kg-vector-batch-description → Batch description returns correct count / Mismatched count triggers fallback / Single method falls back
  - 前置依赖：T1（复用批量模式概念），可并行于 T3
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/service/UnifiedTextService.java` — 新增 `generateDescriptionsBatch(List<MethodInfo>): List<String>` + `estimateTokens(int): int`
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/LLMDescriptionService.java` — 新增 `generateDescriptionsBatch(List<MethodNode>): List<String>` 协调方法
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/config/TextModelConfig.java` — 新增 `jsonOutputStrategy` 字段（默认 "auto"）
  - 允许修改：上述 3 个文件的上述方法/字段
  - 禁止修改：现有 `generateDescriptionWithBody(MethodNode)` 单条路径；其他文件
  - 实施步骤：
    1. TextModelConfig 加 `private String jsonOutputStrategy = "auto";`
    2. 新增 `JsonOutputStrategyResolver`（@PostConstruct 查表 KnownModelCapabilities → 返回 Strategy 实例）—— 支持 json-mode 和 prompt-only 两种策略
    3. 新增 `AdaptiveBatchController`（SlidingWindow 50 + hysteresis 5 + 连续 2 次 ×1.5 上调 + halving 下调）
    4. UnifiedTextService 新增 `generateDescriptionsBatch`：token 预检 → 缩小批次 → 按策略构建请求 → 解析响应（count 校验 → 重试 → 降级）
    5. LLMDescriptionService 新增协调方法，调用上述 + AdaptiveBatchController
    6. prompt 模板：编号格式 `[0] [1] ...` + 强约束禁止错位 + 1 个示例
  - 失败测试或已批准替代验证：单元测试 mock response_format: json_object API 返回 count=20 → 解析成功
  - 验证命令/动作：
    - `mvn test -pl hisi-dev-tool -Dtest='LLMDescriptionServiceTest'` 通过
    - `mvn test -pl hisi-dev-tool -Dtest='AdaptiveBatchControllerTest'` 通过（5 场景对照 `tools/adaptive_batch_sim.py` 基线）
    - `mvn test -pl hisi-dev-tool -Dtest='JsonOutputStrategyResolverTest'` 通过
  - 预期结果：4 个测试类全绿（批量正常 / count 不匹配降级 / 单条委托 / 自适应状态转换 5 场景）
  - 迁移/回滚：配置 `text-model.json-output-strategy: prompt-only` 回退。自适应可单独关闭（配置开关）
  - 完成定义：批量 LLM 方法 + 自适应控制器 + JSON 策略解析器 + 单元测试 4 类全绿 + 现有测试零回归
  - 负责人/冲突说明：无冲突

- [ ] T3：VectorGenerationService 批量改造 + 流水线化
  - 对应需求/场景：kg-vector-batch-embedding + kg-vector-batch-description 的全部场景
  - 前置依赖：T1、T2
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/VectorGenerationService.java` — 重构 `processMethod` 为 `processBatch`，替换 `startVectorGeneration` 中的批次循环
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/vector/VectorWriter.java` — 适配新批量流程（如 `upsertMethod` 被 `processBatch` 调用）
  - 允许修改：上述 2 个文件的流程逻辑
  - 禁止修改：不碰 KnowledgeGraphBuilder；不改变 progressTracker 机制；不改变断点续传 `descriptionEmbedding == null` 逻辑
  - 实施步骤：
    1. VectorGenerationService.startVectorGeneration 中：外层批次循环改为用 AdaptiveBatchController 的动态 batch size
    2. 每批：① 批量 LLM → ② Emb(desc) ∥ Emb(code)（CompletableFuture.allOf）→ ③ @Transactional Neo4j 整批更新 → ④ 进度上报 → ⑤ 每 10 批 adjust()
    3. processSqlNodes 改为调用 `embeddingService.batchGenerateEmbeddings`（自然受益）
    4. processEntryPointDescriptions 改为用批量 LLM（初始 batch=5），由 AdaptiveBatchController 自适应
    5. VectorWriter.upsertMethod 保持不变（单条写入被 @Transactional 批处理方法包裹调用）
  - 失败测试或已批准替代验证：集成测试用测试项目 java-spring-test 跑全量构建
  - 验证命令/动作：
    - `mvn test -pl hisi-dev-tool -Dtest='VectorGenerationServiceTest'` 通过
    - `mvn test -pl hisi-dev-tool -Dtest='VectorWriterTest'` 通过
    - `mvn test -pl hisi-dev-tool` 全绿（零回归）
  - 预期结果：批量流程 3 步串行（desc → emb∥code → neo4j）+ 进度日志正常 + SqlNode 和 EntryPoint 批量日志出现
  - 迁移/回滚：无需迁移；回滚到单条模式通过配置开关
  - 完成定义：批量主流程实现 + SqlNode/EntryPoint 适配 + 所有现有测试零回归
  - 负责人/冲突说明：T3 依赖 T1+T2，需等前两个任务完成后才能集成测试

- [ ] T4：MethodNode.codeHash 字段 + Neo4j 索引（设计预留）
  - 对应需求/场景：kg-vector-code-fingerprint → Code Hash Field on MethodNode / Phase 2 Delta Skip Deferred
  - 前置依赖：无（可并行于 T1-T3）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/MethodNode.java` — 新增 `@Property("codeHash") private String codeHash;`
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/Neo4jInitializer.java`（或用 @Index 注解）— 创建 `codeHash` 索引
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/VectorGenerationService.java` — 处理时计算并写入 codeHash
  - 允许修改：MethodNode 加字段，Neo4jInitializer 建索引
  - 禁止修改：不实现基于 codeHash 的增量跳过逻辑（Phase 2）
  - 实施步骤：
    1. MethodNode 加 `@Property("codeHash") private String codeHash;`
    2. Neo4jInitializer（或 @Index 注解）创建 `idx_methodnode_codehash` 索引
    3. VectorGenerationService.processBatch 中：每方法计算 SHA-256(`className.methodName(signature)\nmethodBody`)，写入 codeHash 再写库
  - 失败测试或已批准替代验证：单元测试验证新 MethodNode 的 codeHash 非空且为 64 位 hex
  - 验证命令/动作：
    - `mvn test -pl hisi-dev-tool -Dtest='MethodNodeTest'` 通过
    - `mvn test -pl hisi-dev-tool -Dtest='KnowledgeGraphBuilderCodegraphDispatchTest'` 通过（验证旧节点 +新字段兼容）
  - 预期结果：新节点 codeHash 非空；旧节点 null 兼容；Phase 1 不跳过任何节点（全量处理 + 写入 hash）
  - 迁移/回滚：旧数据 codeHash null 兼容，无需迁移。移除字段前先清理索引
  - 完成定义：字段 + 索引 + 写入逻辑 + 单元测试通过
  - 负责人/冲突说明：无冲突

- [ ] T5：集成测试 + 性能基线
  - 对应需求/场景：全部 spec 的端到端验证
  - 前置依赖：T1-T4 全部完成
  - 目标文件/符号：测试项目 `test-projects/java-spring-test/`
  - 允许修改：仅 test-projects（不可改主代码）
  - 禁止修改：his-dev-tool 主代码（本任务是验证，不修 bug）
  - 实施步骤：
    1. 对 java-spring-test 项目运行全量 KG 构建
    2. grep 日志：`[性能报告]` 提取 total_time + `[ADAPTIVE]` 提取 batch_size 调整轨迹
    3. 对比优化前基线（从历史日志或二次全量构建单条模式获取）
    4. 验证：`descriptionEmbedding` 字段非 null 覆盖率 100%
    5. 跑增量构建（改 3 个文件后重建）验证断点续传正常
  - 失败测试或已批准替代验证：grep 日志无 ERROR 级别异常
  - 验证命令/动作：
    - `mvn test -pl hisi-dev-tool` 全绿
    - grep `logs/local-model/vector-generation.log` 中的 `[性能报告]` 和 `[ADAPTIVE]` 确认批量优化生效
  - 预期结果：全量构建 total_time < 优化前的 50%；增量构建仅处理变更方法；batch_size 从 20 逐步自适应至 30-50
  - 迁移/回滚：无需
  - 完成定义：测试项目构建成功 + 无 ERROR 日志 + 批量日志确认生效 + 回归测试全绿
  - 负责人/冲突说明：需 T1-T4 全部完成后执行

## 集成顺序
1. T1（批量 Embedding） 和 T4（codeHash 字段）可并行——无文件重叠
2. T2（批量 LLM）在 T1 之后——共享 UnifiedTextService 上下文，可并行于 T4
3. T3（主流程改造）严格依赖 T1 + T2——需要两个批量 API 完成
4. T5（集成测试）严格依赖 T1-T4——需要全部代码就绪

```
T1 ─┬─→ T3 ─→ T5
T2 ─┘
T4 ───────────→ T5
```

## 最终验证
| 命令/动作 | 覆盖范围 | 预期结果 |
|---|---|---|
| `mvn test -pl hisi-dev-tool` | 全部单元测试 + 回归测试 | 零回归，全绿 |
| `mvn test -pl hisi-dev-tool -Dtest='AdaptiveBatchControllerTest'` | 5 种场景自适应 | 对照 sim.py 基线通过 |
| `mvn test -pl hisi-dev-tool -Dtest='UnifiedEmbeddingServiceTest,UnifiedTextServiceTest,LLMDescriptionServiceTest'` | 批量 API 核心逻辑 | 3 类全绿 |
| grep `[性能报告]` logs/local-model/vector-generation.log | 端到端构建时间 | total_time < 优化前的 50% |
| grep `[ADAPTIVE]` logs/local-model/vector-generation.log | 自适应水位线轨迹 | batch 从 20 → 30-50，无异常 halving 死循环 |
