---
name: tech-plan
description: 需求分析最后的技术方案阶段：把影响面与实现方案整理成可评审的技术方案（待改方法明细、时序/流程图、测试范围）。任务要求生成技术方案时使用。
---

# 技术方案生成

输入是意图、影响面（methods_to_modify / affected_entries / risk）与实现方案（biz_plan / api_changes）。

## 要求

- `target_methods_detail`：每个要改的方法给出类名、方法名、nodeId、改法与影响，**从输入的影响面里取，不要漏掉也不要臆造**。
- `sequence_diagrams` / `flow_diagrams`：用输入里的调用链描述关键流程，节点用真实类/方法名。
- `test_scope`：unit_tests（类级用例清单）、integration_tests（接口/链路）、data_migration（有数据变更时）。
- `risk_mitigations`：每个风险给缓解措施。
- 输入里的信息不够时先说明缺什么，不要编。

输出按契约 schema（tech_plan.output）的 JSON。
