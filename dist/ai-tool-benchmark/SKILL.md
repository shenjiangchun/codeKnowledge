---
name: ai-tool-benchmark
description: Use when comparing two AI coding tools on the same task and need to capture execution data (tool calls, prompts, errors, file changes, timings) and generate a comparison report. Triggers include "对比 ai 编程工具", "AI coding benchmark", "比较 Cursor / Claude Code / Copilot / Codex", "工具评测报告".
---

# AI Tool Benchmark — 实时采集与对比报告

## Overview

一个用于**对同一需求在两个不同 AI Coding 工具中开发**时,自动+手动采集执行过程数据并生成对比报告的工具。

- **对 Claude Code**:通过 `settings.json` 的 hooks 自动抓取每一次工具调用、用户提示、错误、文件变更。
- **对其他工具(Cursor / Copilot / Codex / 通义灵码 等)**:通过 `bench log` CLI 手动登记关键事件,只需输入 1~2 条命令/分钟。
- **统一存储**:`~/.claude/ai-bench/sessions/<session_id>/`,事件以 JSONL 追加,meta 以 JSON 保存。
- **一键报告**:`bench.py report` 生成 8 段式 Markdown 对比报告(可直接交付给领导)。

## When to Use

- 需要在两个或多个 AI 编程工具间做横向能力对比
- 领导要求"测试 + 报告",且必须可量化、可复现
- 同一需求会跑两遍,需保留过程证据

## Files in this Skill

| 文件 | 作用 |
|---|---|
| `bench.py` | 主 CLI:start/stop/status/log/score/list/report/hook/dump |
| `report.py` | 报告生成器(被 `bench.py report` 调用) |

数据落盘位置:`%USERPROFILE%\.claude\ai-bench\`

## Quick Start

### 1. 测 Claude Code(自动采集)

在 `C:\Users\47583\.claude\settings.json` 加入如下 hooks(关闭测试后请移除或注释):

```json
{
  "hooks": {
    "PreToolUse":      [{"matcher": "*", "hooks": [{"type": "command", "command": "python C:/Users/47583/.claude/skills/ai-tool-benchmark/bench.py hook PreToolUse"}]}],
    "PostToolUse":     [{"matcher": "*", "hooks": [{"type": "command", "command": "python C:/Users/47583/.claude/skills/ai-tool-benchmark/bench.py hook PostToolUse"}]}],
    "UserPromptSubmit":[{"hooks": [{"type": "command", "command": "python C:/Users/47583/.claude/skills/ai-tool-benchmark/bench.py hook UserPromptSubmit"}]}],
    "Stop":            [{"hooks": [{"type": "command", "command": "python C:/Users/47583/.claude/skills/ai-tool-benchmark/bench.py hook Stop"}]}]
  }
}
```

然后:
```bash
python C:/Users/47583/.claude/skills/ai-tool-benchmark/bench.py start --tool claude-code --task "工单审批流"
# ...在 Claude Code 中正常做需求,hooks 自动记录...
python .../bench.py log --type milestone --text "首次代码可运行"
python .../bench.py stop
python .../bench.py score --understanding 5 --runnable 4 --standards 4 --speed 5
```

### 2. 测其他工具(手动登记)

```bash
python .../bench.py start --tool cursor --task "工单审批流"
python .../bench.py log --type prompt --text "实现工单列表 + 状态机"
python .../bench.py log --type milestone --text "前端跑通"
python .../bench.py log --type error --text "Mapper 字段名拼错"
python .../bench.py log --type retry --text "重新生成 Service 层"
python .../bench.py log --type milestone --text "联调成功"
python .../bench.py stop
python .../bench.py score --understanding 4 --runnable 3 --standards 3 --speed 4
```

### 3. 生成对比报告

```bash
python .../bench.py list                     # 查 session id
python .../bench.py report --sessions <claude-sid> <cursor-sid> --output 对比报告.md
```

## CLI 参考

| 命令 | 说明 |
|---|---|
| `start --tool X --task Y [--notes ...] [--force]` | 开始一次会话 |
| `stop` | 结束当前会话 |
| `status` | 查看当前会话状态 |
| `list` | 列出所有会话 |
| `log --type T --text ... [--data '{}']` | 记录手动事件 |
| `score --understanding N --runnable N ...` | 给当前会话评分(1–5) |
| `hook <Event>` | (内部)hook 入口,读 stdin |
| `report --sessions S1 S2 --output X.md` | 生成对比报告 |
| `dump <session_id>` | 打印 events.jsonl 调试 |

### 事件 type 取值

`prompt` `reply` `milestone` `error` `retry` `note` `build` `run` `manual_tool_call` `file_change`

### 评分维度(均 1–5,与第一页对比表一致)

`understanding` 需求理解 / `runnable` 可运行性 / `standards` 规范符合度 / `complex` 复杂逻辑 / `context_aware` 项目感知 / `multi_file` 多文件协同 / `tests` 测试生成 / `speed` 响应速度 / `clarification` 指令理解 / `self_repair` 错误自修复 / `security` 数据安全 / `cost` 使用成本(越高越省)

## 报告输出包含

1. 测试场次概览(工具 / 任务 / 耗时)
2. 量化指标对比(总耗时、工具调用次数、提示次数、错误数、文件数)
3. 工具调用类型分布
4. 人工评分对比(12 个维度+总分)
5. 关键里程碑时间线
6. 错误与重试明细
7. 文件变更清单
8. 备注

## 重要约定

- 同时只能有一个 active 会话。开新场前先 `stop`,或加 `--force` 覆盖。
- hook 在无 active 会话时**静默忽略**,不影响正常 Claude Code 使用。
- 测试结束后建议**移除 hook 配置**,避免长期影响性能。
- 评分应由 2~3 人交叉打分取均值,然后通过 `score` 命令登记最终值。
