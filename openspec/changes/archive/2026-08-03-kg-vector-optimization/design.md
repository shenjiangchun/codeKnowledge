# KG 向量生成效率优化：技术实施计划

> **比例深度**: Medium — 单模块改动，无红线面，紧凑版（决策记录 + 集成点 + 验证矩阵）

## 已批准目标与约束
- 目标：将 5051 方法节点的全量向量生成时间降低 50-80%；二次构建仅处理变更方法
- 非目标：不修改 KnowledgeGraphBuilder / HybridSearchService / 检索接口；不更换 Neo4j 或 embedding 提供商；不改变向量维度或索引结构；不实现方法级代码指纹增量跳过逻辑（二期）
- 风险/闸门：Standard / Medium — 规格闸门已通过（2026-08-03），18 个设计决策全部 grill 通过

## 已刷新代码事实
| 结论 | 证据 | 新鲜度 |
|------|------|--------|
| `UnifiedEmbeddingService.generateEmbedding` 每次发单条 `{"input": "text"}` | `UnifiedEmbeddingService.java:89-90` | ✅ HEAD |
| `EmbeddingService.batchGenerateEmbeddings` 只是 for 循环，未利用批量 API | `EmbeddingService.java:81-90` | ✅ HEAD |
| `VectorGenerationService.processMethod` 先 LLM 再 2×Embedding 串行 | `VectorGenerationService.java:313-353` | ✅ HEAD |
| `UnifiedTextService.disableThinking()` 已有 model-aware 模式 | `UnifiedTextService.java:628-638` | ✅ HEAD |
| 断点续传仅检查 `descriptionEmbedding == null` | `VectorGenerationService.java:196-199` | ✅ HEAD |
| 令牌桶：text-model qps=3 burst=6，embedding qps=5 burst=10 | `TextModelConfig.java:46-49`, `EmbeddingModelConfig.java:40-43` | ✅ HEAD |
| glm-4-flash 实测支持 `response_format: {"type": "json_object"}` | `tools/probe_zhipu_json_mode.py` 运行结果 | ✅ 2026-08-03 |
| 自适应水位线算法经 Chiron v3 模拟验证（5 场景全通过） | `tools/adaptive_batch_sim.py` 运行结果 | ✅ 2026-08-03 |

## 技术决策清单
| ID | 待决事项 | 决策归属 | 实质影响 | 选项与建议 | 状态 | 最终结论 |
|---|---|---|---|---|---|---|
| D1 | 批量 Embedding 失败处理 | Agent | 错误恢复策略 | 整批重试 1 次 → 仍失败拆单条降级 | decided | 整批重试→降级；维度/NaN 校验在 extractEmbedding |
| D2 | Neo4j 写入模式 | Agent | 事务粒度 | 逐条 UPDATE（非瓶颈，事务安全） | decided | 逐条 UPDATE；批次 Neo4j 写入用 @Transactional 原子包裹 |
| D3 | batch size 配置方式 | Agent | 运维灵活性 | `@Value("${embedding.batch-size:20}")` | decided | 可配置，默认 20 |
| D4 | 自适应水位线架构 | Agent | 复杂度 | 内联 SlidingWindowMetrics + 每 10 批评估 + token 估算前置 | decided | Chiron v3: hysteresis 5 + 连续 2 次 + ×1.5 上调 + halving 下调 |
| D5 | JSON 输出策略 | Agent | 解析可靠性 | 启动时查表 KnownModelCapabilities，glm-4-flash=json_object | decided | JsonOutputStrategyResolver + KNOWN 表 + prompt-only 兜底 |
| D6 | 质量回退 | Agent | 性能开销 | 默认关闭 (`quality-check: false`)，仅 count 校验 | decided | 质量回退默认关闭；count 校验 + 编号格式保证正确性 |
| D7 | 同批次并行 | Agent | 微优化 | Emb(desc) ∥ Emb(code)，不跨批次 | decided | CompletableFuture.allOf 同批次内并行 |
| D8 | EntryPoint 批量初始值 | Agent | 保守起点 | initial=5（上下文长），可自适应至 20 | decided | EntryPoint batch start=5 |
| D9 | MethodNode.codeHash | Agent | 设计预留 | 仅创建字段 + Neo4j 索引；跳过逻辑二期 | decided | 字段加在 MethodNode + Neo4jInitializer 建索引 |

