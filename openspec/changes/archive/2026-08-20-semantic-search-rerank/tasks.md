# 语义检索 rerank：实施任务清单

## 执行规则
- 权威状态源：`openspec/changes/semantic-search-rerank/`（proposal.md + design.md + tasks.md）
- 风险/闸门：Standard / medium；实施门 = 用户「开始实施」go（已在本清单完成后请求）
- 禁止范围：不改服务层 `HybridSearchService.hybridSearch` 方法签名与内部调用方（AgentTools/DirectKgClient/MultiQuerySearcher/KgToolRegistry 等）；不改 KG 生成侧；不改 QueryDecomposer；不改 embedding 模型。
- 必须执行的最终验证：`cd hisi-dev-tool && mvn test` 全绿 + `cd hisi-mcp-server && npx tsc --noEmit` 通过。

## 任务

- [x] 任务 1：MCP `hybrid_search` 切到 v2 端点 + 删除 threshold 死参数
  - 对应需求/场景：MCP 走 v2 端点 / 返回多路召回字段 / MCP 传 searchType
  - 前置依赖：无
  - 目标文件/符号：`hisi-mcp-server/src/tools/vectorTools.ts`
  - 允许修改：`hybridSearch()` 方法的 POST URL（`:102` 改为 `/api/search/semantic/v2`）；删除 `inputSchema` 里的 `threshold` 属性（`:42-45`）；删除 `HybridSearchParams` 接口的 `threshold` 字段（`:66`）及 `body` 里的 `threshold`（`:94`）；更新文件头注释（`:5`）与方法注释（`:84`）。
  - 禁止修改：`language`/`graphDepth`/`limit`/`projectPath(s)` 其他参数；0 结果兜底逻辑（`availableProjects`）。
  - 实施步骤：1) 改 POST URL；2) 删 threshold 三处；3) 更新注释。
  - 失败测试或已批准替代验证：无既有 TS 测试覆盖 vectorTools；采用编译 + 端点断言替代。
  - 验证命令/动作：`cd hisi-mcp-server && npx tsc --noEmit`
  - 预期结果：编译通过，无 threshold 残留（grep `threshold` 在 vectorTools.ts 中为 0 命中）
  - 迁移/回滚：改回 URL 即回滚
  - 完成定义：`grep -n threshold src/tools/vectorTools.ts` 无输出；`grep -n "api/search/semantic/v2" src/tools/vectorTools.ts` 命中 `:102`
  - 负责人/冲突说明：无（仅 MCP 仓库，与其他任务文件不重叠）

