# 语义检索：MCP 端点修复 + 历史接口移除 + rerank 配置化

## Why

当前语义检索存在两个问题：① MCP `hybrid_search` 工具实际打到已 `@Deprecated` 的旧版 `/api/search/semantic`（v1），未享受 v2 的多路召回 + 加权 RRF，检索精度被白白限制；② 历史检索端点（`/api/search/semantic` v1、`/api/vector-search` v1）仍暴露在外，形成冗余的对外 API 面。同时，检索精度尚缺一个召回后的 rerank 精排阶段（对标 Sourcegraph/CodeBERT 的业内做法）。

## What Changes

- **BREAKING**: 移除 `POST /api/search/semantic`（v1，`SemanticSearchController.semanticSearch`）与 `POST /api/vector-search`（v1，`VectorSearchController.search`）两个 `@Deprecated` 端点。
- **BREAKING**: 前端 `vectorSearchApi.search()`（`vectorSearch.ts:74`）随 v1 端点一并移除；`searchV2()` 保留。
- **修复**: MCP `hybrid_search` 工具（`vectorTools.ts:102`）从 `POST /api/search/semantic` 改为 `POST /api/search/semantic/v2`，享受多路召回 + 加权 RRF，返回新增 `subQueries` / `rrfScores` 字段。
- **新增**: rerank 精排能力 —— 在 `MultiQueryHybridSearchService.multiQuerySearch()` 的 RRF 融合之后、返回之前，对 top-K 候选做外部 rerank API 精排重排序。
- **配置化**: `application.yml` 的 `search:` 段新增 rerank 配置项（`enabled` / `base-url` / `model` / `api-key`），默认 `enabled: false`；关闭时走历史逻辑（零回归），开启时走 rerank。

## Capabilities

### New Capabilities
- `rerank`: 召回后精排能力 —— 外部 OpenAI 兼容 `/rerank` 端点配置（base-url/model/api-key）、开关（默认关）、在 multiQuerySearch RRF 融合后对 top-K 重排。

### Modified Capabilities
- `semantic-search-type`: 修正「MCP 检索工具适配」Requirement —— hybrid_search 工具从旧 v1 端点切到 v2 端点；移除历史 v1 端点（breaking）。

## Impact

- **后端**：
  - `SemanticSearchController.java`：删除 `semanticSearch()`（v1，`:61`），保留 `semanticSearchV2()`。
  - `VectorSearchController.java`：删除 `search()`（v1，`:64`），保留 `searchV2()`。
  - `MultiQueryHybridSearchService.java`：新增 rerank 挂载点（RRF 融合后）。
  - 新增 rerank 配置属性类（挂 `search:` 段）+ rerank HTTP 调用（复用 `UnifiedEmbeddingService` 的 OpenAI 兼容客户端模式）。
  - `application.yml`：`search:` 段新增 rerank 配置。
  - 服务层 `HybridSearchService.hybridSearch()` 与 `MultiQueryHybridSearchService.multiQuerySearch()` 的**方法签名与内部逻辑不变**（仅 multiQuerySearch 后置增加可开关的 rerank 步骤）；`AgentTools`/`KgSearchNode`/`DirectKgClient`/`MultiQuerySearcher`/`KgToolRegistry` 等 10+ 处内部调用方**不受影响**。
- **前端**：`vectorSearch.ts` 删除 `search()`，保留 `searchV2()`；`SemanticSearchPanel.vue` 已用 `searchV2()`，无改动。
- **MCP**：`hisi-mcp-server/src/tools/vectorTools.ts` 端点 URL 由 `/api/search/semantic` 改为 `/api/search/semantic/v2`。
- **依赖**：无新增第三方库（rerank 复用现有 HTTP 客户端能力）。

---

# 语义检索：需求与代码事实简报

## 意图

### 目标与成功标准
- 目标：让 MCP `hybrid_search` 真正走 v2 多路召回 + 加权 RRF；移除冗余历史检索端点；新增可配置、默认关闭的 rerank 精排能力。
- 可观察的成功结果：
  1. MCP `hybrid_search` 返回结果中出现 `subQueries` / `rrfScores` 字段（证明走 v2）。
  2. `POST /api/search/semantic` 与 `POST /api/vector-search` 返回 404（端点已移除）。
  3. `rerank.enabled=false` 时，`multiQuerySearch` 输出与现状逐位一致（零回归）。
  4. `rerank.enabled=true` 时，返回结果经 rerank 重排（顺序由 rerank 分决定）。
  5. `mvn test` 全绿。

