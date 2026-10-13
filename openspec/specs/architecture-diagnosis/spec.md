# architecture-diagnosis Specification

## Purpose
TBD - created by archiving change architecture-diagnosis-module. Update Purpose after archive.
## Requirements
### Requirement: 诊断报告聚合端点
系统 SHALL 提供 `GET /api/architecture-diagnosis/report` 端点，接受 `projectPaths`（必填，与 V2 约定一致）与可选 `language`，返回一份**诊断报告**：健康总分（0-100）、ABCD 等级、按严重度排序的跨层问题清单、各层级章节数据（项目级/模块级/类级/方法级）与扣分明细。

#### Scenario: 生成完整的四层诊断报告
- **WHEN** GET `/api/architecture-diagnosis/report?projectPaths=hisi-dev-tool&language=java`，且各底层源数据（仪表盘/热点/领域/拓扑/DSM/环/分层违规/类依赖）均可用
- **THEN** 响应含 `healthScore`（0-100 整数）、`grade`（A/B/C/D）、`deductions`（扣分明细数组，每项含 `source`/`sourceLevel`/`severity`/`points`/`reason`）、`issues`（按 severity 降序的问题清单数组）、`chapters`（project/module/class/method 四个章节数据）

#### Scenario: 无数据项目返回空报告不报错
- **WHEN** 项目未构建过 KG 或聚合未运行（无 DomainNode/ModuleNode）
- **THEN** 返回 HTTP 200，`healthScore` 按「无问题」计（100 分），`issues=[]`，`chapters` 各章节为空集合，而非 5xx

#### Scenario: 鉴权放行
- **WHEN** 以普通用户（非 ADMIN）请求 GET 该端点
- **THEN** 请求放行（GET 不要求 ADMIN），无需管理员凭证

### Requirement: 扣分制健康评分与 ABCD 分级
系统 SHALL 用固定扣分制计算健康总分：初始 100 分，每个问题按严重度扣分（HIGH -10 / MEDIUM -5 / LOW -2），每维度封顶扣除 25 分；等级映射 `A≥90 / B≥75 / C≥60 / D<60`。扣分明细 MUST 随报告返回，且每项保留 `sourceLevel` 原文与统一 `severity` 映射。

#### Scenario: 扣分与封顶
- **WHEN** 某维度（如模块级）存在 5 个 HIGH 问题（理论 -50），但该维度封顶 25
- **THEN** 该维度实际只扣 25 分，而非 -50

#### Scenario: severity 统一映射
- **WHEN** 各底层端点的严重度原文不同（如环检测用「HIGH」、分层违规用「MEDIUM」、热点无严重度）跨层问题聚合为统一 severity
- **THEN** 每个问题项的 `severity` 取统一枚举 `HIGH/MEDIUM/LOW`，`sourceLevel` 保留原文，扣分按统一 severity 执行

#### Scenario: 等级边界
- **WHEN** 健康总分分别为 90 / 75 / 60 / 59
- **THEN** 等级分别为 A / B / C / D

### Requirement: 方法级热点聚合
系统 SHALL 提供方法级热点聚合端点，基于 `MethodNode` 的方法级风险（圈复杂度 × 变更频率），返回按风险分降序的方法热点清单与 treemap 数据，而非退化为文件级 ChurnNode。

#### Scenario: 返回方法级热点
- **WHEN** 请求方法级热点端点（`projectPaths` + 可选 `limit`）
- **THEN** 返回方法列表，每项含 `nodeId`/`className`/`methodName`/`filePath`/`complexity`/`riskScore`，按 `riskScore` 降序

#### Scenario: 非 Java 项目 churn 缺失按 0 计
- **WHEN** 项目为 Python（无 ChurnNode 变更频率数据）
- **THEN** 方法级风险分中 churn 分项按 0 计，仍用圈复杂度 + 入度（扇入）+ 循环惩罚计算 riskScore，方法级章节照常可用

