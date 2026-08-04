# KG 向量生成效率优化：需求与代码事实简报

> **定框日期**: 2026-08-03 | **路由**: Standard | **风险**: Medium | **分支**: release_0731

---

## Why

当前 KG 向量生成流水线对 5051 个方法节点逐次进行 LLM 描述生成（`glm-4-flash`）和双向量生成（`descriptionEmbedding` + `codeEmbedding`），每次都是独立的 HTTP API 调用。即使有 6 线程并发和令牌桶限流，大项目构建仍非常慢——每个方法经历 LLM 调用 → Embedding 调用 × 2 → Neo4j 写入的串行链路。业界同类工具（Codebase-Memory、Potpie）已采用批量 API + 代码指纹增量更新 + 流水线化处理，可将全量构建时间降低 50-80%。本变更在不改动图谱语义和检索接口的前提下，优化向量生成层的 API 调用效率和增量更新能力。

## What Changes

- `UnifiedEmbeddingService`：新增 `generateEmbeddings(List<String>)` 批量方法，一次 API 请求发送多个文本，减少 HTTP 往返
- `UnifiedTextService`：新增 `generateDescriptionsBatch(List<MethodInfo>)` 批量方法，将多个方法签名+注释拼成结构化 prompt，一次 LLM 调用返回 JSON 数组描述。**批大小自适应**：按文本总量设定水位线，后台监控总体时间并通过算法调控；生成失败后自动调整参数并协助重试
- `VectorGenerationService`：`processMethod` 改为批量处理 + 流水线化（LLM 完成一个即提交 embedding，不等待整批）
- `MethodNode` (Neo4j 节点)：**设计预留** `codeHash`（SHA-256）属性——本次仅加字段和索引，不实现指纹增量跳过逻辑（二期）
- `VectorGenerationService.startVectorGeneration`：断点续传保持现有 `descriptionEmbedding == null` 逻辑（二期再加入 codeHash 比较）
- **无破坏性变更**：所有新增方法为增量 API，现有单条调用路径保持不变

## Capabilities

### New Capabilities
- `kg-vector-batch-embedding`: `UnifiedEmbeddingService` 支持批量 embedding API 调用，减少 HTTP 往返次数
- `kg-vector-code-fingerprint`: MethodNode 新增 `codeHash` 字段 + 增量更新时基于 SHA-256 指纹跳过未变化方法
- `kg-vector-batch-description`: `UnifiedTextService` 支持批量 LLM 描述生成，一次调用返回多个方法的描述

### Modified Capabilities
<!-- 无现有 spec 需要修改——这是新增能力，不影响已有 spec 的需求语义 -->

## Impact

- 受影响代码：`VectorGenerationService`、`VectorWriter`、`LLMDescriptionService`、`UnifiedEmbeddingService`、`UnifiedTextService`、`EmbeddingService`、`MethodNode`
- 受影响 Neo4j schema：MethodNode 新增 `codeHash` 属性（设计预留，可选，null 兼容旧数据；二期启用）
- 受影响配置：`EmbeddingModelConfig`（如需区分单条/批量 API endpoint）
- 不影响：`HybridSearchService`、`VectorSearchController`、`KnowledgeGraphBuilder`（结构提取层）、前端
- 测试：新增 `UnifiedEmbeddingServiceTest`（批量方法）、`VectorGenerationServiceTest`（增量更新）、`LLMDescriptionServiceTest`（批量描述）

---

## 意图

### 目标与成功标准
- 目标：将 5051 方法节点的全量向量生成时间降低 50-80%（通过批量 API + 流水线化）；二次构建仅处理代码变更的方法（通过代码指纹增量检测）
- 可观察的成功结果：
  1. `mvn test -pl hisi-dev-tool` 全绿，零回归
  2. 批量 embedding API 调用次数从 ~10K 降低到 ~N/batch_size（默认 batch_size=20，即降低 ~95%）
  3. 批量 LLM 描述调用次数从 ~5K 降低到 ~5K/batch_size（降低 ~95%）
  4. 增量构建：修改 10 个文件后重建，仅处理变更方法数（非全量 5051）
  5. 描述质量和向量检索精度不低于当前单条生成基线

### 边界与非目标
- 本次范围：`UnifiedEmbeddingService` 批量方法、`UnifiedTextService` 批量描述方法、`VectorGenerationService` 流水线化 + 指纹增量、`MethodNode.codeHash` 字段
- 非目标：
  - 不修改 `KnowledgeGraphBuilder`（AST 解析层）
  - 不修改 `HybridSearchService` / 检索接口
  - 不更换 Neo4j 或 embedding 模型提供商
  - 不改变向量维度或索引结构
  - 不实现 LLM 描述的跨项目缓存共享
  - ~~不实现方法级代码指纹增量跳过~~ → 设计预留 `codeHash` 字段 + Neo4j 索引，二期实现
