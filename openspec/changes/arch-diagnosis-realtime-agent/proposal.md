## Why

架构诊断页当前存在体验与定位错位：报告生成是纯确定性业务代码（规则 + 只读查询，秒级返回），却要求用户点击「生成诊断报告」才出内容，进页面只有空态；而真正需要 LLM 结合真实代码做深度分析的「项目级架构现状及问题分析」反而完全缺失。同时报告存在三个缺陷：模块级章节恒为空（后端环数据只写入 issues 未写入 chapter，前端也不渲染 DSM）、切换项目后旧报告残留（状态未重置）、类级/方法级只有纯表格不直观。

## What Changes

- **实时统计报告（去按钮化）**：进入架构诊断页 / 切换项目后自动调用现有纯规则报告接口（`persist=false` 不落快照），秒级出报告；「保存快照」改为显式按钮（`persist=true`）。
- **修复模块级章节恒空**：后端 `collectModuleChapter` 把包环/模块环数据写入 `chapter.module.cycles`（当前只进 issues）；前端模块级 tab 渲染 DSM 矩阵（复用 `DsmMatrix.vue`）+ 环列表。
- **修复切换项目报告残留**：监听项目选择变化即重置报告与快照列表，并自动重新生成实时报告。
- **类级/方法级可视化增强**：方法级热点接入现成 `HotspotTreemap.vue`（treemap）替代纯表格；类级上帝类/枢纽类增加度量条形图。
- **新增 agent 流深度分析（点击触发）**：基于现有通用工作流引擎（`DagNode` + `WorkflowAutoConfig` + `DagExecutor`）做通用扩展，注册 `arch-deep-analysis` 工作流：①证据收集节点（复用 `ArchDiagnosisReportService` 各章数据 + KG 查询）→ ②问题根因分析节点（LLM）→ ③架构现状报告节点（LLM 生成项目级架构现状及问题分析 Markdown）。进度经 SSE 流式推送，前端用现成 `useWorkflow.ts` + StepCard 进度卡 + Markdown 渲染。
- **补齐 `WorkflowController POST /api/workflow/start`**：当前只有 Javadoc 无实现（工作版本在 `RamController`），从 `RequirementAnalysisOrchestrator.start` 移植通用启动路径，使任意工作流类型可从前端直接启动（通用能力，架构诊断为首个消费方）。

## Capabilities

### New Capabilities

- `arch-diagnosis-realtime`: 实时统计报告——进页/切项目自动生成（不落快照）、显式保存快照、四章数据完整渲染（DSM/环/度量图）、项目切换状态重置。
- `arch-deep-analysis`: agent 流深度分析——`arch-deep-analysis` 工作流定义、证据收集/根因分析/现状报告三节点、SSE 流式进度、深度分析报告展示与导出。

### Modified Capabilities

- `architecture-diagnosis`: 四章数据完整性要求收紧——模块级章节 SHALL 包含环列表与 DSM 数据；报告接口新增 `persist` 语义（实时查询不落快照）。该能力规范由在途变更 `architecture-diagnosis-module` 引入，本变更依赖其先行落地（归档）。

## Impact

- **后端修改**：`ArchDiagnosisReportService.collectModuleChapter`（环数据入 chapter）、`ArchitectureDiagnosisController`（报告端点支持 `persist=false` 语义或新增查询参数）、`workflow/WorkflowController`（实现 `POST /start`，移植 `RamController` 通用启动路径）。
- **后端新增**：`workflow/nodes/ArchDeepAnalysisNodes`（证据收集/根因分析/现状报告三个 DagNode）、`WorkflowAutoConfig` 注册 `arch-deep-analysis`、LLM JSON 契约 schema（`classpath:schemas/arch/*.json`）、深度分析结果持久化（复用 `agent_session`/`agent_event` + 快照表扩展或新表）。
- **前端修改**：`views/architecture-diagnosis/ArchitectureDiagnosisView.vue`（实时生成逻辑、状态重置、深度分析入口与进度面板、四章可视化增强）。
- **前端复用**：`api/workflow.ts`、`composables/useWorkflow.ts`、`DsmMatrix.vue`、`HotspotTreemap.vue`、StepCard 进度卡组件。
- **依赖顺序**：依赖在途变更 `architecture-diagnosis-module` 的实现与归档；LLM 调用依赖已有 `DeepseekJsonClient`（deepseek 网关），不新增外部依赖。
- **性能**：实时报告为只读聚合（秒级）；深度分析为异步工作流（分钟级），SSE 流式反馈，不阻塞页面。
