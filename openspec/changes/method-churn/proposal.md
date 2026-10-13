## Why

当前变更频率（churn）只有**文件级**粒度：`ChurnCollector` 用 JGit 按文件跑 `git log`，结果落 `ChurnNode.commitCount90d`。`MethodNode.riskScore` 字段虽然存在，但 `HotspotScorer` 从不写它——方法级热点因此"哑火"，只能拿文件级 churn 近似。架构诊断报告的「方法级热点」章节需要**真·方法级 churn**（某方法自身在近 90 天被改了几次），才能让热点排序/爆炸半径建议有方法级证据支撑。这是 method-churn 独立 change 的立项原因。

## What Changes

- 新增**方法级 churn 专有接口** `GET /api/v2/knowledge-graph/method-churn`：对选定 projectPaths，按 `MethodNode` 的 `filePath + startLine + endLine` 用 JGit 做**精确行号追踪**（等价 `git log -L`，兼顾历史行号漂移），统计每个方法近 90 天的提交次数。
- `MethodNode` 新增方法级 churn 字段 `churnCount90d`（Integer，默认 null），由该接口算完后**落库**（直接 Driver 写，不经过 `mergeAll` / 聚合管线）。
- 接口同时**返回**逐方法的 churn 结果（不落库也可查询）。
- 落地后，架构诊断模块的方法级章节从"读文件级 `ChurnNode.commitCount90d` 近似"切换到"读 `MethodNode.churnCount90d`"，其余评分逻辑不变（经 `resolveMethodChurn` 切换点）。

## Capabilities

### New Capabilities

- `method-churn`: 方法级 Git 变更频率的精确计算、落库与查询接口。用 JGit 行号追踪统计每个方法近 90 天的提交次数，写入 `MethodNode.churnCount90d` 并返回结果。

### Modified Capabilities

（无。文件级 `Churn Stage` 与 `Hotspot Stage` 的既有需求不受影响，方法级 churn 是新增能力而非对既有需求的改写。）

## Impact

- **`MethodNode`**（Neo4j 实体）：新增 `@Property("churnCount90d") Integer` 字段，全量重建后需重跑本接口回填（独立于聚合管线的 on-demand 计算语义）。
- **`KnowledgeGraphV2Controller`**（或新建 `MethodChurnController`）：新增只读 `GET` 端点（受 `AdminOnlyInterceptor` 约束，必须为 GET）。
- **新增 JGit 行号追踪器**（如 `MethodChurnCollector`）：复用 `ChurnCollector` 的 Git 仓库打开/相对路径归一逻辑，新增行号漂移映射。
- **`RiskScoreCalculator` / 架构诊断模块**：不受本次改动；仅在架构诊断 change 中经 `resolveMethodChurn` 切换读取来源。
- **非 Git 项目 / 行号无法解析**：降级为空或文件级近似，不阻塞。