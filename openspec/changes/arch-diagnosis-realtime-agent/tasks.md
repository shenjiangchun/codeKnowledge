## 1. 前置确认

- [x] 1.1 确认在途变更 `architecture-diagnosis-module` 已实现并归档（其 spec 落入 openspec/specs/architecture-diagnosis/）；若未归档，本变更所有编码任务阻塞
- [x] 1.2 复核 `ArchDiagnosisReportService.generate` 的 persist 参数与 `ArchitectureDiagnosisController` 当前签名，确定 persist 查询参数的接入点

## 1b. 分层推断准确性修正（用户并入）

- [x] 1b.1 规则后缀复数修正：`ClassLayerRoleDetector.detectBySuffix` 支持 `*Utils/*Constants/*Helpers/*Dtos` 等复数形式（类名与包名两路），`ModuleStatsAggregator.updateLayerRole` CASE 同步补 `utils/constants`；编写测试：`CodeAnalysisUtils`→UTILITY(NAME)、包名 `xx.utils`→UTILITY(PACKAGE)
- [x] 1b.2 LLM 包位置偏差修正：`LayerRoleLlmService.SYSTEM_PROMPT` 增加「按类自身职责判断，DTO/record/请求响应对象归 MODEL，不因所在 service 包归 SERVICE」；编写测试（mock LLM 断言 prompt 内容包含该约束）
- [x] 1b.3 DATA 脏标签清理：启动时（`Neo4jInitializer`）或迁移逻辑把 `ModuleNode.layerRole='DATA'`/'MAPPER' 重算为现行枚举（DATA→MODEL、MAPPER→REPOSITORY）；编写测试：迁移后无越界标签

## 2. 后端：报告端点 persist 语义 + 模块级章节修复

- [x] 2.1 `ArchitectureDiagnosisController` 报告端点增加可选 `persist` 参数（默认 true 向后兼容），透传至 `generate(paths, language, persist)`；编写测试：persist=false 不落快照、缺省等价 persist=true
- [x] 2.2 `ArchDiagnosisReportService.collectModuleChapter` 将包级环数据写入 `chapters.module.cycles`（每项含 level/message/nodes），与 issues 同源；编写测试：有环时章节含 N 条环、无环时 cycles=[] 且键存在

## 3. 后端：通用工作流启动端点

- [x] 3.1 在 `WorkflowController` 实现 `POST /api/workflow/start`：从 `RamController` 移植启动路径（Registry 校验 type → 创建 agent_session → 异步执行 → 返回 sessionId）；编写测试：合法 type 返回会话标识、未知 type 返回 400、缺 projectPaths 返回 400

## 4. 后端：arch-deep-analysis 工作流

- [x] 4.1 新建 `workflow/nodes/ArchEvidenceCollectNode`（证据收集 DagNode）：调 `generate(persist=false)` 取四章+issues，KG 只读查询补证据（关键类 ego-net、违规代码位置），产出 evidencePack 并写 CHECKPOINT；编写测试：证据包结构完整、KG 为空时降级不抛异常
- [x] 4.2 新建 `schemas/arch/root-cause-analysis.json` 契约文件与 `workflow/nodes/ArchRootCauseNode`（根因分析 DagNode）：`DeepseekJsonClient` 调用 + SchemaValidator 校验 + 失败重试一次后降级标记；编写测试（mock LLM）：契约通过、两次失败降级不阻塞
- [x] 4.3 新建 `workflow/nodes/ArchStatusReportNode`（架构现状报告 DagNode）：输入 evidencePack+根因结果，LLM 生成四段式 Markdown，持久化到会话存储（按现有 agent_session 表结构取最小改动）；编写测试：四段标题齐全、持久化可回读
- [x] 4.4 `WorkflowAutoConfig` 注册 `arch-deep-analysis`（三个节点 bean + 注册行），SSE 事件含节点开始/完成/LLM delta；编写测试：注册表可查到该类型、启动后事件序列符合 节点1→2→3 顺序
- [x] 4.5 报告查询端点：前端可按会话取深度分析 Markdown（复用 agent_session 查询或最小新端点）；编写测试：完成后查询返回报告内容

## 5. 前端：实时报告 + 状态重置

- [x] 5.1 `api/architectureDiagnosis.ts`：`getReport` 增加 `persist` 参数（默认 false），类型同步
- [x] 5.2 `ArchitectureDiagnosisView.vue`：移除「生成诊断报告」按钮；进页（已选项目）与项目切换自动生成实时报告；控件改为「实时生成」徽标 + 「保存快照」按钮（persist=true 后刷新快照列表）
- [x] 5.3 统一重置入口：`watch(appStore 选中项目路径)` 重置 report/snapshots/深度分析状态并自动重新生成；删除 select @change 中的独立重置逻辑；验证跨页面切换无残留

## 6. 前端：四章可视化增强

- [x] 6.1 模块级 tab：左 `DsmMatrix`（传 projectPaths）右环列表双栏；环按 CROSS_LAYER(danger)/SAME_LAYER(warning) 着色；数据源改为 `chapters.module.cycles` 与 `chapters.module.layerViolations`
- [x] 6.2 方法级 tab：按 riskScore 着色 treemap 默认视图（数据源 `chapters.method.hotspots` 自绘，不复用文件级 HotspotTreemap.vue），明细表格折叠
- [x] 6.3 类级 tab：上帝类/枢纽类横向条形图（ECharts，WMC 与扇入扇出对比），明细折叠

## 7. 前端：AI 深度分析

- [x] 7.1 顶部新增「✦ AI 深度分析」按钮：`startWorkflow('arch-deep-analysis', {projectPaths})` + `useWorkflow` 订阅 SSE；自绘三节点进度卡（待执行/运行中/完成 + 当前节点提示）
- [x] 7.2 离开页面恢复：回页时按 localStorage 持久化的 sessionId（按 projectPaths 键控）rejoin，增量恢复进度；已完成直接展示报告
- [x] 7.3 「AI 深度分析」tab：与实时报告并列，Markdown 渲染四段式报告 + 导出按钮；根因降级时显示失败说明与重试入口
- [x] 7.4 报告随项目切换重置（挂在 5.3 的统一重置入口，`:key` 绑定 projectPaths 键）

## 8. 回归与收尾

- [x] 8.1 后端 `mvn test` 全量回归通过（Tests run: 1287, Failures: 0, Errors: 0）
- [x] 8.2 前端 `npm run build` 通过（无 TS 错误、无孤儿 import）
- [ ] 8.3 手动全流程验证（对照 prototypes/01→02→03）：进页自动出报告 → 保存快照 → 切项目重置 → 发起深度分析看进度 → 查看报告导出
- [ ] 8.4 `openspec validate` 通过并归档本变更
