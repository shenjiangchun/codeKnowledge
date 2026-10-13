## Context

架构诊断模块现状（含原型图 `prototypes/`，对应目标态交互）：

- `ArchDiagnosisReportService.generate(paths, language, persist)` 是**纯确定性业务逻辑**（规则 + 只读查询聚合，秒级返回，运行时不调 LLM），且已有 `persist` 参数。但前端 `ArchitectureDiagnosisView.vue` 仍要求用户点击「生成诊断报告」才出内容，进页面只有空态——对秒级查询来说是错误的交互模型。
- 三个既有缺陷：
  1. **模块级章节恒空**：`collectModuleChapter` 中 `collectCycles(b, "module", ...)` 只写 `issues`，从不写 `chapters.module.cycles`；前端读 `moduleChapter?.cycles` 恒为 `[]`。`chapter.put("dsm", ...)` 有数据但前端不渲染。
  2. **切换项目报告残留**：select 的 `@change` 会重置，但 `watch(appStore.selectedProjects)` 只同步名称不重置状态——在项目管理页切项目后返回，旧报告仍在。
  3. **类级/方法级只有纯表格**：不直观。现成组件 `HotspotTreemap.vue`（treemap）、`DsmMatrix.vue`（DSM 热力矩阵）可直接复用。
- 通用 agent 流基础设施已就绪：`DagNode`（`name()/agentId()/execute(Map)`）+ `DagExecutor`（线性 DAG、输入指纹 checkpoint 缓存、HITL gate）+ `WorkflowRegistry` + `WorkflowAutoConfig`（注册工作流 = 注入 bean 一行）+ `WorkflowController`（`/api/workflow/*`，SSE 端点轮询 `agent_event` 表增量推送）。**但 `WorkflowController POST /api/workflow/start` 只有 Javadoc 无实现**，可用实现在 `RamController`（经 `RequirementAnalysisOrchestrator.start`）。
- LLM 接入已有模式：`DeepseekJsonClient.chatJson(systemPrompt, userPrompt, Class<T>)`（OpenAI 兼容 json_object 强约束，失败返回 null），配合 `SchemaValidator` + `classpath:schemas/ram/*.json` 的 JSON 契约。
- 前端消费基建已就绪：`api/workflow.ts`（`startWorkflow` / `workflowStreamUrl` EventSource）、`composables/useWorkflow.ts`（REST 权威 + SSE 增量）、StepCard 进度卡。

依赖约束：本变更依赖在途变更 `architecture-diagnosis-module`（引入 `architecture-diagnosis` 能力规范）先行落地归档。

## Goals / Non-Goals

**Goals:**

1. 实时统计报告：进页 / 切项目自动生成（`persist=false`），秒级出全量四章；「保存快照」显式按钮（`persist=true`）。
2. 修复模块级章节恒空（后端环数据写入 chapter + 前端渲染 DSM 与环列表）。
3. 修复切换项目报告残留（watch 项目变化即重置并重新生成）。
4. 类级/方法级可视化增强（复用 `DsmMatrix.vue` / `HotspotTreemap.vue` + 度量条形图）。
5. 新增 `arch-deep-analysis` 工作流：证据收集 → 根因分析（LLM）→ 架构现状报告（LLM），SSE 流式进度，生成项目级架构现状及问题分析报告。
6. 补齐 `WorkflowController POST /api/workflow/start`（通用启动路径，使任意工作流类型可从前端直接启动）。

**Non-Goals:**

- 不改 `DagExecutor` / `RequirementAnalysisOrchestrator` 既有执行逻辑（RAM 流保持原状）。
- 不做实时报告的自动定时刷新（仅进页 / 切项目 / 手动刷新三个时机）。
- 深度分析不做对话式追问（报告一次生成，问答留待后续变更）。
- 不新增外部依赖（LLM 复用 deepseek 网关，可视化复用 ECharts + 现有组件）。

## Decisions

### D1 — 实时报告复用现有接口 + `persist` 语义，而非新建端点

`generate(paths, language, persist)` 已支持 persist 参数。前端进页/切项目自动调用 `getReport(paths, { persist: false })`；「保存快照」按钮调用 `persist=true`。若现有 GET 端点未暴露 persist 查询参数，则在 `ArchitectureDiagnosisController` 补一个可选参数（`?persist=false` 默认值保持向后兼容）。

- 备选：新建 `/report/realtime` 端点 —— 否，同一确定性逻辑无需两个入口。
- 原型参考：`prototypes/01-realtime-view.svg`（顶部「实时生成」徽标 + 保存快照 + AI 深度分析三控件）。

### D2 — 模块级章节：后端补写 chapter，前端复用 DsmMatrix

后端 `collectModuleChapter` 增加与 issues 同源的 `chapter.module.cycles` 写入（结构：`level/message/nodes[]`）。前端模块级 tab 改为左 DSM（`DsmMatrix`，复用，传 projectPaths）右环列表的双栏布局；环卡片按 `CROSS_LAYER`（红）/`SAME_LAYER`（黄）着色，点击下钻类级。分层违规列表保留表格但来源改为 `chapter.module.layerViolations`。

- 备选：仅前端从 issues 里过滤出环 —— 否，issues 是扁平问题清单，缺 nodes[] 结构，且 chapter 语义本就该完整（见 `architecture-diagnosis` 能力规范的收紧条款）。

### D3 — 类级/方法级可视化：复用现成组件 + 轻量条形图

- 方法级：`HotspotTreemap.vue`（已存在，treemap 按风险分着色）替代纯表格，表格降级为折叠明细。
- 类级：上帝类/枢纽类用 ECharts 横向条形图（度量对比：WMC / 扇入扇出），组件内联实现（无现成组件，但与 DashboardPanel 同模式）。