- [x] 任务 2：移除两个历史 v1 检索端点 + 前端 `search()` 方法
  - 对应需求/场景：v1 semantic 端点不可用 / v1 vector-search 端点不可用 / v2 端点不受影响
  - 前置依赖：任务 1（顺序敏感：先改 MCP 再删 v1，否则 MCP 打到 404）
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/controller/SemanticSearchController.java`、`VectorSearchController.java`、`hisi-dev-tool-frontend/src/api/vectorSearch.ts`
  - 允许修改：删除 `SemanticSearchController.semanticSearch()` 方法（`:60-176` 整个 @Deprecated 方法，含其 @PostMapping("/semantic")）；删除 `VectorSearchController.search()` 方法（`:63-131` 整个 @Deprecated 方法，含 @PostMapping）；删除前端 `vectorSearchApi.search()`（`:72-75`）及 `@deprecated` 注释。
  - 禁止修改：`semanticSearchV2()`、`searchV2()`、`SearchRequest` DTO、`convertToSemanticResult`、`getSearchHistory`/`getCodeNode`/`getNodeRelations`/`getSearchSuggestions`、`diagnose`。
  - 实施步骤：1) 删 SemanticSearchController v1 方法；2) 删 VectorSearchController v1 方法；3) 删前端 search()；4) 清理因删除而失效的 import（若有）。
  - 失败测试或已批准替代验证：无既有 v1 端点测试；采用编译 + 路由断言替代。
  - 验证命令/动作：`cd hisi-dev-tool && mvn -q -pl . compile`
  - 预期结果：编译通过；`@PostMapping("/semantic")` 与 `VectorSearchController` 的裸 `@PostMapping` 已移除
  - 迁移/回滚：git revert 即回滚
  - 完成定义：`grep -n "@Deprecated" SemanticSearchController.java` 无 semanticSearch 对应；`grep -rn "vectorSearchApi.search(" hisi-dev-tool-frontend/src` 仅剩定义行（或全无）
  - 负责人/冲突说明：无（后端两个 controller + 前端一个 api 文件，与任务 1/3/4 文件不重叠）

- [x] 任务 3：新增 RerankService + 主路径 rerank 挂载
  - 对应需求/场景：召回后 rerank 精排 / 关闭时零回归 / rerank 服务异常降级
  - 前置依赖：任务 4（RerankProperties 先就位）
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/service/RerankService.java`（新建）、`MultiQueryHybridSearchService.java`
  - 允许修改：新建 `RerankService`（注入 `RerankProperties`，用 `proxyConfig.getCurrentRestTemplate()` POST `/rerank`，方法 `rerank(String query, List<MethodNode> candidates)` 返回 `Map<String,Double>`，异常捕获返回空 Map）；`MultiQueryHybridSearchService` 注入 `RerankService` + `RerankProperties`，在主路径 `sorted` 之后、`SearchResult.builder()` 之前插入 rerank 分支（`enabled=false` 直接跳过）。
  - 禁止修改：主路径 RRF 融合逻辑（`:93-156`）、`applyAnnotationBonus`/`normalizeMultiHitScores`/`applyCalleePropagation`；单查询退化路径（`:45-51`）与全部失败路径（`:74-80`）。
  - 实施步骤：1) 新建 RerankService；2) MultiQueryHybridSearchService 注入并挂载 rerank 分支；3) rerank 分写回 similarityScore（rrfScores 不动）。
  - 失败测试或已批准替代验证：先写 `RerankServiceTest`（Mock RestTemplate 验证请求体 + 降级）+ `MultiQueryHybridSearchService` 回归测试（enabled=false 输出不变）。
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -Dtest='RerankServiceTest,MultiQueryHybridSearchServiceTest'`
  - 预期结果：新测试 + 回归测试全绿
  - 迁移/回滚：`enabled=false` 即回滚（零代码回滚）
  - 完成定义：rerank 分支仅主路径生效；enabled=false 时 `multiQuerySearch` 输出与改造前一致；异常时降级不抛
  - 负责人/冲突说明：改 `MultiQueryHybridSearchService.java`，与任务 1/2 文件不重叠；与任务 4 共享 `RerankProperties`（任务 4 先完成）

- [x] 任务 4：新增 RerankProperties 配置类 + application.yml 配置段
  - 对应需求/场景：rerank 外部 API 配置（默认关）/ 显式开启
  - 前置依赖：无
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/config/RerankProperties.java`（新建）、`hisi-dev-tool/src/main/resources/application.yml`
  - 允许修改：新建 `RerankProperties`（`@Component` + `@ConfigurationProperties(prefix="search.rerank")`，字段 `enabled`(默认 false)/`baseUrl`/`model`/`apiKey` 及其 getter/setter）；`application.yml` 的 `search:` 段下新增 `rerank:` 子段（含 `enabled: false` 及 base-url/model/api-key 占位）。
  - 禁止修改：`search.intent-weights`、`search.rrf.*` 已有配置；`embedding:` 段。
  - 实施步骤：1) 新建 RerankProperties；2) application.yml 加 rerank 子段。
  - 失败测试或已批准替代验证：无既有配置测试；采用启动绑定验证替代。
  - 验证命令/动作：`cd hisi-dev-tool && mvn -q -pl . compile`
  - 预期结果：编译通过；`grep -n "rerank" application.yml` 命中且 `enabled: false`
  - 迁移/回滚：删配置段即回滚
  - 完成定义：`RerankProperties` 可被 Spring 绑定；yml 中 `search.rerank.enabled=false` 默认
  - 负责人/冲突说明：与任务 3 共享 RerankProperties（本任务先建类，任务 3 注入）

## 集成顺序
1. 任务 4（配置类）→ 2. 任务 3（RerankService 依赖配置类）→ 3. 任务 1（MCP）→ 4. 任务 2（删 v1，必须在任务 1 之后）
   - 注意：任务 1 必须在任务 2 之前（否则 MCP 打到已删的 v1 端点 404）；任务 4 必须在任务 3 之前（依赖注入）。
   - 任务 1 与任务 3/4 可并行（不同仓库/文件，无共享状态）。

## 最终验证
| 命令/动作 | 覆盖范围 | 预期结果 |
|---|---|---|
| `cd hisi-dev-tool && mvn test` | 后端全量回归（含 RerankService/MultiQueryHybridSearchService/控制器） | 全绿 |
| `cd hisi-mcp-server && npx tsc --noEmit` | MCP TS 编译 | 通过 |
| `grep -rn "api/search/semantic'" hisi-mcp-server/src` | MCP 不再打 v1 | 仅命中 `/semantic/v2` |
| `grep -n "threshold" hisi-mcp-server/src/tools/vectorTools.ts` | threshold 已删 | 无输出 |
