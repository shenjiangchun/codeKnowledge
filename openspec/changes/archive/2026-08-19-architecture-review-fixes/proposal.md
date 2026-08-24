# 架构评审修复（P0）

## Why

四位资深架构师对项目做了一次全面评审，一致指出：数据管道质量高（8 分），但存在 2 个功能性 bug 导致功能静默失效、1 个架构师最需要的杀手级能力（分层规则引擎）被写死为 0、以及 fixengine 缺一道人工闸门（未验证的修复直接自动 commit）。这些是「投入产出比最高」的 P0 修复，数据已全部躺在 Neo4j 里，只需把「有但没兑现」的能力真正兑现。

## What Changes

- **修复 ServiceNode 唯一约束 bug**：`Neo4jInitializer` 里 `REQUIRE s.name IS UNIQUE`，但 ServiceNode 的 @Id 是 serviceId、根本没有 name 字段，约束建在不存在属性上。改为 `REQUIRE s.serviceId IS UNIQUE`。
- **修复 SqlNode 向量索引大小写 bug**：`FOR (s:SQL)` 但 SqlNode 是 `@Node("Sql")`，Neo4j 标签大小写敏感，导致 SQL 语义检索的向量索引实际索引了空标签。改为 `FOR (s:Sql)`。
- **分层规则引擎**：`layeredViolations` 从写死的 0 变成真违规清单。用现成的 `layerRole` + `DEPENDS_ON` 边，定义 Spring 分层偏序（controller→service→repository→model→util），枚举所有反向/跨层依赖作为违规，填入 dashboard risks。
- **非 Java/Spring 项目门控**：分层规则引擎按 `layerRole` 非 UNKNOWN 占比门控，占比 <30% 时跳过分层检测、显示「非分层架构，不适用」，避免对 Python/Go/Node 项目误报。
- **fixengine HITL 闸门**：修复未通过测试时，停在 worktree 不 commit，把 diff + 测试结果推给人，人确认后才提交。

## Capabilities

### New Capabilities

- `neo4j-index-fixes`: 修复 Neo4jInitializer 的两个功能性 bug（ServiceNode 约束字段 + SqlNode 向量索引标签）。
- `layered-rule-engine`: 分层规则引擎（Spring 分层偏序 + 违规清单 + 非 Spring 门控）。
- `fix-hitl-gate`: fixengine 的人工闸门（HITL gate）。

### Modified Capabilities

（无。现有 openspec/specs/ 下无相关能力。）

## Impact

- **后端**：
  - `Neo4jInitializer.java`：ServiceNode 约束字段修正 + SqlNode 向量索引标签修正。
  - `KnowledgeGraphController.java`：`/dashboard` 的 `layeredViolations` 从 0 改为真违规计算。
  - `ModuleStatsAggregator.java`：layerRole 已算好，分层规则引擎读取即可。
  - `fixengine/service/FixFlowRunner.java`（及关联）：commit 前加 HITL 闸门。
- **前端**：
  - `DashboardPanel.vue`：分层违规清单卡片（承接 risks）。
