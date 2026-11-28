---
name: openspec-sdd
description: 技术债自动修复的 OpenSpec 变更目录约定：读取与回写 openspec/changes/<id>/ 下的 proposal.md、tasks.md 与 specs/<capability>/spec.md。任务要求在 worktree 内按 OpenSpec 变更实施修复时使用。
---

# OpenSpec 变更目录约定（自动修复流）

修复任务在 worktree 根目录下带一个 OpenSpec 变更目录：

```text
openspec/changes/<change-id>/
├── proposal.md                    # 为什么改、改什么、影响面
├── tasks.md                       # 实现清单（勾选进度）
└── specs/<capability>/spec.md     # 需求与场景增量
```

## 文件格式（业界 OpenSpec 约定）

**proposal.md**：`## Why` / `## What changes` / `## Impact` 三段。

**tasks.md**：`## Implementation` + 每条任务一个 `- [ ]` 勾选项，任务下缩进两格写 `- 文件：` / `- 验证：`。

**spec.md 增量**：`## ADDED Requirements`（修复类变更统一用 ADDED；MODIFIED/REMOVED/RENAMED 是 OpenSpec 的其它增量类型），每条需求：

```markdown
### Requirement: <需求名>
<需求正文（SHALL 句）>

#### Scenario: <场景名>
- **WHEN** <触发条件>
- **THEN** <可观察结果>
```

## 工作方式

1. 动手前先读这三个文件，任务清单以 tasks.md 为准（spec.md 是验收口径）。
2. 每完成一项任务，用 edit 工具把 tasks.md 里对应的 `- [ ]` 改成 `- [x]`。
3. 场景（Scenario）里的 THEN 就是测试用例的通过标准——用例断言与它保持一致，不得削弱。
4. 不执行任何 git 写操作；提交推送由后端完成。
