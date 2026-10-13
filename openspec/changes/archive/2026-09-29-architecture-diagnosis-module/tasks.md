# Tasks: architecture-diagnosis-module

> 依赖顺序：后端数据层 → 后端端点 → 后端测试 → 前端类型/API → 前端页面/路由 → 移除 KG 页架构 tab → 回归。

## 1. 后端数据层（快照表 + 分层配置）

- [ ] 1.1 在 `SQLiteSchemaInitializer.initialize()` 追加 `CREATE TABLE IF NOT EXISTS architecture_diagnosis_snapshot (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, health_score INTEGER, grade TEXT, high_count INTEGER, medium_count INTEGER, low_count INTEGER, report_json TEXT, created_at TEXT DEFAULT (datetime('now','localtime')))` 及 scope 索引
- [ ] 1.2 新增 `ArchDiagnosisSnapshot` 存储组件（`save(scope, report)` / `listByScope(scope, limit)` / `findById(id)`），构造注入 SQLite 连接（复用现有 SQLite 访问方式）

## 2. 后端端点（ArchitectureDiagnosisController）

- [ ] 2.1 新增 `ArchitectureDiagnosisController`，`@RequestMapping("/api/architecture-diagnosis")`，构造函数注入 `KnowledgeGraphController`（v1）与 `Driver`/`SessionConfig`/`ProjectPathResolver`/快照存储
- [ ] 2.2 实现扣分映射常量（HIGH -10 / MEDIUM -5 / LOW -2、每维度封顶 25、等级 A≥90/B≥75/C≥60/D<60）与 severity 统一映射表（CROSS_LAYER 循环+领域循环→HIGH；分层违规+领域依赖→MEDIUM；类级分层违规疑似+同层循环→MEDIUM；热点→LOW/MEDIUM）
- [ ] 2.3 实现 `GET /api/architecture-diagnosis/report`（`projectPaths` 必填 + 可选 `language`，同步一次返回）：组合 V1 端点数据，按 D5 扣分制算 `healthScore`/`grade`/`deductions`/`issues`，组装 `chapters`（project/module/class/method），成功后按项目各落一条快照（scope=单 projectPath）
- [ ] 2.4 实现方法级热点聚合（D3，只读 Cypher join ChurnNode；非 Java 项目 churn 缺失按 0 计），输出 `nodeId`/`className`/`methodName`/`filePath`/`complexity`/`riskScore` 降序
- [ ] 2.5 实现类级度量排行（D4，上帝类 WMC/方法数 + 枢纽类扇入扇出，只读 Cypher，非 Java 项目用复杂度+扇入扇出兜底）
- [ ] 2.6 实现 `GET /api/architecture-diagnosis/snapshots`（历史查询，返回 scope 下的健康总分 + 高/中/低计数趋势，不回传 report_json）
- [ ] 2.7 实现报告导出端点（`GET /api/architecture-diagnosis/report/export`：`projectPaths` 即时生成或 `snapshotId` 还原，`text/markdown`，含健康分/等级/问题清单/证据链）
- [ ] 2.8 报告响应内携带权威 `layerOrder` 数组（D7，源于 `ClassLayerRoleDetector.LAYER_ORDER`）

## 3. 修复爆炸半径 suggestedTests（改 V1）

- [ ] 3.1 修改 `KnowledgeGraphController.getBlastRadius`：`suggestedTests` 从 `List.of()` 改为基于 `affectedEntryPoints`/受影响方法推导的建议列表；无受影响点时仍返回空数组

## 4. 后端测试

- [ ] 4.1 新增 `ArchitectureDiagnosisControllerTest`（`@ExtendWith(MockitoExtension.class)`，`@Mock` repos/Driver，`@InjectMocks`），覆盖：报告端点分数计算与等级边界、扣分封顶、severity 映射、空数据返回 100 分、快照自动落库断言
- [ ] 4.2 方法级热点聚合 / 类级度量排行单测（Mock `Session.run` 返回记录）
- [ ] 4.3 `BlastRadiusTest` 补/改用例断言 `suggestedTests` 非空与空两种情况
- [ ] 4.4 运行 `mvn -pl hisi-dev-tool test` 全绿，零回归

## 5. 前端类型与 API 模块

- [ ] 5.1 新增 `types/architectureDiagnosis.ts`：`DiagnosisReport`/`HealthScore`/`Deduction`/`Issue`/`MethodHotspot`/`ClassMetric`/`Snapshot` 等类型
- [ ] 5.2 新增 `api/architectureDiagnosis.ts`：`getReport(projectPaths, language)` / `getSnapshots(scope)` / `exportReport(params: {projectPaths?} | {snapshotId})`（均走 `@/utils/request` GET）

## 6. 前端页面与路由

- [ ] 6.1 新增 `views/architecture-diagnosis/ArchitectureDiagnosisView.vue`：诊断总览（健康分卡 + ABCD + KPI + 按 severity 问题清单）+ 四章锚点导航（项目级/模块级/类级/方法级，各章内嵌可视化）+ 快照历史（总分折线 + 高/中/低趋势）+ 导出按钮
- [ ] 6.2 `DashboardPanel.vue` 的 `LAYER_BIAS` 改为读报告端点返回的 `layerOrder`（删除组件内硬编码）
- [ ] 6.3 `router/index.ts` 新增 `/architecture` 路由 + 守卫（复用缺项目菜单重定向 `/project` 逻辑）
- [ ] 6.4 `stores/app.ts` `availableMenus` 加 `'architecture-diagnosis': projectSelected.value` 键
- [ ] 6.5 `AppSidebar.vue` `MenuKey` 加 `architecture-diagnosis`，`baseMenuItems` 在「知识图谱」后加「架构诊断」一级菜单项（含图鉴 icon + `menuKey`）

## 7. 移除知识图谱页 7 个架构 tab

- [ ] 7.1 从 `KnowledgeGraphView.vue` 删除 7 个架构 tab-pane（dashboard/hotspots/domains/serviceTopo/buildModule/blastRadius/frontendBackend）及对应 import 与 activeTab 引用；保留 understand/semanticSearch/methodRef/crossService/explorer/generationCenter

## 8. 回归与收尾

- [ ] 8.1 后端 `mvn -pl hisi-dev-tool test` 全绿；前端 `npm run build` 通过
- [ ] 8.2 手动/agent-browser 验证：进入架构诊断页 → 生成报告 → 查看四章 → 查看快照历史 → 导出 Markdown
- [ ] 8.3 `openspec validate --change architecture-diagnosis-module` 通过后按项目 git 规范提交（feat 前缀 + Co-Authored-By）