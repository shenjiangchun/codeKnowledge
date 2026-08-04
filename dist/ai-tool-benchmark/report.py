"""Generate markdown comparison report from one or more sessions."""
import json
from pathlib import Path
from collections import Counter
from datetime import datetime

ROOT = Path.home() / ".claude" / "ai-bench"
SESSIONS_DIR = ROOT / "sessions"

SCORE_FIELDS = [
    ("understanding",  "需求理解准确度"),
    ("runnable",       "代码可运行性"),
    ("standards",      "代码规范符合度"),
    ("complex",        "复杂逻辑处理"),
    ("context_aware",  "项目上下文感知"),
    ("multi_file",     "多文件协同修改"),
    ("tests",          "测试代码生成"),
    ("speed",          "响应速度"),
    ("clarification",  "指令理解/澄清"),
    ("self_repair",    "错误自修复"),
    ("security",       "数据安全"),
    ("cost",           "使用成本 (越高越省)"),
]


def load_session(sid):
    sdir = SESSIONS_DIR / sid
    if not sdir.exists():
        raise FileNotFoundError(f"Session not found: {sid}")
    meta = json.loads((sdir / "meta.json").read_text(encoding="utf-8"))
    events = []
    ep = sdir / "events.jsonl"
    if ep.exists():
        for line in open(ep, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except Exception:
                pass
    return meta, events


def metrics(meta, events):
    m = {}
    dur = meta.get("duration_sec") or 0
    m["duration_sec"] = dur
    m["duration_min"] = round(dur / 60, 1) if dur else 0
    m["total_events"] = len(events)

    type_counts = Counter(e.get("type", "?") for e in events)
    m["type_counts"] = dict(type_counts)

    tool_calls = [e for e in events if e.get("type") == "hook.PreToolUse"]
    m["tool_call_count"] = len(tool_calls)
    m["tool_breakdown"] = dict(Counter(e.get("tool_name", "?") for e in tool_calls))

    m["prompt_count"] = (type_counts.get("prompt", 0)
                         + type_counts.get("hook.UserPromptSubmit", 0))
    m["error_count"] = type_counts.get("error", 0) + sum(
        1 for e in events if e.get("is_error"))
    m["retry_count"] = type_counts.get("retry", 0)

    file_set = set()
    for e in events:
        if e.get("type") in ("hook.PreToolUse", "hook.PostToolUse"):
            if e.get("tool_name") in ("Edit", "Write", "NotebookEdit") and e.get("file_path"):
                file_set.add(e["file_path"])
        if e.get("type") == "file_change" and e.get("text"):
            file_set.add(e["text"])
    m["files_touched"] = len(file_set)
    m["files_list"] = sorted(file_set)

    m["milestones"] = [e for e in events if e.get("type") == "milestone"]
    m["errors"] = [e for e in events if e.get("type") == "error" or e.get("is_error")]
    m["prompts"] = [e for e in events if e.get("type") in ("prompt", "hook.UserPromptSubmit")]
    return m


def render_table(headers, rows):
    out = "| " + " | ".join(str(h) for h in headers) + " |\n"
    out += "|" + "|".join(["---"] * len(headers)) + "|\n"
    for r in rows:
        out += "| " + " | ".join(str(c) for c in r) + " |\n"
    return out


def generate_report(sids, output_path):
    sessions = [(s, *load_session(s)) for s in sids]
    data = []
    for sid, meta, events in sessions:
        data.append({"sid": sid, "meta": meta, "metrics": metrics(meta, events)})

    L = []
    L.append("# AI Coding 工具对比报告\n\n")
    L.append(f"_生成时间: {datetime.now().isoformat(timespec='seconds')}_\n\n")

    # 1. session summary
    L.append("## 一、测试场次概览\n\n")
    rows = [[d["meta"].get("tool", "?"),
             d["meta"].get("task", "?"),
             d["meta"].get("started_at", "-"),
             f"{d['metrics']['duration_min']} min" if d["metrics"]["duration_min"] else "-",
             d["sid"]] for d in data]
    L.append(render_table(["工具", "任务", "开始时间", "耗时", "Session ID"], rows))

    # 2. quantitative metrics
    L.append("\n## 二、量化指标对比\n\n")
    metric_rows = [
        ["总耗时 (分钟)"]    + [d["metrics"]["duration_min"] for d in data],
        ["总事件数"]         + [d["metrics"]["total_events"] for d in data],
        ["工具调用次数"]     + [d["metrics"]["tool_call_count"] for d in data],
        ["用户提示次数"]     + [d["metrics"]["prompt_count"] for d in data],
        ["错误次数"]         + [d["metrics"]["error_count"] for d in data],
        ["重试次数"]         + [d["metrics"]["retry_count"] for d in data],
        ["涉及文件数"]       + [d["metrics"]["files_touched"] for d in data],
        ["里程碑数"]         + [len(d["metrics"]["milestones"]) for d in data],
    ]
    L.append(render_table(["指标"] + [d["meta"].get("tool", "?") for d in data], metric_rows))

    # 3. tool breakdown
    L.append("\n## 三、工具调用类型分布\n\n")
    all_tools = set()
    for d in data:
        all_tools.update(d["metrics"]["tool_breakdown"].keys())
    if all_tools:
        rows = []
        for t in sorted(all_tools):
            rows.append([t] + [d["metrics"]["tool_breakdown"].get(t, 0) for d in data])
        L.append(render_table(["子工具"] + [d["meta"].get("tool", "?") for d in data], rows))
    else:
        L.append("_(无 hook 工具调用记录;非 Claude Code 工具请用 `bench log --type manual_tool_call` 手动登记)_\n")

    # 4. scores
    L.append("\n## 四、人工评分对比 (1-5 分)\n\n")
    score_rows = []
    for k, label in SCORE_FIELDS:
        score_rows.append([label] + [d["meta"].get("scores", {}).get(k, "-") for d in data])
    totals = []
    counts = []
    for d in data:
        s = d["meta"].get("scores", {})
        vals = [s.get(k) for k, _ in SCORE_FIELDS if isinstance(s.get(k), int)]
        totals.append(sum(vals))
        counts.append(len(vals))
    score_rows.append(["**已评维度数**"] + counts)
    score_rows.append([f"**总分 (满分 {len(SCORE_FIELDS)*5})**"] + totals)
    score_rows.append(["**平均分**"] + [round(t/c, 2) if c else "-" for t, c in zip(totals, counts)])
    L.append(render_table(["维度"] + [d["meta"].get("tool", "?") for d in data], score_rows))
    L.append("\n_注:总分仅汇总实际已打分维度,缺失项不计入。如需公平横向比较,请保证两侧打分维度一致。_\n")

    # 5. milestones timeline
    L.append("\n## 五、关键里程碑时间线\n")
    for d in data:
        L.append(f"\n### {d['meta'].get('tool','?')} — {d['meta'].get('task','?')}\n\n")
        ms = d["metrics"]["milestones"]
        if not ms:
            L.append("_(无里程碑)_\n")
            continue
        rows = []
        t0 = d["meta"].get("started_ts") or 0
        for e in ms:
            offset = round((e.get("ts") or 0) - t0, 1)
            rows.append([e.get("time", ""), f"+{offset}s", e.get("text", "")])
        L.append(render_table(["时间", "相对开始", "事件"], rows))

    # 6. errors
    L.append("\n## 六、错误与重试明细\n")
    for d in data:
        L.append(f"\n### {d['meta'].get('tool','?')}\n\n")
        errs = d["metrics"]["errors"]
        if not errs:
            L.append("_(无)_\n")
        else:
            for e in errs:
                L.append(f"- `{e.get('time','')}` {e.get('text') or e.get('tool_name','')}\n")

    # 7. files touched
    L.append("\n## 七、文件变更清单\n")
    for d in data:
        L.append(f"\n### {d['meta'].get('tool','?')}\n\n")
        files = d["metrics"]["files_list"]
        if not files:
            L.append("_(无)_\n")
        else:
            for f in files:
                L.append(f"- `{f}`\n")

    # 8. notes
    L.append("\n## 八、备注\n")
    any_notes = False
    for d in data:
        notes = (d["meta"].get("notes") or "").strip()
        if notes:
            any_notes = True
            L.append(f"\n### {d['meta'].get('tool','?')}\n\n{notes}\n")
    if not any_notes:
        L.append("\n_(无)_\n")

    out = Path(output_path)
    out.write_text("".join(L), encoding="utf-8")
    return str(out.resolve())
