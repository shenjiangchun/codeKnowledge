# architecture-diagnosis Delta

> 说明：`architecture-diagnosis` 能力规范由在途变更 `architecture-diagnosis-module` 引入（尚未归档到 openspec/specs）。本 delta 基于该变更 spec 的现状文本修改；归档顺序 MUST 先归档 `architecture-diagnosis-module`，再归档本变更。

## ADDED Requirements

### Requirement: 模块级章节完整性
报告的模块级章节 MUST 包含完整环数据：`chapters.module.cycles`（每项含 `level`（CROSS_LAYER/SAME_LAYER）、`message`、参与包路径 `nodes` 数组）与 DSM 数据。环数据 SHALL 与问题清单同源同批生成，不允许只写 issues 不写章节。

#### Scenario: 环数据写入章节
- **WHEN** 报告生成且底层检测到 N 个包级循环依赖
- **THEN** `chapters.module.cycles` 含这 N 条环（含 level/message/nodes），与 issues 中对应问题一一同源

#### Scenario: 无环项目章节仍完整
- **WHEN** 项目无循环依赖
- **THEN** `chapters.module.cycles=[]`，章节结构完整（cycles 与 dsm 键存在），而非缺失键

### Requirement: 分层推断准确性
系统 SHALL 保证分层推断结果的准确性约束：职责后缀匹配 MUST 覆盖常见复数形式（`*Utils`/`*Constants`/`*Helpers` 等）；LLM 补全 MUST 在 prompt 中要求按类自身职责而非所在包名判断（DTO/record/请求响应对象归 MODEL）；包级层标签 MUST 限定在统一层序枚举内（CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN），历史遗留的越界标签（如 DATA）SHALL 被迁移或重算消除。

#### Scenario: 复数后缀规则命中
- **WHEN** 类名为 `CodeAnalysisUtils` 或包名末段为 `utils`
- **THEN** 三级回退规则直接判为 UTILITY（来源 NAME/PACKAGE），不再落入 LLM 补全

#### Scenario: LLM 不按包名归类 DTO
- **WHEN** LLM 补全一个位于 `service.*` 包下的纯数据类（如请求/响应对象、record）
- **THEN** 其职责按自身形态判为 MODEL，而非因包名归为 SERVICE

#### Scenario: 包级标签枚举一致
- **WHEN** 报告与前端读取包级 layerRole
- **THEN** 所有取值均在统一层序枚举内，不存在 DATA 等越界历史标签（旧值被迁移或重算为 MODEL）

## MODIFIED Requirements

### Requirement: 诊断报告聚合端点
系统 SHALL 提供 `GET /api/architecture-diagnosis/report` 端点，接受 `projectPaths`（必填，与 V2 约定一致）、可选 `language` 与可选 `persist`（默认 true 保持向后兼容），返回一份**诊断报告**：健康总分（0-100）、ABCD 等级、按严重度排序的跨层问题清单、各层级章节数据（项目级/模块级/类级/方法级）与扣分明细。`persist=false` 时报告仅为实时查询结果，MUST NOT 落快照。

#### Scenario: 生成完整的四层诊断报告
- **WHEN** GET `/api/architecture-diagnosis/report?projectPaths=hisi-dev-tool&language=java`，且各底层源数据（仪表盘/热点/领域/拓扑/DSM/环/分层违规/类依赖）均可用
- **THEN** 响应含 `healthScore`（0-100 整数）、`grade`（A/B/C/D）、`deductions`（扣分明细数组，每项含 `source`/`sourceLevel`/`severity`/`points`/`reason`）、`issues`（按 severity 降序的问题清单数组）、`chapters`（project/module/class/method 四个章节数据）

#### Scenario: 无数据项目返回空报告不报错
- **WHEN** 项目未构建过 KG 或聚合未运行（无 DomainNode/ModuleNode）
- **THEN** 返回 HTTP 200，`healthScore` 按「无问题」计（100 分），`issues=[]`，`chapters` 各章节为空集合，而非 5xx

#### Scenario: 鉴权放行
- **WHEN** 以普通用户（非 ADMIN）请求 GET 该端点
- **THEN** 请求放行（GET 不要求 ADMIN），无需管理员凭证

#### Scenario: persist=false 实时查询不落快照
- **WHEN** 以 `persist=false` 请求报告端点
- **THEN** 返回完整报告且快照表不新增记录；省略 persist 参数时行为与 persist=true 一致（落快照，兼容既有调用方）

### Requirement: 诊断快照持久化与历史查询
系统 SHALL 仅在报告生成请求显式持久化（`persist=true`）时落一条快照（SQLite 新表 `architecture_diagnosis_snapshot`），并提供历史查询端点，返回健康总分趋势与高/中/低问题计数趋势。前端 SHALL 通过显式「保存快照」按钮触发持久化，自动实时生成（persist=false）MUST NOT 产生快照记录。

#### Scenario: 显式保存落快照
- **WHEN** 报告端点以 `persist=true` 成功生成一份诊断报告，且请求含 N 个项目（`projectPaths`）
- **THEN** 为每个项目各落一条快照（scope = 单个 projectPath，而非组合键），每条含 `scope`、`healthScore`、`grade`、`highCount`/`mediumCount`/`lowCount`、`reportJson` 快照体、`createdAt`

#### Scenario: 实时查询不产生快照
- **WHEN** 报告端点以 `persist=false` 被调用（进页/切项目自动生成）
- **THEN** 不落任何快照记录，历史趋势不变

#### Scenario: 复选多项目快照按项目隔离
- **WHEN** 用户复选 A、B 两个项目做一次显式保存
- **THEN** 落两条快照（scope=A、scope=B 各一条），历史可按单项目维度查看每个项目各自的演进

#### Scenario: 历史趋势查询
- **WHEN** 请求快照历史端点（`scope` + 可选 `limit`）
- **THEN** 返回按时间正序的历史快照列表，前端可绘制健康总分折线 + 高/中/低问题计数趋势
