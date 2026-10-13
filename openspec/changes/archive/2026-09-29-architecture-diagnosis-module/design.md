## Context

架构分析能力当前分布在 `KnowledgeGraphController`（3700 行，含 12+ 个架构相关 GET 端点）与 `KnowledgeGraphV2Controller`（委托 V1，`projectPaths` 必填）中，前端 `KnowledgeGraphView.vue`（1621 行）以 13 个 tab 混排「语义搜索」与「架构分析」。真实架构师工作流（C4 + ArchUnit + 演化适应度函数）是「自顶向下下钻 + 每个问题追到代码证据」，产出的是**诊断报告**而非工具箱。

本 change 不改动现有聚合管线（`AggregationPipeline` 7 Stage）与现有 12 个架构端点的行为，而是在其上叠加一个**报告聚合层**，并把前端重构成报告优先的独立页面。

关键约束：
- `AdminOnlyInterceptor` 对非 `/api/users/**` 的 GET 放行 → 所有新读端点必须是 GET。
- `MethodNode.riskScore` 字段虽存在，但 `HotspotScorer` 只把 riskScore 写入 `ChurnNode`（文件级），从不写 `MethodNode` → 方法级热点需在报告端点内实时聚合，而非读现成字段。
- `ClassLayerRoleDetector` 已内置 `LAYER_ORDER` 偏序（后端）；前端 `DashboardPanel` 另有硬编码 `LAYER_BIAS` → 需收敛为单一来源。
- `ClassNode` 无 WMC/扇入/扇出字段 → 上帝类/枢纽类需实时 Cypher 聚合（不落库）。

## Goals / Non-Goals

**Goals:**
- 单一报告端点 `GET /api/architecture-diagnosis/report` 输出四层诊断报告（健康分 + 问题清单 + 各章数据），前端一行调用即可渲染。
- 方法级热点、类级上帝/枢纽、爆炸半径测试建议三个「结论性」输出补齐（gaps #4 核心）。
- 快照历史（SQLite）+ 报告导出（Markdown）闭环（gaps #3/#7）。
- 前端"架构诊断"独立一级页面，报告优先 + 四章锚点导航，复用全局项目选择与历史报告查看。
- 分层偏序单一来源（gap #6）。

**Non-Goals:**
- 不重写聚合管线 / 不改动现有 12 个架构端点的返回结构（报告聚合层只做"组合 + 评分 + 排序"）。
- 不做 LLM 生成式诊断报告（不在本版引入 LLM 诊断文案）——按 grilling 结论，评分/清单/证据链由规则驱动。
- 不做历史快照的差异对比（diff）可视化，仅趋势折线（横切 drift 首版）。
- 不动知识图谱页的 7 个非架构 tab（understand/semanticSearch/…/generationCenter）。

## Decisions

### D1: 新独立控制器 vs 在 V2 控制器加方法
选用**新 `ArchitectureDiagnosisController`**（`@RequestMapping("/api/architecture-diagnosis")`），构造函数注入 `KnowledgeGraphController`（v1）复用其公共方法（`getDashboard`/`getHotspots`/`getDomains`/`getServiceTopology`/`getDsm`/`getPackageCycles`/`getModuleCycles`/`getClassLayerViolations`/`getClassDependencies` 等需访问权限为 public 或其返回 `ApiResponse`）。理由：与 V2Controller 委托 V1 的先例一致；避免把 3700 行的 V1 控制器继续膨胀；路径独立于 `/knowledge-graph` 语义。

备选：塞进 V2Controller —— 但 `/api/architecture-diagnosis` 是独立一级能力的对外契约，独立控制器更清晰，且不污染 KG 路由。

> 实现注意：需确认 V1 各端点方法为 `public`；若部分为 `private` 则改为在报告端点内直接 `neo4jDriver` 发查询（报告端点只读，与 V1 同用 `neo4jDriver`/`neo4jSessionConfig`/`ProjectPathResolver`）。

### D2: 报告聚合方式 —— 「端点组合」而非「复制 Cypher」
报告端点优先**复用 V1 现有方法**组装数据（batch-1 结论「组合现有端点方法」），仅对 V1 缺失的粒度补充**新的只读 Cypher**（方法级热点、类级 WMC/扇入扇出、扣分聚合）。理由：底层规则（环分类、分层违规、热点公式）已在 V1/Stage 中验证，复制会引入分叉。

