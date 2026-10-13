## ADDED Requirements

### Requirement: 方法级变更频率精确计算与落库

系统 MUST 提供方法级 Git 变更频率计算能力：对 `MethodNode` 的 `filePath + startLine + endLine` 用 JGit 做**精确行号追踪**（等价 `git log -L start,end:filePath`），统计每个方法近 90 天的提交次数，并将结果写入 `MethodNode.churnCount90d`（Integer），不经过 `mergeAll` / 聚合管线。

#### Scenario: 方法级 churn 精确计算
- 前提：项目是 Git 仓库，MethodNode 已写入且带 `filePath`/`startLine`/`endLine`
- 当：方法级 churn 计算器遍历每个 MethodNode，用 JGit 对该方法的行号区间做近 90 天提交追踪
- 则：每个方法得到一个 `churnCount90d`（该区间在 90 天内被提交触碰的次数）
- 并且：结果写回 `MethodNode.churnCount90d` 字段

#### Scenario: 历史行号漂移补偿
- 前提：方法在当前文件的行号区间是 `[L1, L2]`，但历史提交中该方法曾处于不同行号区间
- 当：JGit `-L` 行号追踪在历史 blob 中按上下文锚定方法边界（签名/首行内容）折算行号
- 则：能正确把历史提交归到该方法，而非仅按当前行号硬匹配
- 并且：无法可靠锚定历史位置的方法按「文件级 churn 近似」降级，不中断整体计算

#### Scenario: 缺失上下文降级
- 前提：项目非 Git 仓库，或 MethodNode 缺 `startLine`/`endLine`
- 当：方法级 churn 计算运行
- 则：对应方法 `churnCount90d` 保持 null（不落库），计算跳过不报错
- 并且：调用方（架构诊断 `resolveMethodChurn`）fallback 到文件级 `ChurnNode.commitCount90d` 或 0

### Requirement: 方法级变更频率查询接口

系统 MUST 提供只读查询接口 `GET /api/v2/knowledge-graph/method-churn`，返回指定项目范围内逐方法的 churn 结果（`nodeId` / `className` / `methodName` / `filePath` / `startLine` / `endLine` / `churnCount90d`）。

#### Scenario: 查询方法级 churn
- 前提：`MethodNode.churnCount90d` 已写入
- 当：`GET /api/v2/knowledge-graph/method-churn?projectPaths=hisi-dev-tool&limit=200`
- 则：返回按 `churnCount90d` 降序排列的方法列表
- 并且：`churnCount90d` 为 null 的方法不进入结果集

#### Scenario: 未落库时返回空
- 前提：方法级 churn 尚未计算
- 当：`GET /api/v2/knowledge-graph/method-churn?projectPaths=hisi-dev-tool`
- 则：返回空列表（`success=true`，`data` 为空），不报错

### Requirement: 方法级 churn 字段持久化与重建语义

系统 MUST 在 `MethodNode` 新增 `churnCount90d` 字段，作为**独立于聚合管线的 on-demand 计算产物**：由方法级 churn 接口按需计算并落库；全量重建（DETACH DELETE + MERGE）后该字段被清空，需重跑方法级 churn 接口回填，不随 `mergeAll` 走全量覆盖语义。

#### Scenario: 全量重建后字段清空并可回填
- 前提：`MethodNode.churnCount90d` 已有值
- 当：全量重建运行（DETACH DELETE + MERGE 重建同一 nodeId）
- 则：新节点 `churnCount90d` 为 null（不在 `mergeAll` SET 子句中）
- 并且：重跑方法级 churn 接口后回填为最新值

#### Scenario: 字段不影响既有聚合字段
- 前提：`MethodNode` 已有 `riskScore`/`communityId`/`inDegree` 等聚合字段
- 当：方法级 churn 接口写入 `churnCount90d`
- 则：仅更新 `churnCount90d` 字段，不触碰其他字段