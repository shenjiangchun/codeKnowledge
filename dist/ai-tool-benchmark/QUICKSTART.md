# 30 秒上手 — AI Tool Benchmark

假设你已经把脚本放在 `D:\tools\ai-bench\` (或运行了 install 脚本后放在 `~/.claude/skills/ai-tool-benchmark/`)。下面命令把路径替换成你的实际路径即可。

为简化,下文用 `BENCH` 代指 `python D:/tools/ai-bench/bench.py`。

---

## 场景 1:测 Claude Code(自动采集)

**1) 配置 hooks**(只配一次)

打开 `%USERPROFILE%\.claude\settings.json`,把 `hooks-snippet.json` 里的 `hooks` 节点合并进去(注意:已有 hooks 节点的话要做合并,不要覆盖)。

**2) 开始一次测试**
```bash
BENCH start --tool claude-code --task "工单审批流"
```

**3) 在 Claude Code 里正常做需求**
所有工具调用、用户提示、文件改动都会自动落盘,你什么都不用做。
中途可手动打里程碑:
```bash
BENCH log --type milestone --text "代码首次跑通"
BENCH log --type milestone --text "联调成功"
```

**4) 结束并打分**
```bash
BENCH stop
BENCH score --understanding 5 --runnable 4 --standards 4 --complex 4 --context_aware 5 --multi_file 4 --tests 3 --speed 5 --clarification 4 --self_repair 4 --security 5 --cost 4
```

---

## 场景 2:测 Cursor / Copilot / Codex / 通义灵码 等(手动登记)

```bash
BENCH start --tool cursor --task "工单审批流"

# 关键节点 (每分钟手敲 1~2 条即可)
BENCH log --type prompt    --text "实现工单列表+状态机"
BENCH log --type milestone --text "前端跑通"
BENCH log --type error     --text "Mapper 字段拼错"
BENCH log --type retry     --text "重新生成 Service 层"
BENCH log --type milestone --text "联调成功"

BENCH stop
BENCH score --understanding 4 --runnable 3 --standards 3 --complex 3 --context_aware 3 --multi_file 3 --tests 2 --speed 4 --clarification 4 --self_repair 3 --security 5 --cost 3
```

---

## 场景 3:出对比报告

```bash
BENCH list                          # 看所有 session,复制 2 个 ID
BENCH report --sessions 20260511-110747-7e42ae 20260511-110857-b7a703 --output 对比报告.md
```

打开 `对比报告.md`,8 段内容齐全:
1. 测试场次概览
2. 量化指标对比
3. 工具调用类型分布
4. 人工评分对比(12 维 + 已评数 + 总分 + 平均分)
5. 关键里程碑时间线
6. 错误与重试明细
7. 文件变更清单
8. 备注

---

## 评分维度速查(都是 1~5 分)

| 参数 | 含义 |
|---|---|
| `--understanding` | 需求理解准确度 |
| `--runnable` | 代码可运行性 |
| `--standards` | 代码规范符合度 |
| `--complex` | 复杂逻辑处理 |
| `--context_aware` | 项目上下文感知 |
| `--multi_file` | 多文件协同修改 |
| `--tests` | 测试代码生成 |
| `--speed` | 响应速度 |
| `--clarification` | 指令理解/澄清 |
| `--self_repair` | 错误自修复 |
| `--security` | 数据安全 |
| `--cost` | 使用成本(越高越省) |

⚠️ **两边打分维度必须一致**(都打 12 项),否则总分不可比。

---

## 事件 type 速查

| type | 用途 |
|---|---|
| `prompt` | 你输入给 AI 的指令 |
| `reply` | AI 的关键回复(可选) |
| `milestone` | 关键节点(代码跑通/联调成功) |
| `error` | 出错点 |
| `retry` | 重新生成/重试 |
| `note` | 自由备注 |
| `build` | 构建事件(成功/失败) |
| `run` | 运行/启动事件 |
| `manual_tool_call` | 手动登记的"工具调用"(对非 Claude 工具用) |
| `file_change` | 手动登记的文件改动(text 写文件名) |

---

## 出问题时

```bash
BENCH status                  # 查当前会话
BENCH list                    # 查所有会话
BENCH dump <session_id>       # 看原始 events.jsonl
```

数据全在 `~/.claude/ai-bench/sessions/<session_id>/` 目录里,可以直接用编辑器打开 `meta.json` 和 `events.jsonl` 查看。
