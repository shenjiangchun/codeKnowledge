# 多切面分析平台（架构 Copilot）：需求与代码事实简报

## 意图

### 目标与成功标准
- 目标：将 Hisi Dev Tool 从"代码图谱可视化工具"升级为"架构 Copilot"——为架构师提供多切面的架构分析能力，支持诊断（架构仪表盘、DSM 矩阵、热点分析、领域/DDD 边界检测、跨服务拓扑）→ 影响分析（爆炸半径）→ 建议（生成中心：测试建议 + 重构建议）的闭环
- 可观察的成功结果：
  1. 架构师打开仪表盘首页，5 秒内看到架构健康摘要 + Top 3 风险项
  2. DSM 矩阵展示模块间依赖关系，循环依赖和分层违规高亮
  3. 热点 Treemap 展示文件级风险排名（复杂度 × 变更频率 × 耦合度）
  4. 领域切面自动检测领域边界，置信度 > 0.8 的领域用 LLM 命名
  5. 爆炸半径一键查询：给定方法 → 展示下游影响面 + 上游入口点 + 跨服务波及
  6. 生成中心基于 Claude Sonnet 4 + Spring AI ChatClient 生成测试/重构建议

### 边界与非目标
- 本次范围：
  - 数据模型扩展（MethodNode 加 5 个新属性、新增 4 种 Neo4j 节点类型、5 种关系类型）
  - 聚合管道 6 Stage（ModuleStats → DSM → Hotspot → Churn → Community → DomainName）
  - 6 个新 REST 端点 + 6 个新 MCP 工具
  - 前端 Tab 布局框架 + 架构仪表盘首页 + DSM/热点/领域/跨服务切面 + 生成中心面板
  - 爆炸半径端点 + MCP 工具
  - 分层迁移 API（历史项目不强制全量重建）
  - 风险评分引擎（分段映射归一化 + 硬编码业界推荐权重）
  - Louvain 社区检测（Java 自实现，不依赖 GDS 插件）
- 非目标：
  - CI/CD 集成 / PR 自动评论（现有基础设施不支持）
  - 测试用例的完整代码生成（只生成场景描述，不生成 .java 文件）
  - 实时文件监控（WatchService）
  - 多用户协作 / 权限系统
- 禁止修改路径：
  - `AnthropicHttpClient.java`（已标记 @Deprecated，不应扩展）
  - `ZhipuService.java`（已标记 @Deprecated，不应扩展）
  - 现有 15 个 MCP 工具的接口签名
  - Python KG 构建管道的核心逻辑（只在其末尾注入聚合回调）

## 代码事实

### 现状摘要
项目已有 Neo4j 知识图谱（7 种节点类型、12 种边类型），存储方法级调用链。前端有 FlowDag.vue（SVG + dagre DAG 渲染）和 ChainChart.vue（4 种视图模式）。LLM 管道有三条：UnifiedTextService（GLM-4-Flash 批量方法描述生成）、Spring AI ChatClient + AnthropicChatModel（Claude Sonnet 4 Agent 推理）、ApmClaudeLlmClient（Claude Opus 4 APM 诊断）。KgSchedulerService 支持 cron 定时调度全量/增量构建。POST /refresh 触发 V2 增量构建（基于 JGIT 差异检测）。

### 可复用 / 需扩展 / 冲突

#### 可直接复用
- `KnowledgeGraphBuilder.generationSemaphore`（1 permit，公平）— 聚合 Stage 在已有信号量内运行
- `Neo4jMethodNodeRepository` — 40+ 个现有 Cypher 查询继续工作，新增的批量更新查询追加
- `UnifiedTextService` + `LLMDescriptionService` — 不用于生成中心；UnifiedTextService 已正确用于方法描述生成
- `Spring AI ChatClient`（`agentChatClient` bean）— 生成中心的核心 LLM 管道
- `AgentTools`（10 个 `@Tool` 方法）— 生成中心的 Agent 扩展基础
- `AgentTypeRegistry`（6 个 agent type）— 新增 "test-gen" agent type 的模式
- `GitStatusServiceJgit` — ChurnNode 数据源
- `KgSchedulerService` — 定时调度可触发聚合
- `KnowledgeGraphTaskService` — 构建完成回调的注入点
- 前端 `KnowledgeGraphView.vue` 的 Tab 布局模式 — 新增切面的容器

