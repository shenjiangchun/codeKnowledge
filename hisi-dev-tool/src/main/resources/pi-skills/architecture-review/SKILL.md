---
name: architecture-review
description: 架构深度分析的查证方法。当你拿到一份架构诊断证据包（循环依赖、分层违规、上帝类、实现类外泄等候选问题），需要逐条判定真伪并产出根因、建议与路线图时使用。
---

# 架构问题查证

你的输入是一份「确定性检测」产生的证据包：候选问题已经带好定位信息（类名、方法名、nodeId、行号）。你的职责不是重新发现，而是**查证与判定**，每个问题给出 verdict。

## 判定规则

- `confirmed`：查证属实，保留原严重度。
- `false_positive`：误报（如调用方是测试类触发的「实现类外泄」），不予采纳。
- `downgraded`：属实但严重度要降（如「幽灵类」多为正常 DTO、枚举与启动类）。

## 硬性规则

- **抽样查证，不要逐条**：同一类型的多条候选先抽 1-2 条代表查证；代表都判 false_positive 且其余候选定位相似时，可整体判为该类误报；仅当代表为 confirmed 时才逐条区分其余。
- **impl-leak / 实现类外泄必须反向查询**：用 injection_scan 传被依赖的实现类全限定名。
  - total=0（items 空）→ 无人直接注入该实现类 → false_positive（接口注入）；
  - kind=interface → false_positive；
  - kind=class → confirmed。
  - injection_scan 拿不准时用 grep_source 查源码亲验：命中只出现在实现类自身定义 → false_positive；命中其他类的 @Autowired/@Resource/@Inject 或构造器参数 → confirmed。
- **工具返回 scannedFileCount=0 说明源码扫描没命中文件（项目路径可能不对），此时不得判 false_positive**，在 reason 里注明「无法核实」并保留原严重度。
- 输入经过上下文预算：过长的列表带 `_truncated` 标记（说明还有多少条没展示），`_contextNotice` 给出完整原文的保存路径；被截掉的部分不要当作不存在，必要时用 read 工具分段查看。
- 结论里只保留 confirmed 与 downgraded 的问题；false_positive 在报告中标明已被过滤。
- 每个问题的 rootCause 要说到技术根因（为什么形成），suggestion 要可执行（改什么、怎么改、先后顺序）。