#### Scenario: 方法级与文件级并存区分
- **WHEN** 方法级热点与文件级热点（ChurnNode）同时存在于系统
- **THEN** 方法级端点只返回 Method 粒度条目，文件级端点只返回文件粒度条目，二者不混淆

### Requirement: 类级上帝类与枢纽类检测
系统 SHALL 提供类级度量排行端点：识别**上帝类**（类内方法复杂度总和 WMC 高 + 方法数多）与**枢纽类**（扇入 + 扇出双高），返回度量排行表，配合类依赖图下钻。

#### Scenario: 识别上帝类
- **WHEN** 某类包含 40+ 方法且方法复杂度总和（WMC）在全项目前 5%
- **THEN** 该类别入度量排行的 god-class 标记，含 `className`/`methodCount`/`wmc`/`avgComplexity`

#### Scenario: 识别枢纽类
- **WHEN** 某类同时被大量外部类依赖（高扇入）且依赖大量外部类（高扇出）
- **THEN** 该类别入度量排行的 hub-class 标记，含 `className`/`inDegree`/`outDegree`

### Requirement: 诊断快照持久化与历史查询
系统 SHALL 在报告端点成功生成后自动落一条快照（SQLite 新表 `architecture_diagnosis_snapshot`），并提供历史查询端点，返回健康总分趋势与高/中/低问题计数趋势。

#### Scenario: 报告生成自动落快照
- **WHEN** 报告端点成功生成一份诊断报告，且请求含 N 个项目（`projectPaths`）
- **THEN** 为每个项目各落一条快照（scope = 单个 projectPath，而非组合键），每条含 `scope`、`healthScore`、`grade`、`highCount`/`mediumCount`/`lowCount`、`reportJson` 快照体、`createdAt`

#### Scenario: 复选多项目快照按项目隔离
- **WHEN** 用户复选 A、B 两个项目做一次诊断
- **THEN** 落两条快照（scope=A、scope=B 各一条），历史可按单项目维度查看每个项目各自的演进

#### Scenario: 历史趋势查询
- **WHEN** 请求快照历史端点（`scope` + 可选 `limit`）
- **THEN** 返回按时间正序的历史快照列表，前端可绘制健康总分折线 + 高/中/低问题计数趋势

### Requirement: 诊断报告导出
系统 SHALL 提供报告导出能力：将诊断报告导出为 Markdown 文本（可下载/可归档），供架构师评审会汇报使用。

#### Scenario: 导出 Markdown 快照
- **WHEN** 请求导出端点，指定某次历史快照 `snapshotId` 或即时 `projectPaths`
- **THEN** 返回 Markdown 格式文本（含健康总分、等级、问题清单、证据链），响应头含可下载文件名

#### Scenario: 从历史快照导出
- **WHEN** 请求导出端点并提供某次历史快照 `snapshotId`
- **THEN** 从该快照的 `reportJson` 还原并导出 Markdown，内容与该次诊断生成时一致

#### Scenario: 单项目快照导出
- **WHEN** 快照 scope 为单项目（复选场景下每个项目各落一条）
- **THEN** 按 `snapshotId` 导出时只还原该项目那份快照的报告

### Requirement: 分层规则统一配置
系统 SHALL 将分层偏序（原前端 `LAYER_BIAS` 硬编码）抽为后端统一常量/可配置来源，报告与前端可视化共用同一层序，架构师对层序的调整一处生效。

#### Scenario: 单一层序来源
- **WHEN** 前端诊断页渲染分层架构图、后端报告计算分层违规
- **THEN** 二者从同一后端层序配置取值，避免前端硬编码与后端规则引擎不一致

#### Scenario: 非 Spring 项目回退默认层序
- **WHEN** 项目无 Spring 分层角色（classRole 均为 UNKNOWN）
- **THEN** 使用通用默认层序（CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY），UTILITY 为叶子层