#### 需要扩展
| 资源 | 扩展内容 |
|------|---------|
| `neo4j/model/MethodNode.java` | 加 `packageName`, `inDegree`, `outDegree`, `communityId`, `riskScore` |
| `Neo4jMethodNodeRepository.mergeAll` | SET 子句加 `m.packageName = n.packageName` |
| `Neo4jStorageService.saveMethodNodes` | map.put("packageName", …) |
| `Neo4jInitializer` | 加 packageName 索引 + 新节点约束 |
| `KnowledgeGraphBuilder` | 5 个注入点（Java/Python/TS full + Java/Python incremental）各 1 行 |
| `Neo4jStorageService.cleanProjectData` | 加 ChurnNode/ModuleNode/DomainNode/AggregationCheckpoint 清理 |
| `KnowledgeGraphController` | 3 个已有响应方法 + 6 个新聚合端点 |
| `KnowledgeGraphV2Controller` | 6 个新 V2 委托方法 |
| `knowledgeGraphTools.ts`（MCP） | 6 个新工具定义 + handler 分支 |
| `knowledgeGraph.ts`（前端 API） | MethodNode 接口 + 6 个新 API 函数 + 6 个新类型 |
| `AgentTools` | 新增 4 个 @Tool（getBlastRadius, getHotspotSummary, getDsmViolations, getDomainBoundaries） |
| `AgentTypeRegistry` | 新增 "test-gen" / "refactor-suggest" agent type |

#### 需求与现状冲突
- **MethodNode 的 `mergeAll` 包含所有属性 → 全量重建时会覆盖聚合字段**：解决：`packageName` 走 `mergeAll`（构建时写入），`inDegree/outDegree/communityId/riskScore` 走独立 `batchUpdate*` 查询（聚合时写入）
- **MethodNode 约 8 个显式 RETURN 列查询 → 新属性不可见**：不修正——新属性不需要在这些查询中出现（调用链/搜索/入口点查询不需要返回聚合字段）
- **GDS `callGraph` 投影的 `nodeProperties` 不含新字段**：更新 `01_project_graph.cypher` 加 `packageName` 和 `complexity`

### 挂载点候选

| 优先级 | 路径/符号 | 理由 |
|--------|----------|------|
| 必选 | `KnowledgeGraphBuilder.java:606` | Java 全量构建后注入聚合回调 |
| 必选 | `KnowledgeGraphBuilder.java:258` | Python 全量构建后注入聚合回调 |
| 必选 | `IncrementalKnowledgeGraphBuilder.java:520` | Java 增量构建后注入聚合回调 |
| 必选 | `IncrementalKnowledgeGraphBuilder.java:718` | Python 增量构建后注入聚合回调 |
| 必选 | `Neo4jMethodNodeRepository.mergeAll` (line ~2210) | Cypher SET 子句加 packageName |
| 必选 | `KnowledgeGraphController.java` | 6 个新聚合端点 |
| 必选 | `hisi-mcp-server/src/tools/knowledgeGraphTools.ts` | 6 个新 MCP 工具 |
| 必选 | `agentChatClient` bean (Spring AI) | 生成中心 LLM 管道 |
| 备选 | `KnowledgeGraphBuilder.java:323` | TS/JS 全量构建后注入（TS/JS KG 当前无 saveGenerationLog） |

### 波及线索

| 波级 | 范围 | 波及项 |
|------|------|--------|
| 直接 | 数据模型 | MethodNode 属性扩展 → 约 25 个 `RETURN m` Cypher 自动兼容，约 8 个显式 RETURN 列查询需评估但不修正 |
| 直接 | 序列化 | MethodNode JSON 响应 → 所有 REST 端点返回的 MethodNode 自动含新字段（3 个端点需显式补字段） |
| 直接 | 前端类型 | `MethodNode` TypeScript 接口需加 optional 字段 → 已有组件不受影响（结构性类型） |
| 间接 | 构建管道 | 5 个注入点 → KnowledgeGraphBuilder + IncrementalKnowledgeGraphBuilder 测试 mock 集扩展 |
| 间接 | MCP 工具 | 新增 6 个工具 → MCP Server 的 KG_TOOLS 数组从 15 扩展到 21 |
| 间接 | 前端组件 | 6 个新切面页面 → 路由 + Pinia store 扩展 |

