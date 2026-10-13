## Why

架构分析能力目前埋在「知识图谱」页第 7~12 个 tab，与语义搜索混排；12 个 tab 各自独立 KPI，无健康度总评、无跨层面问题清单、无历史趋势、无报告导出，方法级风险分（`MethodNode.riskScore`）在热点页只退化为文件级展示，爆炸半径 `suggestedTests` 恒空。真实架构师（C4 + ArchUnit + 演进式适应度函数方法论）要的是「诊断报告」而非「工具箱」——他们的第一个问题是「这个项目架构健康吗？最该先修哪三个问题？」。将架构诊断拆出一级模块、以报告为核心重构，是把一项能力从「换个地方放工具」升级为「杀手锏第一步」。

## What Changes

- **新增一级入口「架构诊断」**：从知识图谱模块移出全部 7 个架构 tab，重构为**报告优先 + 四章锚点导航**的独立页面（项目级 → 模块级 → 类级 → 方法级）+ 诊断总览 + 快照历史。
- **新增后端报告聚合端点** `GET /api/architecture-diagnosis/report`：聚合现有 V1 端点数据（仪表盘/热点/领域/拓扑/DSM/环/分层违规/类依赖/爆炸半径），输出**扣分制健康总分（ABCD 四级）+ 按严重度排序的跨层问题清单 + 各章可视化数据**。固定扣分 HIGH -10 / MEDIUM -5 / LOW -2，每维度封顶 25 分，`A≥90 / B≥75 / C≥60 / D<60`，扣分明细随报告返回。
- **新增方法级热点聚合端点**：把 `MethodNode` 的方法级风险（圈复杂度 × 变更频率）聚合呈现（treemap + 风险分排行），补齐方法级诊断哑火。
- **新增类级度量排行端点**：上帝类（类内方法复杂度总和 WMC 高 + 方法数多）/ 枢纽类（扇入扇出双高）检测 + 度量排行，配合现有类依赖图下钻。
- **修复爆炸半径 `suggestedTests`**：从恒空 `List.of()` 改为返回基于受影响方法/入口点推导的真实测试建议。
- **新增诊断报告导出**：报告生成后支持导出（Markdown / 文本快照下载）。
- **新增快照历史**：报告生成成功自动落一条 `ArchDiagnosisSnapshot`（SQLite 新表），历史页展示健康总分折线 + 高/中/低问题计数趋势。
- **分层规则配置化**：`LAYER_BIAS` 从前端 `DashboardPanel` 硬编码抽到后端统一常量/服务，报告与前端共用同一层序。
- 移除 `KnowledgeGraphView.vue` 中 7 个架构 tab-pane 及其 import/activeTab 引用。

## Capabilities

### New Capabilities

- `architecture-diagnosis`: 架构诊断报告聚合端点（健康评分 + 跨层问题清单）、方法级热点聚合、类级上帝/枢纽检测与度量排行、快照持久化与历史查询、报告导出、分层规则统一配置。

### Modified Capabilities

- `api-endpoints`: 「爆炸半径 API」的 `suggestedTests` 由恒空改为返回真实测试建议列表（- 影响 spec 中该字段语义）。

## Impact

- **后端新增**：`ArchitectureDiagnosisController`（GET 报告/导出/方法级热点/类级度量/快照历史），`ArchDiagnosisSnapshot` 存储，`SQLiteSchemaInitializer` 加 `architecture_diagnosis_snapshot` 表；`KnowledgeGraphController.getBlastRadius` 补 `suggestedTests`。
- **前端新增**：`/architecture` 路由 + 诊断总览/四章/快照历史页面 + 侧边栏菜单项（`MenuKey` 加 `architecture-diagnosis`）+ `availableMenus` 键 + 路由守卫；`api/architectureDiagnosis.ts`。
- **前端修改**：`KnowledgeGraphView.vue` 删除 7 个架构 tab（组件文件本身保留，供诊断页复用/替换）；`DashboardPanel.vue` 的 `LAYER_BIAS` 改为读后端配置。
- **无 DB 迁移风险**：新表 `CREATE TABLE IF NOT EXISTS`，快照为增量追加，不影响现有聚合管线。
- **鉴权**：所有新读端点均为 GET（`AdminOnlyInterceptor` 对非 `/api/users/**` 的 GET 放行），无新增写端点暴露给普通用户。