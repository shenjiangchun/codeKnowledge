# nga (CodeAgent CLI) Hook Adapter

This folder lets the **AI Tool Benchmark** capture nga sessions the same way it
captures Claude Code sessions — by registering hooks that forward each event
to `bench.py hook <EventName>`.

## Files

| File | Role |
|---|---|
| `_adapter.js`                | Generic adapter — spawns `python bench.py hook <event>` and pipes JSON payload via stdin. Reused by every event shim. |
| `chat-message.js`            | Shim → event `chat.message` |
| `tool-execute-before.js`     | Shim → event `tool.execute.before` |
| `tool-execute-after.js`      | Shim → event `tool.execute.after` |
| `session-stop.js`            | Shim → event `session.stop` (optional, ignore if your nga build doesn't emit it) |
| `install-nga-hooks.{bat,sh}` | Runs `nga hooks add` for each event with absolute paths |
| `uninstall-nga-hooks.{bat,sh}` | Runs `nga hooks remove` for each event |

## Install

Pre-req: the main benchmark must already be installed (so `bench.py` exists at
`%USERPROFILE%\.claude\skills\ai-tool-benchmark\bench.py` or you set `BENCH_PY`
to its actual path).

```bash
# Windows
nga-hooks\install-nga-hooks.bat

# macOS / Linux
chmod +x nga-hooks/install-nga-hooks.sh
./nga-hooks/install-nga-hooks.sh
```

Verify:
```bash
nga hooks list
```

## Use

Same workflow as for Claude Code:

```bash
python bench.py start --tool nga --task "工单审批流"
# ... use nga normally; events stream into events.jsonl ...
python bench.py stop
python bench.py score   # then fill the 12 dimensions
```

When no benchmark session is `start`-ed, hooks are no-ops (silent return).

## Environment overrides

| Var | Default | Purpose |
|---|---|---|
| `BENCH_PY`     | `~/.claude/skills/ai-tool-benchmark/bench.py` | Path to bench.py |
| `BENCH_PYTHON` | `python` | Python interpreter to spawn |
| `BENCH_EVENT`  | derived from filename | Force the event name (advanced) |

## Uninstall

```bash
# Windows
nga-hooks\uninstall-nga-hooks.bat

# Unix
./nga-hooks/uninstall-nga-hooks.sh
```

This removes only the four bench hooks; your other nga hooks are untouched.