### 证据表

| 类型 | 结论 | 证据 |
|------|------|------|
| 事实 | MethodNode 当前有 20 个字段，不含 packageName | `neo4j/model/MethodNode.java` lines 20-153 |
| 事实 | 全量构建用 DETACH DELETE + MERGE（非事务性），部分失败不整体回滚 | `KnowledgeGraphBuilder.buildJavaKnowledgeGraph()` lines 333-614 |
| 事实 | 增量构建 V2 已有，通过 JGIT 差异检测 | `IncrementalKnowledgeGraphBuilder.java` lines 98-129 |
| 事实 | Spring AI ChatClient 已配置，主模型 Claude Sonnet 4 | `AgentConfig.java` lines 17-30, `application.yml` spring.ai.anthropic.* |
| 事实 | MethodNode.className 存储全限定名（含包名信息） | `Neo4jInitializerIndexTest.java` 测试数据 |
| 事实 | 生成模型（UnitTestRequest/Response、BusinessFlowRequest/Response）已定义但后端未实现 | `knowledgegraph/model/UnitTestRequest.java`, `knowledgegraph/model/BusinessFlowRequest.java` |
| 推断 | packageName 可从 className 提取（lastIndexOf('.') 之前的部分） | className 格式范例如 `com.huawei.hisi.neo4j.service.HybridSearchService` |
| 推断 | Louvain 算法对 8000 节点规模在 Java 中自实现可行（<5 秒，~400MB 内存） | 算法特性分析：O(N log N) 复杂度，已有 `findAllCallRelationsByProjectPaths()` 可流式传输 CALLS 边 |
| 决策 | 聚合 Stage 在聚合 Stage 中创建 DomainNode（非懒加载） | 用户确认：决策 #17 |
| 决策 | 聚合失败不阻断构建，降级展示上次成功数据（B+C 容错） | 用户确认：决策 #16 |
| 决策 | 增量时社区检测全量重跑（Louvain 是全局优化问题） | 用户确认：决策 #3 + #13 |

## 消歧与闸门

### 开放问题清单

| 优先级 | 问题 | 代码事实背景 | 选项与影响（摘要） | 建议 | 状态 | 最终决策 |
|--------|------|-------------|-------------------|------|------|---------|
| 必选 | P0 切面实施顺序：先做架构仪表盘首页还是先做完整的 6 切面？ | 前端工作量约 59 人天。首页是"5 秒哇"体验的核心 | A: 先做架构仪表盘 + 爆炸半径（2-3 周）→ B: 再做 DSM + 热点（3-4 周）→ C: 领域 + 跨服务 + 生成中心（4-5 周）| 建议：A（先交付核心价值，迭代扩展）| decided | B: 严格分3阶段渐进交付 |
| 必选 | 前端图渲染库：G6 v5、Cytoscape.js（已安装但未使用）还是继续 SVG？ | `package.json` 已有 cytoscape@3.33.4 + cytoscape-fcose@2.2.0（未使用）；dagrejs 正在用 | A: 引入 G6 v5（WebGL，600KB，Vue 3 官方 wrapper）→ B: 激活已有 cytoscape（Canvas，已安装，无需新依赖）→ C: 继续 SVG + dagre（零成本，维持现状）| 建议：A（调研结论：G6 v5 性能最佳 + Vue 3 支持最好）| decided | A: 引入 G6 v5 |
| 可选 | 生成中心：测试建议和重构建议是分开的切面还是合并为一个"智能建议"面板？ | 两个功能共享相同的 LLM 管道（Claude + Spring AI）和 KG 数据 | A: 分开两个 Tab → B: 合并为一个"智能建议"面板，根据上下文（选中方法 vs 选中模块）切换内容 | 建议：B（合并面板，减少 Tab 数量）| decided | A: 合并为一个上下文感知面板 |
| 可选 | 是否需要在 P0 就包含领域/DDD 切面，还是可以推迟到 P1？ | 领域检测需要 Louvain 自实现（~400 行 Java）+ LLM 命名（Claude 调用），开发成本较高 | A: P0 包含领域切面（完整方案）→ B: P0 只做仪表盘+DSM+热点+爆炸半径，领域推迟到 P1 | 建议：B（领域切面准确率依赖调优，先交付确定性高的切面）| decided | B: P0 包含领域切面（6切面全部在P0交付，分3阶段） |

