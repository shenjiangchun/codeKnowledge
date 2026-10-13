## Context

现状：变更频率只有**文件级**。`ChurnCollector`（`aggregation/stage/ChurnCollector.java`）用 JGit `git.log().addPath(relPath)` 按文件聚合近 90 天提交，结果写 `ChurnNode.commitCount90d`。`MethodNode.riskScore` 字段虽存在但 `HotspotScorer` 从不写它——方法级热点"哑火"。

架构诊断模块（change `architecture-diagnosis-module`）的方法级章节需要**真·方法级 churn**。当前它的首版用文件级 `ChurnNode.commitCount90d` 近似（经 `resolveMethodChurn` 切换点），本 change 补齐精确数据源。

关键约束（复用 `ChurnCollector` 的既有结论）：
- `MethodNode.filePath` 是**绝对路径**，JGit `addPath` 需要**相对仓库根**的路径 → 需 `toRepoRelativePath` 归一。
- `MethodNode.startLine/endLine` 是**当前文件**的行号；JGit `-L` diff 的行号是相对**旧 blob** 的行号 → 存在历史行号漂移，升级到方法级必然要处理。
- `AdminOnlyInterceptor` 对非 `/api/users/**` 的 GET 放行 → 新端点必须是 GET。
- 全量重建用 DETACH DELETE + MERGE，`churnCount90d` 若不经 `mergeAll` 会随之清空 → 需 on-demand 回填语义。

## Goals / Non-Goals

**Goals:**
- 精确方法级 churn：JGit `-L start,end:file` 行号追踪，统计每个方法近 90 天被提交触碰的次数。
- 落库：`MethodNode` 新增 `churnCount90d` 字段，接口算完写回，供架构诊断 `resolveMethodChurn` 切换读取。
- 只读查询接口 `GET /api/v2/knowledge-graph/method-churn` 返回逐方法结果。
- 历史行号漂移有明确降级路径（无法锚定 → 文件级近似 / null）。

**Non-Goals:**
- 不改动文件级 `Churn Stage` / `Hotspot Stage`（它们仍是文件级唯一落点）。
- 不把方法级 churn 纳入聚合管线 (7 Stage) —— 它是独立 on-demand 计算，避免动核心链路。
- 不做跨行号漂移的"完美"映射（如 diff3 / 重命名追踪）——首版用上下文锚定 + 文件级降级兜底。
- 不改架构诊断模块的评分公式本 change 不改（切换点已在那个 change 预留）。

## Decisions

### D1: 计算方式 —— JGit 行号追踪（`-L` 语义）而非文件级近似
对每个 `MethodNode`，用 JGit 读该文件近 90 天的 `RevCommit` 序列，对每次提交的父/子 blob 做 diff，把 diff 中 `Edit` 区间与方法的 `[startLine, endLine]` 区间做**重叠判定**：有重叠即计 1 次提交。

理由：这是"方法级 churn 精确档位"的定义——按行号区间判断某方法是否被改过，而非整文件被改就算。JGit 的 `DiffFormatter` + `Edit`（`ChurnCollector.countDiffLines` 已用同样机制）可直接复用。

备选：`git log -L` 原生标注 (`startLine,endLine:file`) —— 语义最精确（能追踪行号漂移），但 JGit `LogCommand` 无原生 `-L` 支持，需手写 `LineSequence` 对齐或 shell 出进程。首版用「diff 区间重叠」近似，成本低、无外部进程。

### D2: 历史行号漂移 —— 上下文锚定 + 降级
JGit diff 的 `Edit` 区间是相对旧 blob 的行号，方法当前 `[startLine, endLine]` 是当前 blob 的行号。对回溯提交链：
1. **正向锚定**：从最近提交回溯，每次用上一次映射后的行号区间去匹配下一层旧 blob 的 diff，区间随 diff 的 delete/insert 偏移量**滚动更新**（行号漂移的简单补偿）。
2. **锚点兜底**：用方法签名/首行内容在旧 blob 中匹配，若匹配不到则停止回溯，该提交链到此为止。
3. **最终降级**：无法可靠锚定时，该方法 churn 记为「文件级近似」（读 `ChurnNode.commitCount90d`）或 null。