### D4 — 状态重置：watch `appStore.selectedProjects` 深度变化

把重置逻辑（`report=null, snapshots=[], deepAnalysis=null`）统一放进 `watch(() => appStore.getSelectedProjectPaths(), ...)`，随后自动触发实时生成。select 的 `@change` 只更新 store（现状已如此），不再各自处理——单一重置入口，消除"项目管理页切项目回来残留"的路径。

### D5 — 深度分析走通用工作流引擎，三节点线性 DAG

`arch-deep-analysis` 工作流（原型 `prototypes/02-deep-analysis-progress.svg`）：

1. **证据收集节点**（确定性，无 LLM）：调用 `ArchDiagnosisReportService.generate(persist=false)` 取四章 + issues；再经 KG 只读查询补充证据（关键类 ego-net、违规边代码位置）。产出 `evidencePack`（Map：chapters/issues/kpi/摘录），写 CHECKPOINT。
2. **根因分析节点**（LLM）：`DeepseekJsonClient` 输入 evidencePack 摘要，输出 JSON 契约 `schemas/arch/root-cause-analysis.json`（`issues: [{ref, title, severity, rootCause, evidenceRefs[], suggestion}]`），SchemaValidator 校验，失败重试一次后降级（保留证据包 + 标记根因分析失败）。
3. **架构现状报告节点**（LLM）：输入 evidencePack + 根因结果，输出 Markdown 文本（非 JSON），生成「现状总览 → 核心问题及根因 → 改进优先级 → 证据附录」四段式报告。结果持久化到 SQLite（`agent_session` 存会话，深度分析报告本体存 `agent_session` 的输出列或新增 `arch_deep_report` 表，实现时按现有表结构取最小改动）。

注册：`WorkflowAutoConfig` 增加三个 `@Bean DagNode` + registry 注册行，与 demand/status 同模式。进度经现有 `agent_event` + SSE 端点流出，节点粒度事件（开始/完成/LLM delta）。

- 备选：为架构诊断单写一套 orchestrator —— 否，违背"基于现有核心 agent 流方案做通用扩展"的要求。
- 备选：根因与报告合并为单节点 —— 否，根因 JSON 可校验可降级，报告 Markdown 不可；分开使报告节点可独立重跑。

### D6 — 补齐 `POST /api/workflow/start`（通用，非诊断专用）

从 `RamController` 的启动路径移植：入参 `{type, input}` → `WorkflowRegistry` 查工作流 → 创建 `agent_session` → 启动异步执行 → 返回 `{sessionId}`。放在 `WorkflowController`，与既有 SSE 端点同处。RAM 的 orchestrator 后续可收敛到这条通用路径（本次不动，避免回归）。校验：未知 type 返回 400；同 type 已有运行中会话时按现有 RAM 语义（新建会话，前端自行管理列表）。

### D7 — 前端深度分析面板：useWorkflow + StepCard + Markdown 渲染

`ArchitectureDiagnosisView` 顶部新增「AI 深度分析」按钮（原型 01）；点击 → `startWorkflow('arch-deep-analysis', {projectPaths})` → `useWorkflow` 订阅 SSE → StepCard 三节点进度（原型 02）。完成后新增「AI 深度分析」tab（与实时报告并列），Markdown 渲染报告 + 导出按钮（原型 03）。报告随项目切换重置（D4）。

- 备选：单独路由页 —— 否，报告与实时数据强关联（引用同一份证据包），同页 tab 切换符合用户心智。

## Risks / Trade-offs

- [LLM 输出违反 JSON 契约] → SchemaValidator + 一次重试 + 降级路径（证据包仍可看，根因区域明确标注失败与重试入口）。
- [深度分析分钟级，用户关闭页面丢失进度] → agent 流后台执行 + 会话可恢复（SSE lastSeq 水位增量拉取），回页自动恢复进度（原型 02 底部提示）。
- [报告节点输出长 Markdown 超时/截断] → 节点内设置 LLM max_tokens 上限与超时；截断时报告尾部标注"生成不完整，可重跑本节点"。
- [通用 /start 端点被滥用触发任意工作流] → 仅注册表中已注册的类型可用（白名单天然成立）；与 RAM 现状一致，不做额外鉴权（内网工具）。
- [实时自动生成在 KG 未构建时报错] → 保持现有错误路径，空态文案提示"请先构建图谱"，不静默重试。
- [依赖在途变更 architecture-diagnosis-module] → 本变更 tasks 中显式以"前置：该变更已归档"为第一个验证点；规范 delta 基于其落地后的 spec。

## Migration Plan

1. 后端：`collectModuleChapter` 补写 cycles → 报告接口 persist 参数 → `POST /workflow/start` → `arch-deep-analysis` 三节点 + 注册 + schema JSON → SQLite 建表（`SQLiteSchemaInitializer` 追加）。
2. 前端：`api/architectureDiagnosis.ts` persist 参数 → 视图改造（自动生成 + 重置 + 可视化组件 + 深度分析面板）。
3. 回归：后端 `mvn test` + 前端 `npm run build`；手动验证原型 01→02→03 全流程。
4. 回滚：纯增量改动（新节点/新表/新端点实现），revert 即可；`arch_diagnosis_snapshot` 与深度分析表数据可保留不清理。

## Open Questions

- 深度分析报告持久化位置：`agent_session` 输出列 vs 新表 —— 实现 tasks 阶段查看现有表结构后取最小改动，不阻塞设计。
- 六个已无引用的可视化组件（HotspotTreemap 等）随本变更部分复用，其余是否删除由用户决定（不阻塞本变更）。
