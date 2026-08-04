# AI Tool Benchmark - 离线安装包

> 本目录是 **AI Coding 工具对比评测** 工具的离线分发包,内含全部源文件、安装脚本和使用说明。

## 1. 包内文件

| 文件 | 作用 |
|---|---|
| `bench.py` | 主 CLI(start/stop/log/score/list/report/hook/dump) |
| `report.py` | Markdown 对比报告生成器 |
| `SKILL.md` | Claude Code Skill 元数据(可选,仅 Claude Code 用户需要) |
| `install.bat` | Windows 一键安装脚本(复制到 `%USERPROFILE%\.claude\skills\ai-tool-benchmark\`) |
| `install.sh` | macOS / Linux 一键安装脚本 |
| `uninstall.bat` / `uninstall.sh` | **一键卸载脚本**(交互式 + dry-run + settings.json 备份) |
| `uninstall.py` | 卸载主程序(供脚本调用,也可直接 `python uninstall.py` 运行) |
| `hooks-snippet.json` | 要追加到 Claude Code `settings.json` 的 hooks 片段(仅测 Claude Code 时需要) |
| `nga-hooks/` | CodeAgent CLI(`nga`)hook 适配套件 + 一键注册/卸载脚本(详见 `nga-hooks/README.md`) |
| `USAGE.md` | 完整使用手册(操作流程、CLI 参考、评分维度、常见问题) |
| `QUICKSTART.md` | 30 秒上手:复制粘贴就能跑的命令清单 |
| `README.md` | 本文件 |

## 2. 系统要求

- **Python 3.8+**(脚本无第三方依赖,标准库即可)
- **Windows / macOS / Linux** 均可
- 测 Claude Code 时:**已安装 Claude Code** 且能编辑 `~/.claude/settings.json`
- 测其他工具(Cursor/Copilot/Codex/通义灵码等):**无额外依赖**

## 3. 安装(三选一)

### Windows
双击 `install.bat`(或在 cmd 里执行)。脚本会:
1. 把 `bench.py` `report.py` `SKILL.md` 复制到 `%USERPROFILE%\.claude\skills\ai-tool-benchmark\`
2. 创建数据目录 `%USERPROFILE%\.claude\ai-bench\`
3. 校验 `python --version` 可用

### macOS / Linux
```bash
chmod +x install.sh && ./install.sh
```

### 手动
把本目录三个核心文件 (`bench.py`/`report.py`/`SKILL.md`) 拷贝到任意位置(例如 `D:\tools\ai-bench\`),后续命令把路径改成你的位置即可。**不一定要装到 `~/.claude/skills/`**,只有想让 Claude Code 把它识别为 Skill 时才需要。

## 4. 30 秒上手

详见 `QUICKSTART.md`。最常用的 4 条命令:

```bash
python bench.py start --tool claude-code --task "工单审批流"
python bench.py log --type milestone --text "首次跑通"
python bench.py stop
python bench.py report --sessions <id1> <id2> --output 对比报告.md
```

## 5. 完整文档

- 详细操作流程、12 评分维度、报告格式说明 → 看 `USAGE.md`
- Claude Code hooks 配置 → 看 `hooks-snippet.json` + `USAGE.md` 的"模式 A"
- CodeAgent CLI(nga)hooks 配置 → 看 `nga-hooks/README.md`,执行 `nga-hooks/install-nga-hooks.bat`(或 `.sh`)即可一键注册
- 命令行所有参数 → `python bench.py --help`

## 6. 数据落盘位置

所有 session 数据写到: `~/.claude/ai-bench/sessions/<session_id>/`
- `meta.json` - 工具/任务/耗时/评分
- `events.jsonl` - 追加式事件日志(每行一个 JSON)

可放心打包/拷贝整个 `~/.claude/ai-bench/` 目录用于备份或离线分析。

## 7. 已知约束

- 同时只允许 1 个 active 会话(防止数据串)
- hook 在无 active 会话时**静默忽略**,不影响 Claude Code 正常使用
- Windows 终端中文已正确处理(自动 UTF-8 reconfigure)
- 测试结束后请记得**移除 settings.json 中的 hooks 配置**

## 8. 卸载(测试结束后必做)

**强烈推荐用一键卸载脚本**,它会:
- 自动从 `~/.claude/settings.json` 中**只**移除 command 含 `bench.py hook` 的条目
- 保留你所有其他 hooks / permissions / env / model 等设置
- 修改 settings.json 前先备份为 `settings.json.bak.<时间戳>`
- 默认 dry-run,确认后再执行
- 默认**保留**已采集的会话数据,可选 `--purge-data` 一并清理

```bash
# Windows: 双击 uninstall.bat (交互式 — 先 dry-run 再确认)

# Linux/macOS:
bash uninstall.sh

# 或直接调 Python(高级用户):
python uninstall.py                  # dry-run 看会删什么
python uninstall.py --yes            # 实际卸载,保留 session 数据
python uninstall.py --yes --purge-data   # 连 session 数据一起删
```

**手动卸载**(不推荐,容易漏)对应删除以下三处:
- `~/.claude/skills/ai-tool-benchmark/`
- `~/.claude/ai-bench/`(可选)
- `~/.claude/settings.json` 中 hooks 节点下含 `bench.py hook` 的条目