### 澄清完整性扫描
- 已检查的适用维度：使用者角色（架构师）、正常流程（打开仪表盘→查看风险→深度分析→生成建议）、空状态（无 KG 数据时引导构建）、数据保存（预计算缓存写入 Neo4j / 改名写入 SQLite）、现有调用方（MethodNode 序列化向后兼容）、性能（聚合管道预期 <30s）、可观测性（聚合失败日志）、回归验证（mvn test 全量）
- 由证据解决的缺失事实：MethodNode 属性列表（审计确认 20 个现字段）、mergeAll 行为（审计确认显式 SET + MERGE 模式）、生成模型已定义（审计确认 UnitTestRequest/Response 等已存在）
- 新增开放问题及处理状态：4 个已决 → 全部 closed
- 明确不适用 / 不在范围的维度：并发用户会话（单人工具）、权限系统（本地工具）、CI/CD 集成（无渠道）、数据隐私（本地存储）
- 结论：无实质阻塞项；4 个产品决策已确认；可进入规格闸门

### 风险定级与闸门建议
- 建议车道/风险：**High**
- 命中的风险特征：
  1. **数据 schema / 迁移 / 回填**（红线：migration）— MethodNode 加 5 个新属性，新增 4 种 Neo4j 节点类型
  2. **公共 API/协议字段变更**（红线：public contract）— 6 个新 REST 端点 + 3 个已有端点响应格式变更
  3. **跨模块/服务边界**— 后端（Java）/ MCP Server（TypeScript）/ 前端（Vue 3）三端联动
  4. **核心数据模型变更**（红线范围内）— MethodNode 是 KG 核心节点类型，变更波及 ~25 个 Cypher 查询
- 未命中的高风险特征：
  - 鉴权/权限（不涉及）
  - 支付（不涉及）
  - 隐私（不涉及）
  - 破坏性操作（迁移 API 只增不删，历史数据可回填）
- 不确定点：
  - Louvain 在 8000+ 节点规模下的实际运行时间和内存消耗（理论估算 <5s/400MB，需实测）
  - G6 v5 在前端渲染 500+ 模块节点时的实际帧率（理论 WebGL 可达 60fps，需实测）
- 闸门建议：Standard 实验门 + 增量交付（每阶段独立验证 + 全量回归）
- 可用验证：`mvn test`（已有 ~315 个 KG 相关测试）、Neo4j Testcontainers 集成测试、MCP 工具集成测试
- 缺失验证：G6 v5 性能基准测试、聚合管道大规模数据压测、前端 E2E 测试（需 Playwright）

### Explore 交接消费
N/A — 无 explore handoff（探索阶段为 Agent 内部 grill + 圆桌讨论 + 调研，非 delivery-explore 技能产出；方向已由用户 17 轮交互确认）

### 状态源与工件位置
- 后端：OpenSpec change `multi-perspective-platform`
- 路径：`openspec/changes/multi-perspective-platform/`
- 闸门记录：
  - 规格闸门：通过（2026-08-11）— 4 个规格文件（aggregation-pipeline / api-endpoints / mcp-tools / frontend-dashboard / generation-center）已就绪
  - 审查修正：偏差 1（生成中心缺少后端 spec）→ 新增 `specs/generation-center/spec.md`；偏差 2（容错未写重试次数）→ 聚合 spec 失败行为加"3 次重试，间隔 5s/15s/60s"
  - 实施批准人：shenjiangchun
  - 附加约束：分 3 阶段渐进交付（每阶段独立验证 + 全量回归）；所有变更禁止修改已标记 @Deprecated 的管道；mvn test 全量回归绿灯后方可进入下一阶段