备选：报告端点内重写所有 Cypher —— 被 batch-4「不追求最大复用」否决，但可在**前端呈现层**自由重构，后端仍组合复用。

### D3: 方法级热点 —— 实时聚合，不新增落库
在报告端点内用 Cypher 聚合 `Method` 节点：`complexity` + `inDegree` + 与 `ChurnNode`（按 `filePath`）的 `commitCount90d` join，套用 `RiskScoreCalculator.calculate` 得方法级 riskScore，写回响应。理由：`MethodNode.riskScore` 当前恒为 null（`HotspotScorer` 不写它），落库需改聚合管线（成本高、动核心链路）；实时聚合只读、可测试、不改管线。

备选：改 `HotspotScorer` 让它同时写 `MethodNode.riskScore` —— 被否：动聚合管线违背 Non-Goals。

> 非 Java 项目（Python 无 ChurnNode）：`commitCount90d` 缺失时 churn 分项按 0 计，方法级风险仍由复杂度 + 入度 + 循环惩罚兜底，方法级章节照常可用。

> **churn 分量切换点**：方法级热点聚合的 churn 取值封装为单个可替换函数 `resolveMethodChurn(methodNode)`——首版读文件级 `ChurnNode.commitCount90d`（按 `filePath` join），待「方法级 churn 接口」落地后改为读 `MethodNode` 方法级字段，其余评分逻辑不动。

### D4: 上帝类/枢纽类 —— 实时 Cypher 聚合
- 上帝类：`MATCH (c:Class)-[:HAS_METHOD]->(m:Method)` 按 `className` 聚合 `count` 与 `sum(m.complexity)` 为 WMC，按 `methodCount` 阈值（≥ 全项目 95 分位）或 `wmc` 阈值判定。
- 枢纽类：基于 `Class -[:HAS_METHOD]-> Method -[:CALLS]-> Method <-[:HAS_METHOD]- Class` 聚合每类 `inDegree`/`outDegree`（类间，排除自环），判定双高（各 ≥ 95 分位）。
不落库，不扩 `ClassNode` 字段。理由：度量是派生值，落库需改实体 + 建图 + 增量，收益低。

### D5: 评分模型 —— 固定扣分 + 封顶（batch-2 结论）
`healthScore = 100 - Σ min(维度扣分, 25)`。severity → 分：HIGH -10 / MEDIUM -5 / LOW -2。问题来源到 severity 的统一映射沿用 batch-2 决策表：
- CROSS_LAYER 循环 + 领域循环 → HIGH
- 分层违规 + 领域依赖 → MEDIUM
- 类级分层违规疑似 + 同层循环技术债 → MEDIUM
- 热点 → LOW/MEDIUM（按 riskScore 阈值）
- 每个 issue 保留 `sourceLevel` 原文 + 统一 `severity`。

### D6: 快照存储 —— SQLite 新表，复用 `SQLiteSchemaInitializer`
新表 `architecture_diagnosis_snapshot(id, scope, health_score, grade, high_count, medium_count, low_count, report_json, created_at)`，`CREATE TABLE IF NOT EXISTS` 加到 `SQLiteSchemaInitializer.initialize()`。报告端点成功后落快照：**复选 N 个项目时落 N 条，每条 scope = 单个 projectPath**（非组合键），历史按单项目维度查看演进。`report_json` 存全量报告体，供历史导出还原。理由：batch-3 结论「SQLite 新表 + 自动快照 + 总分/各严重度计数趋势」+ grilling 追加「每项目单独一份」。

### D7: 分层偏序统一 —— 后端常量 + 端点暴露
将 `ClassLayerRoleDetector.LAYER_ORDER` 恒等常量与前端 `LAYER_BIAS` 收敛：后端报告响应内携带权威 `layerOrder` 数组（如 `[CONTROLLER, SERVICE, REPOSITORY, MODEL, UTILITY]`），前端 `DashboardPanel` 改为读该配置；`LAYER_BIAS` 从组件内删除。理由：`LAYER_ORDER` 已是后端权威，前端复制一份即分叉风险。

### D8: 报告导出 —— Markdown 文本
导出端点返回 `text/markdown`，内容为报告结构化快照（健康分 + 等级 + 问题清单 + 各章摘要 + 证据文件路径）。支持两种来源：即时 `projectPaths` 生成、或 `snapshotId` 从历史快照 `report_json` 还原。前端提供下载按钮（Blob + 文件名）。理由：Markdown 零依赖、可进评审文档，PDF 加重前端依赖风险。