- 禁止修改路径：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`、`neo4j/service/HybridSearchService.java`

---

## 代码事实

### 现状摘要
向量生成流水线由 `VectorGenerationService.startVectorGeneration()` 驱动，核心流程：
1. 从 Neo4j 查询所有方法节点（`Neo4jMethodNodeRepository.findByProjectPathWithoutRelationships`）
2. 过滤已处理节点（`descriptionEmbedding == null`）
3. 分批提交到 6 线程池，每批内并发，批间 barrier
4. 每个方法：`LLMDescriptionService.generateDescriptionWithBody()` → `EmbeddingService.generateEmbedding(description)` → `buildCodeText()` → `generateEmbedding(codeText)` → Neo4j 更新

关键依赖：
- `UnifiedEmbeddingService.generateEmbedding(String)` — 每次调用发送单条 `{"input": "text"}` 到 `/embeddings`
- `UnifiedTextService.generateText(String)` — 每次调用发送单条 `{"messages": [{"role": "user", "content": "..."}]}` 到 `/chat/completions`
- 令牌桶（`TokenBucketRateLimiter`）控制 QPS，线程池提供并发度

### 可复用 / 需扩展 / 冲突
#### 可直接复用
- `TokenBucketRateLimiter` 限流机制（批量调用仍需限流）
- `EmbeddingService` 门面层（保留注入点，委托到新增的批量方法）
- `VectorWriter.upsertMethod()` — 增量更新时复用单条写入逻辑
- 断点续传逻辑（`descriptionEmbedding == null` 过滤）→ 扩展为 `(descriptionEmbedding == null || codeHash changed)`

#### 需要扩展
- `UnifiedEmbeddingService`：新增 `generateEmbeddings(List<String>)` 方法，构建 `{"input": ["text1", "text2", ...]}` 请求体
- `UnifiedTextService`：新增 `generateDescriptionsBatch(List<MethodInfo>)` 方法，将多个方法拼成结构化 prompt，返回 `List<String>`
- `LLMDescriptionService`：新增 `generateDescriptionsBatch(List<MethodNode>)` 协调方法
- `EmbeddingService`：新增 `batchGenerateEmbeddings(List<String>)` 真正调用批量 API
- `MethodNode`：新增 `codeHash` 字段及其 Neo4j 属性
- `VectorGenerationService.processMethod` → 重构为 `processBatch(List<MethodNode>)` + 流水线

#### 需求与现状冲突
- 无冲突：所有改动是增量 API 扩展，现有单条调用路径不变

### 挂载点候选
| 优先级 | 路径/符号 | 理由 |
|---|---|---|
| 必选 | `UnifiedEmbeddingService.generateEmbedding(String)` | 唯一 embedding API 入口，批量方法加在此处 |
| 必选 | `UnifiedTextService.generateText(String)` | 唯一 LLM 调用入口，批量描述方法加在此处 |
| 必选 | `VectorGenerationService.processMethod(MethodNode, ...)` | 向量生成核心循环，改为批量+流水线 |
| 必选 | `MethodNode` (Neo4j entity) | 新增 `codeHash` 字段 |
| 备选 | `EmbeddingService.generateEmbedding(String)` | 门面层，可加批量委托 |

### 波及线索
- `VectorWriter.upsertMethod()` — 如果调用 `processMethod` 的重构版，需适配参数签名
- `VectorGenerationService.processSqlNodes()` — 同类分批模式，可后续统一（非本次范围）
- `VectorGenerationService.processEntryPointDescriptions()` — 同类分批模式（非本次范围）
- `Neo4jInitializer` — 如新增索引，需在初始化时创建 `codeHash` 索引
- `IncrementalRefreshService` / `IncrementalRefreshServiceV2` — 增量刷新入口，将受益于指纹检测，但本次不改其逻辑
- 测试：`VectorWriterTest`、`VectorGenerationService` 相关测试需更新

### 证据表
| 类型 | 结论 | 证据 |
|---|---|---|
| 事实 | `UnifiedEmbeddingService.generateEmbedding` 每次只发单条 `"input": text` | `UnifiedEmbeddingService.java:89-90` — `requestBody.put("input", text)` |
| 事实 | OpenAI 兼容的 `/embeddings` 端点支持 `"input": ["t1", "t2", ...]` 数组批量输入 | OpenAI API 文档 + 智谱 embedding-3 兼容 |
| 事实 | `EmbeddingService.batchGenerateEmbeddings` 只是 for 循环，未利用批量 API | `EmbeddingService.java:81-90` |
| 事实 | `VectorGenerationService.processMethod` 先 LLM 再 2×Embedding，串行 | `VectorGenerationService.java:313-353` |
| 事实 | 当前断点续传仅检查 `descriptionEmbedding == null`，不检测方法体变化 | `VectorGenerationService.java:196-199` |
| 事实 | 当前 KG 状态显示 5051 方法节点、5376 调用关系 | `kg_status` 查询结果 |
| 推断 | 批量 embedding 可将 HTTP 往返从 ~10K 降至 ~500（batch=20） | 需验证智谱 API 单次批量上限 |
| 推断 | SHA-256 指纹对比足够快，不影响重建总时间（每个方法 ~1μs） | SHA-256 性能常识 |
| 决策 | 方向 A 为首选，risk signal 为 `none` | Explore 阶段交接 |

---

## 消歧与闸门

### 开放问题清单
| 优先级 | 问题 | 代码事实背景 | 选项与影响 | 建议 | 状态 | 最终决策 |
|---|---|---|---|---|---|---|
| 必选 | 批量 LLM 描述的 batch size 选多大？ | `UnifiedTextService` 当前 `max_tokens` 配置决定一次调用能容纳多少方法描述 | A. 固定 10 个/批 — 安全，但收益有限；B. 固定 20 个/批 — 收益大；C. 动态计算 — 复杂度高；其他：按文本总量自适应 | 建议：B — 20 个/批 | decided | **自适应水位线**：按文本总量设定最佳水位线，确保不超出模型上下文且时间开销平衡。后台进程监控总体时间，通过算法调控上下文大小；生成失败后调整参数并协助失败进程重试。初始默认 20 个/批，运行时自适应 |
| 可选 | 批量描述质量退化时是否自动回退单条？ | 批量生成的描述可能比单条略粗糙（LLM 注意力分散） | A. 自动回退：抽样检测到质量退化时降级为单条；B. 仅告警不降级 | 建议：A | decided | **A. 自动回退**：全量构建时抽样检测（每 500 方法抽 10 做单条对比），质量退化自动降级；增量更新保持单条 |

### 澄清完整性扫描
- 已检查的适用维度：
  - 使用者/权限：无影响——向量生成是管理员触发的后台任务
  - 正常状态变化：批量 API 返回结果顺序与输入一致（`data[i].embedding` 对应 `input[i]`）
  - 失败/重试：现有令牌桶+重试机制已覆盖，批量调用复用同一机制
  - 数据保存/迁移：`codeHash` 为可选字段，旧数据 null 兼容
  - 向后兼容：所有新增方法为增量 API，现有调用路径不变
  - 性能：批量 API 响应时间基于单条+网络开销，10-20 条批量预计从 10×N ms 降至 N+100 ms
  - 回归验证：`mvn test -pl hisi-dev-tool` 全绿
- 由证据解决的缺失事实：批量 embedding API 的智谱兼容性（已确认 OpenAI 兼容）
- 新增开放问题及处理状态：2 个（1 必选 + 1 可选），进入首波澄清
- 明确不适用 / 不在范围的维度：隐私（无 PII 处理）、支付（无）、鉴权（无变更）、公共 API 契约（无变更）
- 结论：无实质阻塞项（必选题仅 1 个），可进入首波澄清

### 风险定级与闸门建议
- 建议车道/风险：**Standard / Medium**
- 命中的风险特征：跨模块/跨文件（触及 6+ 文件）、修改共享基础设施（`UnifiedEmbeddingService`、`UnifiedTextService` 被多个消费者依赖）、新增 Neo4j schema 属性
- 未命中的高风险特征：不涉及鉴权/支付/隐私/数据迁移/核心业务链路/公共 API 契约/破坏性操作
- 不确定点：批量 LLM 描述质量需 A/B 测试验证（已在开放问题清单）
- 闸门建议：Standard 规格闸门 — 1 个必选澄清题 + 1 个可选澄清题，决定后进入 `delivery-plan-tasks` 写 design/tasks
- 可用验证：`mvn test -pl hisi-dev-tool`、`VectorWriterTest`、`VectorGenerationService` 相关测试
- 缺失验证：端到端性能对比（需在 execute 阶段做）

### Explore 交接消费
- [x] `chosen_direction` → 已写入「意图」目标：方向 A — 图谱向量生成效率优化（批量 + 指纹 + 流水线）
- [x] `non_goals` → 已写入「意图」边界：不修改 AST 解析层、检索接口、embedding 提供商、向量维度
- [x] `code_anchors` → 已驱动「代码事实」检查：`VectorGenerationService.processMethod`、`UnifiedEmbeddingService.generateEmbedding`、`EmbeddingService.batchGenerateEmbeddings` 均已引用
- [x] `risk_signal` → `none` 已按代码事实重算为 `medium`：跨文件改动 + 共享基础设施扩展 + Neo4j schema 新增字段
- [x] `unknowns` → 已写入「开放问题清单」：U1（批量 LLM 质量）→ 可选题已列入；U2/U3（Pi Agent 兼容性/Skill 格式）→ 不适用，属方向 C

### 状态源与工件位置
- 后端：OpenSpec change
- 路径：`openspec/changes/kg-vector-optimization/`
- 闸门记录：
  - 规格闸门：已批准 | 批准人：用户 | 批准时间：2026-08-03 | 范围：6 项子能力
  - 实现闸门：已批准 | 批准人：用户 | 批准时间：2026-08-03 | 5 任务（T1-T5），1 警告已记录（lightweight-system-refactoring Javadoc 行级重叠）
  - 接受警告 ID：rw1
