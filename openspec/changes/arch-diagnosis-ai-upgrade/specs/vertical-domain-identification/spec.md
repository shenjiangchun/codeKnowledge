# vertical-domain-identification Specification

## ADDED Requirements

### Requirement: 垂直领域识别
系统 SHALL 通过"命名领域词提取 + 分层角色贯穿聚合"从真实代码识别业务领域：读取每个类的 `classRole` 与 `className`，剥离技术后缀得到领域词，将同一领域词在多个分层角色出现的类聚合为一个领域（垂直贯穿），而非按包边界或调用耦合切分。

#### Scenario: 识别垂直贯穿领域
- **WHEN** 某项目存在 `OrderController`(CONTROLLER)、`OrderServiceImpl`(SERVICE)、`OrderRepository`(REPOSITORY)、`OrderEntity`(MODEL)
- **THEN** 系统识别出一个 `Order` 领域，成员含上述跨层类，而非按包或调用耦合切分

#### Scenario: 领域跨越包边界
- **WHEN** 领域成员类分布在 controller 包、service 包、repo 包等不同包
- **THEN** 领域识别仍按命名词 + 分层角色贯穿聚合，跨越包边界

### Requirement: 领域骨架判定
系统 SHALL 仅将"含至少一个业务角色（SERVICE/CONTROLLER/REPOSITORY）"的领域词聚合判为完整领域；纯 MODEL 孤立词（只有数据对象、无业务类）不判为领域，归为孤立数据对象。

#### Scenario: 纯数据对象不入域
- **WHEN** 某领域词只出现在 MODEL 类（如仅 `OrderEntity`，无 `OrderService`/`OrderController`）
- **THEN** 系统不将其判为领域，标记为孤立数据对象

#### Scenario: 含业务角色的领域成立
- **WHEN** 某领域词覆盖 SERVICE 或 CONTROLLER 或 REPOSITORY 至少一个角色
- **THEN** 系统判其为完整领域

### Requirement: LLM 消歧与命名（不猜边界）
系统 SHALL 仅在领域边界已确定性聚合后，用 LLM 做领域命名与命名消歧（复合词拆分、缩写还原、中文名）。LLM 不参与领域边界划分。

#### Scenario: LLM 只命名不改边界
- **WHEN** 垂直聚合已得出某领域成员集合
- **THEN** LLM 只为其生成领域名与消歧结果，不增删成员

#### Scenario: 命名不可用时的回退
- **WHEN** LLM 命名失败或不可用
- **THEN** 领域名回退为领域词原文（如 `Order`），不影响领域边界识别

#### Scenario: 命名结果缓存复用
- **WHEN** 领域集合未变化且再次生成诊断报告
- **THEN** 领域名复用缓存，不重复调用 LLM；点击"实时生成/刷新"时清缓存并重算命名

### Requirement: 诊断侧纯读不碰生成侧
系统 SHALL 在诊断侧纯读 Neo4j 已有的 `classRole`/`className` 计算垂直领域，不写回、不修改生成侧 aggregation pipeline（含 `MultiDimensionCommunityDetector`、`DomainNameGenerator`）。

#### Scenario: 领域识别不写回生成侧
- **WHEN** 诊断侧执行垂直领域识别
- **THEN** 只读取已有 ClassNode 数据，不向 Neo4j 写回领域节点/边，不触发生成侧重跑
