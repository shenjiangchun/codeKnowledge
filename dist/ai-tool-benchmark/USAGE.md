# USAGE — AI Tool Benchmark 完整使用手册

## 一、定位

对**同一需求在两个不同 AI Coding 工具里开发**时,实时采集执行过程数据(工具调用、提示、错误、文件改动、耗时)并自动生成对比报告,用于工具选型 / 能力评测 / 汇报领导。

---

## 二、安装(详见 README.md)

- Windows: 双击 `install.bat`
- Linux/macOS: `bash install.sh`
- 手动: 把 `bench.py` `report.py` 拷贝到任意目录即可,Python 3.8+ 标准库无第三方依赖

---

## 三、模式 A — 测 Claude Code(自动 hooks)

### 1. 配置 hooks

打开 `~/.claude/settings.json`,把 `hooks-snippet.json` 中的 `hooks` 节点合并进去。**注意**:
- 如果 settings.json 已有 `hooks` 字段,做 JSON 合并;不要整个覆盖
- 命令路径中的 `python` 如果 PATH 中没有,改用绝对路径(如 `C:/Python313/python.exe`)
- 脚本路径必须用**正斜杠**或**双反斜杠**,不能 `C:\Users\...` 单反斜杠

### 2. 工作流程

```bash
# 一切开始前
python <安装路径>/bench.py start --tool claude-code --task "工单审批流" --notes "v1 测试"

# 在 Claude Code 中正常做需求 -- hooks 自动记录所有事件
# 中途有里程碑随时打:
python <安装路径>/bench.py log --type milestone --text "首次代码可运行"
python <安装路径>/bench.py log --type milestone --text "前后端联调通过"

# 结束
python <安装路径>/bench.py stop

# 打分(12 个维度,每项 1-5 分)
python <安装路径>/bench.py score \
    --understanding 5 --runnable 4 --standards 4 --complex 4 \
    --context_aware 5 --multi_file 4 --tests 3 --speed 5 \
    --clarification 4 --self_repair 4 --security 5 --cost 4
```

### 3. 自动捕获什么

| Hook | 记录内容 |
|---|---|
| PreToolUse | 工具名、参数(file_path/command 等)、时间戳 |
| PostToolUse | 工具结果、是否报错 |
| UserPromptSubmit | 用户输入的提示词(截断 1000 字) |
| Stop | 一轮对话结束信号 |

---

## 四、模式 B — 测其他 AI 工具(手动登记)

Cursor / GitHub Copilot / Codex / 通义灵码 / Trae / Augment 等无法挂 Claude Code hook,只能用 `bench log` 关键节点手动登记。**节奏建议:每 1-3 分钟打一条,关键事件不漏**。

```bash
python <安装路径>/bench.py start --tool cursor --task "工单审批流"

python <安装路径>/bench.py log --type prompt    --text "实现工单列表+状态机"
python <安装路径>/bench.py log --type milestone --text "前端跑通"
python <安装路径>/bench.py log --type error     --text "Mapper 字段拼错"
python <安装路径>/bench.py log --type retry     --text "重新生成 Service 层"
python <安装路径>/bench.py log --type file_change --text "OrderService.java"
python <安装路径>/bench.py log --type milestone --text "联调成功"

python <安装路径>/bench.py stop
python <安装路径>/bench.py score --understanding 4 --runnable 3 ...
```

**小技巧**:开两个终端,一个跑 AI 工具,另一个常驻 `bench log` 命令历史(↑ 调出修改 text 即可)。

---

## 五、CLI 完整参考

### `start`
```
--tool TEXT      [必填] 工具名 (claude-code/cursor/copilot/codex/...)
--task TEXT      [必填] 任务名 (会显示在报告里)
--operator TEXT  操作员名 (默认取 USERNAME 环境变量)
--notes TEXT     备注 (会出现在报告"备注"段)
--force          强制覆盖已存在的 active 会话
```

### `stop` / `status` / `list`
无参数。

### `log`
```
--type TYPE      [必填] prompt|reply|milestone|error|retry|note|build|run|manual_tool_call|file_change
--text TEXT      事件描述
--data JSON      可选 JSON 附加数据
```

### `score`
12 个维度参数,每个值 1-5:
```
--understanding --runnable --standards --complex --context_aware --multi_file
--tests --speed --clarification --self_repair --security --cost
```

### `report`
```
--sessions S1 S2 [...]   [必填] 1 个或多个 session ID
--output PATH            输出文件路径,默认 bench-report.md
```