理由：JGit `-L` 本质就是沿提交链滚动行号区间，正向滚动 + 内容锚点是其最小可用实现；完整 diff3/重命名追踪是本 change 的 Non-Goal。

### D3: 落库方式 —— 直接 Driver MERGE 写入，不走 mergeAll
用 `neo4jDriver.session(neo4jSessionConfig)` 发 `MATCH (m:Method {nodeId:$id}) SET m.churnCount90d=$c`（或 `UNWIND` 批量），与 `ChurnCollector` 写 `ChurnNode` 的"直接 Driver MERGE 避免 transactionManager"先例一致。

理由：不引入 Spring Data Repository 事务；on-demand 字段写入不污染聚合管线。

### D4: 端点位置 —— 复用 `KnowledgeGraphV2Controller` 或新 `MethodChurnController`
新只读端点挂在 `/api/v2/knowledge-graph/method-churn`（与 v2 现有 hotspots/domains 并列，语义是"图谱方法级 churn 查询"）。实现上优先在 `KnowledgeGraphV2Controller` 加方法委托，或独立 `MethodChurnController`（如 V2 控制器已过大）。计算逻辑放独立 `MethodChurnCollector`（聚合 stage 包旁），控制器只做参数解析 + 调用 + 落库触发。

理由：路径语义属于图谱 v2；计算逻辑独立便于测试与复用。

### D5: 字段类型 —— `Integer churnCount90d`（默认 null）
null 表示"未计算"，与 `ChurnNode.commitCount90d`（Integer）对齐。非 Java 项目（Python 无 Git 或行号缺失）保持 null，调用方 `resolveMethodChurn` 按 0 计。

理由：null 语义区分"未计算/不可计算"与"0 次变更"，避免把缺数据误判为"从不改动"。

### D6: 批量与限流
计算按 projectPaths 分批遍历 MethodNode，`limit` 参数控制单次返回上限；落库用 `UNWIND $batch` 批量 SET，避免逐方法往返。大项目慢由 limit + 批量摊薄，首版同步返回（与架构诊断报告端点同步返回的既有决策一致）。

理由：不做后台任务/轮询（`/architecture-analysis` 的 status 轮询是另一回事），保持 on-demand 简单。

## Risks / Trade-offs

- [行号漂移回溯不完美，可能少算/多算历史提交] → D2 正向滚动 + 内容锚点兜底，最终降级文件级近似；不追求 diff3 精确。
- [每方法一次 diff，大图/大历史可能慢] → `limit` 上限 + `UNWIND` 批量写 + 只遍历项目内方法；后续可加 `@Async`/缓存。
- [全量重建后 `churnCount90d` 清空] → 记录 on-demand 回填语义（D3/规格 Requirement 3），架构诊断切换点 fallback 到文件级。
- [非 Git 项目 / 行号缺失] → 保持 null，调用方降级；不报错中断。

## Migration Plan

1. `MethodNode` 实体加 `@Property("churnCount90d") Integer` 字段（仅模型，无 schema 迁移，Neo4j 动态 label 属性）。
2. 新增 `MethodChurnCollector`（JGit 行号追踪 + 降级）+ 落库逻辑。
3. 新增 `GET /api/v2/knowledge-graph/method-churn` 端点（只读，GET，无回滚风险）。
4. 架构诊断 change 落地时，`resolveMethodChurn` 从文件级切换为读 `MethodNode.churnCount90d`（若无值则 fallback）。
5. 回滚：删除新端点 + 新 collector + `MethodNode` 字段即可，数据层仅多一个动态属性。

## Open Questions

- 方法签名字符串匹配（D2 锚点）对重载方法的区分度 —— 需实测，首版用「签名 + 首行内容」联合匹配。
- Python 项目（无 `startLine`/`endLine` 或非 Git）的精确档位是否要单独支持 —— 首版按规格降级 null，后续独立考虑。