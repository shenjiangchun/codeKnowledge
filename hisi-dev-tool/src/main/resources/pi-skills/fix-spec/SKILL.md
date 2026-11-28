---
name: fix-spec
description: 技术债自动修复的 Spec 阶段：把已确认的方案写成目标、验收标准与测试用例。任务要求生成修复 Spec 时使用。
---

# 修复 Spec 生成

输入是已确认的修复方案与待办证据。

## 要求

- 目标：一句话说清修什么、为什么。
- 验收标准：可检验（改了哪个行为、哪个输入产生哪个输出）。
- 测试用例：**在 Spec 阶段就定死**，每条给类型（unit/api/e2e）、名称、落点（类/接口）、通过标准；这是后续开发与回归的验收依据，不得在后续阶段放宽。
- 非目标：明确列出不做什么（防止修复蔓延）。
- 涉及接口时给出 HTTP 方法、路径、参数与返回的现状。

## OpenSpec 输出

本阶段的产出会由后端确定性转换为 OpenSpec 变更目录里的两个文件（落到 worktree 的
`openspec/changes/<id>/`）：`proposal.md`（为什么改/改什么/影响面）与
`specs/<capability>/spec.md`（每条验收标准一个 Requirement，每条测试用例一个 Scenario，
WHEN/THEN 要点）。JSON 契约是本阶段唯一交付，不要自己写 Markdown。