### `dump <session_id>`
打印该 session 的 `events.jsonl` 全部内容(调试用)。

### `hook <Event>` (内部)
被 Claude Code 调用,从 stdin 读 JSON,无活动会话时静默退出。**不需要手动调用**。

---

## 六、12 评分维度详解

| 参数 | 维度 | 评分参考 |
|---|---|---|
| understanding | 需求理解准确度 | 5=一次说清就懂; 1=反复澄清仍偏 |
| runnable | 代码可运行性 | 5=贴上即跑; 1=改半天才跑 |
| standards | 代码规范符合度 | 5=完全符合团队规范; 1=风格混乱 |
| complex | 复杂逻辑处理 | 5=状态机/事务正确; 1=漏分支 |
| context_aware | 项目上下文感知 | 5=自动复用工具类; 1=造重复轮子 |
| multi_file | 多文件协同修改 | 5=跨文件改动一致; 1=遗漏调用方 |
| tests | 测试代码生成 | 5=主动写单测且覆盖; 1=不写或乱写 |
| speed | 响应速度 | 5=秒级出码; 1=经常等待 |
| clarification | 指令理解/澄清 | 5=模糊需求会主动确认; 1=瞎猜 |
| self_repair | 错误自修复 | 5=报错能定位修; 1=越改越乱 |
| security | 数据安全 | 5=本地化/不外发; 1=代码全发云端 |
| cost | 使用成本 (越高越省) | 5=免费/极低; 1=昂贵 |

**建议**: 2-3 人交叉打分取均值,再用 `score` 命令登记。

---

## 七、报告输出格式(8 段)

| 段 | 内容 | 数据来源 |
|---|---|---|
| 一 | 测试场次概览 | meta.json |
| 二 | 量化指标对比(耗时/事件/调用/提示/错误/重试/文件/里程碑) | events.jsonl 聚合 |
| 三 | 工具调用类型分布(Edit/Write/Read/Bash/...) | hook.PreToolUse 事件 |
| 四 | 人工评分对比 + 已评数 + 总分(标满分) + 平均分 | meta.scores |
| 五 | 关键里程碑时间线(相对开始 +Xs) | type=milestone |
| 六 | 错误与重试明细 | type=error/retry/is_error |
| 七 | 文件变更清单 | Edit/Write 工具的 file_path + type=file_change |
| 八 | 备注 | meta.notes |

---

## 八、目录结构

```
~/.claude/ai-bench/
├── active.json             # 当前活动会话指针(stop 后删除)
└── sessions/
    └── <session_id>/
        ├── meta.json       # tool/task/started_at/duration/scores
        └── events.jsonl    # 追加式事件日志
```

session_id 格式: `YYYYMMDD-HHMMSS-<6 位随机>`,例如 `20260511-110747-7e42ae`。

---

## 九、常见问题

**Q: hook 配上后 Claude Code 变慢?**
A: 每次 hook 调用 ~50ms。如果有上百次工具调用,总开销 5s 以内,正常。**测试结束请移除 hooks**。

**Q: 会话没 stop 就关电脑了?**
A: `active.json` 还在,下次再 start 加 `--force` 即可。已有数据不丢。

**Q: 中文乱码?**
A: 已修复(脚本启动时强制 UTF-8 stdout/stderr)。如果你的 Python <3.7,升级一下。

**Q: 想看原始数据?**
A: `bench dump <id>` 或直接编辑器打开 `~/.claude/ai-bench/sessions/<id>/events.jsonl`。

**Q: 想用 Python 脚本二次分析?**
A: events.jsonl 每行一个 JSON 对象,直接 `pandas.read_json(..., lines=True)` 即可。

**Q: hook 在内网机器上路径不一样?**
A: 改 `hooks-snippet.json` 的 command 路径,或者把脚本装到统一路径。

**Q: 数据敏感能否离线?**
A: 全程本地落盘,无任何网络请求。可放心在内网 / 涉密环境使用。

---

## 十、卸载

```bash
# Windows
rmdir /s /q "%USERPROFILE%\.claude\skills\ai-tool-benchmark"
rmdir /s /q "%USERPROFILE%\.claude\ai-bench"

# Linux/macOS
rm -rf ~/.claude/skills/ai-tool-benchmark ~/.claude/ai-bench
```

记得也从 `settings.json` 里去掉 hooks 配置。
