# arch-deep-analysis Specification

## ADDED Requirements

### Requirement: 跨项目聚合深度分析
系统 SHALL 支持 AI 深度分析多选项目，跨项目聚合生成一份架构分析报告书（4+1 视图 + 跨项目问题对比），而非每个项目各出一份。

#### Scenario: 多项目聚合一份报告
- **WHEN** 用户在 AI 深度分析入口多选多个项目
- **THEN** 证据收集与灰度过滤在多个项目上分别进行，报告节点聚合生成一份跨项目报告书

#### Scenario: 单项目仍可用
- **WHEN** 用户在 AI 深度分析入口仅选单个项目
- **THEN** 深度分析针对该项目生成报告，行为与现状一致

## MODIFIED Requirements

### Requirement: 证据收集节点
系统 SHALL 实现确定性证据收集节点（不调 LLM）：调用诊断报告逻辑（persist=false）获取四章数据与问题清单，并为每个问题条目补充可查证定位信息（className/methodName/nodeId），再通过知识图谱只读查询补充证据（参与环的关键类 ego-net、违规依赖的代码位置），产出证据包并写入节点 CHECKPOINT。

#### Scenario: 产出完整证据包（含定位信息）
- **WHEN** 证据收集节点执行且报告与 KG 查询均成功
- **THEN** 节点输出证据包（chapters/issues/KPI/KG 摘录），其中 issues 每条含可查证定位信息（className/methodName/nodeId），CHECKPOINT 落库，后续节点可复用

#### Scenario: 底层数据缺失降级
- **WHEN** 项目未构建 KG 导致报告或 KG 查询失败/为空
- **THEN** 节点以可得数据继续（空集也算合法证据包）或以明确错误终止工作流，前端能看到具体失败原因

### Requirement: 根因分析 LLM 节点与 JSON 契约
系统 SHALL 实现问题根因分析节点：以证据包（候选问题 + 可查证定位信息）为输入，先用只读工具逐条查证候选问题真伪，按 JSON 契约 schema（`classpath:schemas/arch/` 下）输出每项判定（`verdict ∈ confirmed/false_positive/downgraded` + 理由）及 confirmed 问题的根因清单（问题引用、标题、严重度、根因、证据引用、修复建议），经 schema 校验。校验失败 SHALL 重试一次，仍失败则降级：保留证据包（不过滤）并在报告中标注过滤失败，不阻塞报告节点。灰度过滤结果只进入 AI 深度分析报告，不回写看板 issue。

#### Scenario: 误报被灰度过滤
- **WHEN** 候选问题经查证为误报（如测试类触发的"实现类外泄"）
- **THEN** 该项 `verdict=false_positive` 并附理由，不进入最终根因清单

#### Scenario: 真问题确认并归纳根因
- **WHEN** 候选问题经查证为真实架构问题
- **THEN** 该项 `verdict=confirmed`，进入根因清单并附根因、证据引用、修复建议

#### Scenario: 严重度虚高降级
- **WHEN** 候选问题存在但严重度被高估
- **THEN** 该项 `verdict=downgraded` 并附降级理由

#### Scenario: 契约校验失败重试与降级
- **WHEN** LLM 返回不符合 schema 的 JSON（两次均失败）
- **THEN** 节点标记过滤失败，保留证据包（不过滤），工作流继续执行报告节点，报告中标注过滤失败

### Requirement: 架构现状报告 LLM 节点
系统 SHALL 实现架构现状报告节点：以证据包与灰度后根因结果为输入调用 LLM，生成《架构现状分析报告书》Markdown 报告，内容 MUST 覆盖 4+1 视图（逻辑/过程/开发/物理/场景）与架构问题分析（灰度后问题 + 根因 + 改进建议 + 证据附录）。报告 SHALL 持久化（复用 agent 会话存储或新增最小表），支持前端查询与导出。

#### Scenario: 生成 4+1 视图报告书
- **WHEN** 报告节点执行成功
- **THEN** 产出含逻辑/过程/开发/物理/场景五视图 + 问题分析的 Markdown 报告书并持久化，前端可渲染

#### Scenario: 4+1 视图由 AI 生成
- **WHEN** 报告节点生成 4+1 视图内容
- **THEN** 视图内容由 LLM 基于 ReAct 采集证据生成，非图谱规则拼装

#### Scenario: 报告随项目切换失效
- **WHEN** 用户切换所选项目
- **THEN** 前端展示的深度分析结果被重置，不展示其他项目的报告