### 边界与非目标
- 本次范围：MCP 端点修复 + 历史 v1 端点移除 + rerank 配置化（外部 API，开关默认关）。
- 非目标：不换 embedding 模型（P1-1 另议）；不建 CodeSearchNet 评测集（P0-2 另议）；不改 QueryDecomposer 术语桥接（P1-2 另议）；不动服务层 `hybridSearch` 方法签名与 10+ 处内部调用方。
- 禁止修改路径：KG 生成侧代码（`knowledgegraph/`）；`HybridSearchService` 的检索核心逻辑；`Neo4j*Repository`。

## 代码事实

### 现状摘要
语义检索有两条控制器 API 面：`SemanticSearchController`（`/api/search/*`）与 `VectorSearchController`（`/api/vector-search/*`），各自有 v1（`@Deprecated`）与 v2 两个版本。v2 走 `MultiQueryHybridSearchService.multiQuerySearch()`（多路召回 + 加权 RRF）；v1 走 `HybridSearchService.hybridSearch()`（单查询向量+图 RRF）。MCP `hybrid_search` 工具当前错配到 v1。

### 可复用 / 需扩展 / 冲突
#### 可直接复用
- `MultiQueryHybridSearchService.multiQuerySearch()`（`:38`）作为 rerank 挂载点，其 RRF 融合结果直接可用。
- `UnifiedEmbeddingService` 的 OpenAI 兼容 HTTP 调用模式（`/embeddings` 批处理 + 重试 + 归一化），rerank 外部 API 调用可套用同一模式。
- `application.yml` 现有 `search:` 配置段（`:279`）与 `SearchIntentProperties` 配置绑定模式。

#### 需要扩展
- `MultiQueryHybridSearchService`：新增「RRF 融合后、返回前」的 rerank 步骤（可开关）。
- `application.yml` + 新增配置属性类：`search.rerank.enabled/base-url/model/api-key`。

#### 需求与现状冲突
- 移除 v1 端点与「MCP 当前依赖 v1」冲突 —— 必须先改 MCP 到 v2，再删 v1（顺序敏感）。

### 挂载点候选
| 优先级 | 路径/符号 | 理由 |
|---|---|---|
| 必选 | `MultiQueryHybridSearchService.multiQuerySearch()` | rerank 唯一正确挂载点（RRF 之后、返回之前） |
| 必选 | `hisi-mcp-server/src/tools/vectorTools.ts:102` | MCP 端点修复点 |
| 必选 | `SemanticSearchController.semanticSearch()` / `VectorSearchController.search()` | 待删除的历史 v1 端点 |
| 备选 | `UnifiedEmbeddingService` | rerank HTTP 调用可参考/复用其客户端模式 |

### 波及线索
- 删除 `VectorSearchController.search()` 后，`SearchRequest` DTO（`:301`）仍被 `searchV2()` 使用，**不能删**。
- `CorsConfig.java:36` 仍引用 `/api/vector-search/**`，端点删除后该 CORS 白名单条目需保留（`/v2` 仍在此前缀下）。
- 前端 `vectorSearch.ts` 删除 `search()` 后，需确认无其他组件引用 `vectorSearchApi.search`。
- rerank 开关关闭时不得改变 `multiQuerySearch` 现有排序/返回值，需回归测试锁定。

### 证据表
| 类型 | 结论 | 证据 |
|---|---|---|
| 事实 | MCP `hybrid_search` 打到 v1 `/api/search/semantic` | `vectorTools.ts:102` |
| 事实 | v1 `/api/search/semantic` 已 `@Deprecated` | `SemanticSearchController.java:60-61` |
| 事实 | v2 `/api/search/semantic/v2` 走 multiQuerySearch | `SemanticSearchController.java:183-238` |
| 事实 | v1 `/api/vector-search` 已 `@Deprecated`，前端 `search()` 已无人调用 | `VectorSearchController.java:63-64`；`vectorSearch.ts:72-74` |
| 事实 | 前端实际用 `searchV2()` | `SemanticSearchPanel.vue:244` |
| 事实 | 服务层 `hybridSearch` 被 10+ 处内部调用，与 controller 移除无关 | `AgentTools.java:80`、`KgSearchNode.java:299`、`DirectKgClient.java:83`、`MultiQuerySearcher.java:87` 等 |
| 事实 | `search:` 配置段已存在，含 rrf 配置 | `application.yml:279-306` |
| 推断 | rerank 复用 OpenAI 兼容 HTTP 模式可行 | `UnifiedEmbeddingService` 现有实现（未经实测确认 rerank 端点细节） |
| 决策 | rerank 采用外部 Rerank API，开关默认关 | 「开放问题清单」已决 |