## 方案比较（仅 D4 — 唯一有实质替代方案差异的决策）

### 方案 A：Chiron-style 双信号反压（选定）
- 信号：错误率 + 单方法延迟；上调 EWMA 比例缩放，下调 halving
- 收益：模拟验证 5 场景全通过，调整次数 17-46（相比 v2 的 99-148 大幅减少）
- 成本：新增 SlidingWindow + BatchController ~150 行
- 可逆性：`@Value` 配置可完全关闭自适应（`adaptive.enabled: false`）
- 选择理由：业界 2025 最佳实践，模拟验证充分

### 方案 B：简单固定 + 手动调参
- 不做自适应，运维手动调 `embedding.batch-size` 配置
- 收益：零复杂度
- 成本：每次模型/网络环境变化需人工调参
- 选择理由：**不选**——自适应是本次核心价值点之一，且 fallback 到方案 B 只需一个配置开关

## 集成方式与数据流/控制流

```
startVectorGeneration()
  ├─ 查询 MethodNode（descriptionEmbedding==null 的）
  ├─ 外层批次循环（每批 batchSize 个，自适应调整）
  │   ├─ ① 批量 LLM: generateDescriptionsBatch(batch)
  │   │   ├─ token 预检 → 超限则缩小 batch
  │   │   ├─ JsonOutputStrategy 构建请求
  │   │   └─ 解析 LLM 响应 → 校验 count
  │   ├─ ② 并行 Embedding: allOf(Emb(descs), Emb(codes))
  │   ├─ ③ @Transactional Neo4j 原子写入整批
  │   ├─ ④ 进度上报
  │   └─ ⑤ 每 10 批→AdaptiveBatchController.adjust()
  ├─ SqlNode（自然受益于 batch generateEmbeddings）
  └─ EntryPoint（initial=5，自适应的 generateDescriptionsBatch）
```

## 接口与状态模型

### 新增方法签名

```java
// UnifiedEmbeddingService
public List<float[]> generateEmbeddings(List<String> texts);

// UnifiedTextService  
public List<String> generateDescriptionsBatch(List<MethodInfo> methods);
private int estimateTokens(int methodCount);

// LLMDescriptionService
public List<String> generateDescriptionsBatch(List<MethodNode> nodes);

// EmbeddingService（门面）
public List<float[]> batchGenerateEmbeddings(List<String> texts);

// AdaptiveBatchController（新增类）
public record BatchMetrics(long latencyMs, boolean error, int tokens, int batchSize);
public int preflightCheck(int desiredSize);
public void recordBatch(BatchMetrics m);
public boolean shouldAdjust();
public int adjust();
public int getEffectiveBatchSize();
```

### MethodNode 新增字段（设计预留）
```java
@Node
public class MethodNode {
    // ... existing fields ...
    @Property(name = "codeHash")
    private String codeHash; // SHA-256, 二期启用
}
```

### TextModelConfig 新增字段
```java
private String jsonOutputStrategy = "auto"; // auto | json-mode | prompt-only
```

### EmbeddingModelConfig 新增字段
```java
private int batchSize = 20; // 批量 embedding 每批方法数
```

## 失败处理与可观测性

