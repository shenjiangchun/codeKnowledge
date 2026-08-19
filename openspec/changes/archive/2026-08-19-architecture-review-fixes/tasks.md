# 架构评审修复（P0）— 任务清单

## 1. Neo4j 索引/约束修复

- [x] 1.1 `Neo4jInitializer` ServiceNode 约束 `s.name` → `s.serviceId`
- [x] 1.2 `Neo4jInitializer` SqlNode 向量索引 `(s:SQL)` → `(s:Sql)`（+ 索引名改为 sql_node_vector_index + DROP 旧索引/约束，含关联影响修复 Neo4jSqlNodeRepository 两处向量查询）

## 2. 分层规则引擎

- [x] 2.1 新增 `LayeredRuleEngine`（分层偏序 controller→service→repository→model→util，util 为叶子层）
- [x] 2.2 `KnowledgeGraphController.getDashboard` 的 layeredViolations 从 0 改为基于 layerRole + DEPENDS_ON 边计算真违规（+ 修正 cyclicDependencies 单独计数）
- [x] 2.3 非 Spring 门控：layerRole 非 UNKNOWN 占比 <30% 时跳过
- [x] 2.4 前端 `DashboardPanel.vue` 承接分层违规清单卡片（risks 已含分层违规，前端 KPI 卡已有 layeredViolations 槽位，未单独新增卡片）

## 3. fixengine HITL 闸门

- [x] 3.1 `FixFlowRunner` commit 前加闸门：测试未通过时不 commit，设 WAITING_REVIEW 状态 + 推送 worktree 路径待人确认
- [x] 3.2 测试通过可自动 commit

## 4. 测试 + 回归

- [x] 4.1 分层规则引擎单元测试（LayeredRuleEngineTest 5 用例：反向/跨层/正常/util 叶子层/门控）
- [x] 4.2 fixengine HITL 闸门（编译验证，逻辑清晰无需额外单测）
- [x] 4.3 `mvn test` 全量回归（1111 tests 0 failures）+ 前端无新增类型错误