## 消歧与闸门

### 开放问题清单
| 优先级 | 问题 | 代码事实背景 | 选项与影响（摘要） | 建议 | 状态 | 最终决策 |
|---|---|---|---|---|---|---|
| 必选 | rerank 用什么实现 | multiQuerySearch 无 rerank 阶段 | A 外部 Rerank API / B 复用 LLM / C 本地交叉编码器 | A | decided | A 外部 Rerank API，开关默认关 |
| 必选 | MCP `threshold` 死参数处置 | v1/v2 均不读 threshold，MCP 却暴露 | A 删除 / B 保留 / C 给 v2 补能力 | A | decided | A 删除（本次不新增 threshold 能力） |
| 必选 | MCP subQueries/rrfScores 透传 | v2 新增字段，MCP 未解构 | A 原样透传 / B 截掉 | A | decided | A 原样透传 |
| 必选 | rerank documents 文本 | MethodNode 有 description/methodBody 等字段 | A description / B methodBody / C 拼接 | A | decided | A description，null 降级签名 |
| 必选 | rerank 重排后分数处理 | rrfScores 与 similarityScore 并存 | A 更新 similarityScore、留 rrfScores / B 全不动 / C 全覆盖 | A | decided | A 重排+更新 similarityScore=rerank 分 |
| 必选 | rerank 作用范围 | multiQuerySearch 有 3 个返回路径 | A 只主路径 / B 三路径都 | A | decided | A 只主路径 |
| 必选 | rerank 配置属性组织 | 已有 SearchIntentProperties(prefix=search) | A 独立 RerankProperties / B 塞进 SearchIntentProperties | A | decided | A 独立 RerankProperties(prefix=search.rerank) |

> 附带发现：`views/search/SemanticSearchView.vue` 与 `api/search.ts` 已不存在于磁盘（views/api 目录均无、router 零引用、全仓零 import），CLAUDE.md 相关描述为过时文档。本次代码层面无需删这两个文件（无物可删）。

### 澄清完整性扫描
- 已检查的适用维度：端点调用面、前端调用方、服务层调用方、配置结构、能力/规格复用。
- 由证据解决的缺失事实：历史接口谁在用（已 grep 全仓）、前端用哪个 API（`searchV2`）、服务层调用方是否受影响（10+ 处不受影响）。
- 新增开放问题及处理状态：rerank 实现方式 → 已决（A）。
- 明确不适用 / 不在范围的维度：embedding 模型选型、评测集建设、QueryDecomposer 术语桥接（均另议）。
- 结论：无实质阻塞项。

### 风险定级与闸门建议
- 建议车道/风险：Standard / medium。
- 命中的风险特征：公开 API 变更（移除公开 REST 端点，breaking）。
- 未命中的高风险特征：无 auth/payment/privacy/migration/并发/持久化格式变更；影响面已完全界定且全部在同一仓库内可统一改。
- 不确定点：rerank 外部端点具体响应格式需在实施时验证（备选 rerank 模型的可用性）。
- 闸门建议：规格闸门（单次范围批准）。
- 可用验证：`mvn test` 回归；MCP 工具返回字段断言；端点 404 断言。
- 缺失验证：rerank 开启时的精度提升需评测集量化（P0-2，本次范围外）。

### Explore 交接消费
- [x] `chosen_direction` → 已写入「意图」（MCP 端点修复 + 移除历史接口 + rerank 配置化）
- [x] `non_goals` → 已写入「意图」边界（不换 embedding、不建评测集、不改 QueryDecomposer）
- [x] `code_anchors` → 已驱动「挂载点候选」检查（`vectorTools.ts:102`、`multiQuerySearch`、v1 端点均引用）
- [x] `risk_signal` → 仅作线索；「风险定级」已按代码事实重算（public API breaking，但影响面可控 → medium）
- [x] `unknowns` → 已写入（rerank 端点响应格式待实施验证）

落点摘要：意图=MCP端点修复+历史接口移除+rerank配置化；挂载=multiQuerySearch+vectorTools.ts；Risk=medium；开放问题=rerank实现（已决 A）。

### 状态源与工件位置
- 后端：OpenSpec change
- 路径：`openspec/changes/semantic-search-rerank/`
- 闸门记录：规格批准状态=待批准 / 批准人=待填 / 附加约束=rerank 开关默认关闭
