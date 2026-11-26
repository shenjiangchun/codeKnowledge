---
name: solution-design
description: 需求分析中的实现方案阶段：根据影响面（要改的方法、受影响入口）产出实现方案。任务要求给出 biz_plan、api_changes、state_machine_changes、data_changes 时使用。
---

# 实现方案设计

输入是澄清后的意图、验收标准、目标方法（target_methods，形如 `全限定类名#方法名`）与受影响的上游入口。

## 要求

- 每个要改的方法说清改法（签名怎么变、逻辑怎么改、异常怎么处理）。
- 接口变更（HTTP 参数/返回、状态机流转、表结构）逐项列出，并说明兼容性（旧调用方是否受影响）。
- 不要臆造：不确定的方法签名、接口定义先用工具查代码再写。
- 方案要能被「校验节点」逐项对照代码核实。

输出按契约 schema（implement.output）的 JSON。