| 失败场景 | 处理 | 日志关键字 |
|---------|------|-----------|
| 批量 LLM count 不匹配 | 错误反馈重试 1 次 → 降级单条 | `[BATCH-LLM] count mismatch` |
| 批量 Embedding 维度/NAN | 整批重试 → 拆单条降级 | `[BATCH-EMB] validation failed` |
| 令牌桶超时 (120s) | 现有机制抛出 RuntimeException | `[Embedding/TextModel] 获取令牌超时` |
| 429 限流 | 现有指数退避重试 + 令牌桶压制 | 现有日志 |
| 自适应状态变化 | 每 10 批打印 batch_size + 原因 | `[ADAPTIVE] batch: 20→25 reason: upscale x1.5` |
| 批次 Neo4j 写入失败 | @Transactional 回滚整批 → 下批重做 | `[BATCH-NEO4J] rollback` |

## 兼容、迁移与回滚

- **兼容性**：所有新增方法为增量 API，现有单条路径 `generateEmbedding(String)` / `generateText(String)` 不变
- **迁移**：无需数据迁移。`codeHash` 字段 optional，旧数据 null 兼容
- **回滚**：配置 `embedding.batch-size: 1` + `text-model.json-output-strategy: prompt-only` 即可回退到单条模式。自适应可单独关闭 `adaptive.enabled: false`
- **Neo4j 索引**：`Neo4jInitializer` 启动时建 `codeHash` 索引，幂等无副作用

## 安全与性能

- 安全：无鉴权/隐私/支付变更。API Key 仍从配置读取，不硬编码
- 性能预期：
  - 批量 Embedding: ~10K 次单条调用 → ~250 次批量调用（降低 95%）
  - 批量 LLM: ~5K 次单条调用 → ~250 次批量调用（降低 95%）
  - 同批次并行: 每批节省 ~200ms（一次网络往返）
  - 总构建时间预期：从 N×3 API 调用/方法 → N/batchSize×3 API 调用/方法
- 令牌桶不变（qps=3/5），批量模式下令牌消耗自然减少 95%，桶利用率更高

## 验证策略
| 层 | 工具 | 命令 |
|---|---|---|
| 单元测试 | JUnit 5 + Mockito | `mvn test -pl hisi-dev-tool -Dtest='*EmbeddingService*,*UnifiedEmbeddingService*,*VectorGenerationService*,*AdaptiveBatch*'` |
| 回归测试 | Maven Surefire | `mvn test -pl hisi-dev-tool` 全绿 |
| 集成验证 | 测试项目 java-spring-test | 全量构建 + 增量构建，日志对比 batch_size 调整轨迹 |
| 性能基准 | 日志分析 | grep `[性能报告]` 对比优化前后 total_time |

## 需求追溯
| 需求/场景 | 设计要素 | 任务 | 验证 |
|---|---|---|---|
| Batch Embedding API → returns vectors in input order | UnifiedEmbeddingService.generateEmbeddings(List) | T1 | 单元测试验证 result[i] 对应 input[i] |
| Batch Embedding → empty input | 参数校验抛 IllegalArgumentException | T1 | 单元测试 |
| Backward Compatibility → single call path unchanged | 保留 generateEmbedding(String) 不变 | T1 | 回归测试 |
| Code Hash Field → null compatible | Optional @Property | T4 | 单元测试 |
| Batch Description → returns correct count | LLM 响应 count 校验 + 重试 | T2 | 单元测试 mock LLM |
| Batch Description → mismatched count fallback | 降级单条 generateText | T2 | 单元测试 |
| Batch Description → single method fallback | 委托 generateText(String) | T2 | 单元测试 |
| MethodNode codeHash → new node gets hash | SHA-256 计算 | T4 | 单元测试 |
| MethodNode codeHash → Phase 1 does NOT skip | 不实现跳过逻辑 | T4 | 集成测试确认全量仍处理 |

## 已知风险与非目标
- 代码指纹增量跳过推迟到二期，当前增量仍基于 git diff 文件级 + descriptionEmbedding null 断点
- Qwen3-embedding-8b 的批量 API 行为未经实测（仅 glm-4-flash 实测），KNOWN 表按文档标为 JSON_MODE
- 自适应水位线算法在极端网络抖动下可能过度 halving → 已通过 hysteresis 5 周期缓解