### D9: 前端信息架构 —— report-first + 四章锚点
```
/architecture
├── 诊断总览（顶部）  健康分卡 + ABCD + KPI 总览 + 按 severity 排序问题清单
├── 第 1 章 项目级   服务清单/技术栈/服务拓扑 + C4 式容器图
├── 第 2 章 模块级   DSM 矩阵 + 分层图 + 循环/分层违规清单
├── 第 3 章 类级     类依赖图下钻 + 上帝/枢纽度量排行
├── 第 4 章 方法级   方法级热点 treemap + 风险排行 + 爆炸半径卡片
└── 快照历史         健康总分折线 + 高/中/低计数趋势
```
左侧侧边导航加「架构诊断」菜单（MenuKey `architecture-diagnosis`，`availableMenus` 加键，复用 `getSelectedProjectPaths()`），路由 `/architecture` + 守卫（同 `/knowledge-graph` 缺项目菜单重定向 `/project`）。

## Risks / Trade-offs

- [报告端点单次调用较重（聚合 10+ 底层端点）] → 报告端点同步组装、前端 loading 态；首版不做缓存，慢项目后续可加 `@Async`/缓存。
- [方法级热点实时 Cypher join ChurnNode 在大图可能慢] → `LIMIT $limit` + 只取 top N；非 Java 项目 churn 缺失按 0 计，方法级风险仍由复杂度+入度+循环惩罚兜底。
- [V1 端点方法非 public 导致无法复用] → 实现时确认；若不 public 则报告端点直接发只读 Cypher（同 Driver），不影响结论。
- [快照 `report_json` 体积膨胀] → `TEXT` 列存储，`scope` 建索引，历史查询不回传 `report_json`（仅趋势字段）。
- [前端 7 个组件平移后可能成为死代码] → 组件文件保留（诊断页初期可复用 DashboardPanel/HotspotTreemap 等），仅移除 `KnowledgeGraphView.vue` 中的 tab 引用；若诊断页最终不同，再收敛组件。

## Migration Plan

1. 加 `architecture_diagnosis_snapshot` 表（`CREATE TABLE IF NOT EXISTS`，幂等）。
2. 新增 `ArchitectureDiagnosisController` 只注册 GET 端点，不改动现有端点 → 可独立部署，无回滚风险。
3. 前端新增 `/architecture` 路由与页面（独立文件，不影响现有 KG 页）。
4. 最后移除 `KnowledgeGraphView.vue` 的 7 个架构 tab（单文件改动，可独立 revert）。
5. 回滚：删除新端点 + 新路由 + 移除 tab 的 diff 即可，数据层仅多一张表（删除无损）。

## Open Questions

- （已由 grilling 关闭）方法级是否新增聚合端点 → 是（D3）。
- （已由 grilling 关闭）爆炸半径是否修 suggestedTests → 是（修）。
- （已由 grilling 关闭）导出是否纳入 → 是（Markdown）。
- （已由 grilling 关闭）LAYER_BIAS 是否配置化 → 是（D7）。
- （已由 grilling 关闭）报告模式同步/异步 → 同步一次返回（前端 loading，慢项目后续升级）。
- （已由 grilling 关闭）非 Java 方法级 → churn 缺失按 0 计，方法级章节照常可用。
- （已由 grilling 关闭）快照 scope 语义 → 复选 N 项目落 N 条、每条 scope=单项目。
- （已由 grilling 关闭）导出来源 → 即时 projectPaths + snapshotId 历史还原两者都要。
- 报告端点是否做成异步（类比 `/architecture-analysis` 的 status 轮询）→ 首版同步；若底层端点慢再升级，不阻塞本 change。

## Deferred（后续独立 change，不在本 change 范围）

- **方法级 churn 专有接口**（已拍板）：本 change 方法级章节首版用文件级 `ChurnNode.commitCount90d` 近似（经 `resolveMethodChurn` 切换点）。真·方法级 churn 作为**独立 change**立项，规格：新增专有接口（如 `GET /api/v2/knowledge-graph/method-churn`），用 JGit 精确档位（`-L` 行号追踪，兼顾 `MethodNode.startLine/endLine` 的历史行号漂移），算完后**落库**（`MethodNode` 新增方法级 churn 字段，如 `churnCount90d`）。落地后架构诊断方法级章节切换到该字段，其余评分逻辑不变。