#!/usr/bin/env python3
"""AI Tool Benchmark - capture and compare AI coding tool execution sessions.

Usage:
  python bench.py start --tool <name> --task <name> [--notes "..."]
  python bench.py stop
  python bench.py status
  python bench.py list
  python bench.py log --type <prompt|milestone|error|retry|note|build|run|manual_tool_call|file_change> [--text "..."] [--data '{...}']
  python bench.py score --understanding 4 --runnable 5 ...
  python bench.py hook <EventName>   # used by Claude Code hooks, reads stdin JSON
  python bench.py report --sessions <id1> <id2> [--output out.md]
"""
import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

# Force UTF-8 stdout/stderr so Chinese text renders correctly on Windows cp936 consoles.
for _stream in ("stdout", "stderr"):
    _s = getattr(sys, _stream, None)
    if _s is not None and hasattr(_s, "reconfigure"):
        try:
            _s.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

ROOT = Path.home() / ".claude" / "ai-bench"
SESSIONS_DIR = ROOT / "sessions"
ACTIVE_FILE = ROOT / "active.json"


def ensure_dirs():
    SESSIONS_DIR.mkdir(parents=True, exist_ok=True)


def now_iso():
    return datetime.now().isoformat(timespec="seconds")


def now_ts():
    return time.time()


def load_active():
    if not ACTIVE_FILE.exists():
        return None
    try:
        return json.loads(ACTIVE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return None


def save_active(data):
    if data is None:
        if ACTIVE_FILE.exists():
            ACTIVE_FILE.unlink()
    else:
        ACTIVE_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def session_path(sid):
    return SESSIONS_DIR / sid


def append_event(sid, event):
    p = session_path(sid) / "events.jsonl"
    event.setdefault("ts", now_ts())
    event.setdefault("time", now_iso())
    with open(p, "a", encoding="utf-8") as f:
        f.write(json.dumps(event, ensure_ascii=False) + "\n")


# ---------- commands ----------

def cmd_start(args):
    ensure_dirs()
    active = load_active()
    if active and not args.force:
        print(f"[!] Active session exists: {active['session_id']} (tool={active['tool']}). Use --force or stop first.", file=sys.stderr)
        sys.exit(1)
    sid = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    sdir = session_path(sid)
    sdir.mkdir(parents=True, exist_ok=True)
    meta = {
        "session_id": sid,
        "tool": args.tool,
        "task": args.task,
        "operator": args.operator or os.environ.get("USERNAME", ""),
        "started_at": now_iso(),
        "started_ts": now_ts(),
        "ended_at": None,
        "ended_ts": None,
        "duration_sec": None,
        "notes": args.notes or "",
        "scores": {},
    }
    (sdir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    save_active({"session_id": sid, "tool": args.tool, "task": args.task})
    append_event(sid, {"type": "session_start", "tool": args.tool, "task": args.task})
    print(f"[OK] Started session {sid}  tool='{args.tool}'  task='{args.task}'")


def cmd_stop(args):
    active = load_active()
    if not active:
        print("[!] No active session", file=sys.stderr)
        sys.exit(1)
    sid = active["session_id"]
    meta_p = session_path(sid) / "meta.json"
    meta = json.loads(meta_p.read_text(encoding="utf-8"))
    meta["ended_at"] = now_iso()
    meta["ended_ts"] = now_ts()
    meta["duration_sec"] = round(meta["ended_ts"] - meta["started_ts"], 1)
    meta_p.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    append_event(sid, {"type": "session_stop"})
    save_active(None)
    print(f"[OK] Stopped session {sid}  duration={meta['duration_sec']}s")


def cmd_status(args):
    active = load_active()
    if not active:
        print("No active session.")
        return
    sid = active["session_id"]
    meta = json.loads((session_path(sid) / "meta.json").read_text(encoding="utf-8"))
    ep = session_path(sid) / "events.jsonl"
    n_events = sum(1 for _ in open(ep, encoding="utf-8")) if ep.exists() else 0
    elapsed = round(now_ts() - meta["started_ts"], 1)
    print(f"Active session: {sid}")
    print(f"  Tool    : {meta['tool']}")
    print(f"  Task    : {meta['task']}")
    print(f"  Elapsed : {elapsed}s")
    print(f"  Events  : {n_events}")


def cmd_log(args):
    active = load_active()
    if not active:
        print("[!] No active session", file=sys.stderr)
        sys.exit(1)
    sid = active["session_id"]
    event = {"type": args.type, "text": args.text or ""}
    if args.data:
        try:
            event["data"] = json.loads(args.data)
        except Exception:
            event["data"] = args.data
    append_event(sid, event)
    print(f"[OK] Logged {args.type}: {(args.text or '')[:60]}")


def cmd_score(args):
    active = load_active()
    if not active:
        print("[!] No active session", file=sys.stderr)
        sys.exit(1)
    sid = active["session_id"]
    meta_p = session_path(sid) / "meta.json"
    meta = json.loads(meta_p.read_text(encoding="utf-8"))
    scores = meta.get("scores", {})
    for k, v in vars(args).items():
        if k in ("func", "cmd") or v is None:
            continue
        scores[k] = v
    meta["scores"] = scores
    meta_p.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[OK] Updated scores: {scores}")


def cmd_list(args):
    ensure_dirs()
    rows = []
    for d in sorted(SESSIONS_DIR.iterdir()):
        mp = d / "meta.json"
        if not mp.exists():
            continue
        m = json.loads(mp.read_text(encoding="utf-8"))
        rows.append((m["session_id"], m.get("tool", "?"), m.get("task", "?"),
                     m.get("duration_sec", "-"), m.get("started_at", "-")))
    if not rows:
        print("(no sessions)")
        return
    print(f"{'SESSION':40}  {'TOOL':14}  {'DURATION':>10}  {'STARTED':20}  TASK")
    for sid, tool, task, dur, t in rows:
        print(f"{sid:40}  {tool:14}  {str(dur):>10}  {t:20}  {task}")


def cmd_hook(args):
    """Receive Claude Code hook input via stdin and append to active session."""
    active = load_active()
    if not active:
        return  # silently ignore when no session active
    sid = active["session_id"]
    raw = sys.stdin.read() if not sys.stdin.isatty() else ""
    try:
        payload = json.loads(raw) if raw.strip() else {}
    except Exception:
        payload = {"raw": raw[:2000]}
    event = {"type": f"hook.{args.event}"}
    if isinstance(payload, dict):
        if "tool_name" in payload:
            event["tool_name"] = payload["tool_name"]
        ti = payload.get("tool_input") or {}
        if isinstance(ti, dict):
            for key in ("file_path", "path", "command", "pattern", "notebook_path"):
                if key in ti:
                    event[key] = str(ti[key])[:500]
                    break
        if "prompt" in payload:
            event["prompt"] = str(payload["prompt"])[:1000]
        tr = payload.get("tool_response")
        if isinstance(tr, dict):
            # capture brief error indication if any
            if tr.get("is_error") or tr.get("error"):
                event["is_error"] = True
    # keep raw payload truncated for forensics
    event["payload_keys"] = list(payload.keys()) if isinstance(payload, dict) else []
    append_event(sid, event)


def cmd_report(args):
    # local import so `python bench.py` works even if report.py path issues
    sys.path.insert(0, str(Path(__file__).parent))
    from report import generate_report
    out = generate_report(args.sessions, args.output)
    print(f"[OK] Report written to {out}")


def cmd_dump(args):
    """Print events JSON for a session (for debugging)."""
    sid = args.session
    ep = session_path(sid) / "events.jsonl"
    if not ep.exists():
        print(f"[!] Not found: {ep}", file=sys.stderr)
        sys.exit(1)
    for line in open(ep, encoding="utf-8"):
        print(line.rstrip())


# ---------- arg parsing ----------

def main():
    p = argparse.ArgumentParser(prog="bench", description="AI coding tool benchmark recorder")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("start", help="Start a new session")
    sp.add_argument("--tool", required=True, help="Tool name, e.g. claude-code / cursor / copilot / codex")
    sp.add_argument("--task", required=True, help="Task name, e.g. '工单审批流'")
    sp.add_argument("--operator", help="Operator name (defaults to USERNAME env)")
    sp.add_argument("--notes", help="Free-text notes about this run")
    sp.add_argument("--force", action="store_true", help="Force-replace existing active session")
    sp.set_defaults(func=cmd_start)

    sp = sub.add_parser("stop", help="Stop the active session")
    sp.set_defaults(func=cmd_stop)

    sp = sub.add_parser("status", help="Show active session info")
    sp.set_defaults(func=cmd_status)

    sp = sub.add_parser("log", help="Log a manual event into the active session")
    sp.add_argument("--type", required=True,
                    choices=["prompt", "reply", "milestone", "error", "retry",
                             "note", "build", "run", "manual_tool_call", "file_change"])
    sp.add_argument("--text", help="Free text describing the event")
    sp.add_argument("--data", help="Optional JSON payload string")
    sp.set_defaults(func=cmd_log)

    sp = sub.add_parser("score", help="Set 1-5 scores on the active session")
    for k in ["understanding", "runnable", "standards", "complex",
              "context_aware", "multi_file", "tests", "speed",
              "clarification", "self_repair", "security", "cost"]:
        sp.add_argument(f"--{k}", type=int, choices=[1, 2, 3, 4, 5])
    sp.set_defaults(func=cmd_score)

    sp = sub.add_parser("list", help="List all recorded sessions")
    sp.set_defaults(func=cmd_list)

    sp = sub.add_parser("hook", help="Internal: receive Claude Code hook stdin and log")
    sp.add_argument("event", help="Hook event name, e.g. PreToolUse / PostToolUse / UserPromptSubmit / Stop")
    sp.set_defaults(func=cmd_hook)

    sp = sub.add_parser("report", help="Generate markdown comparison report")
    sp.add_argument("--sessions", nargs="+", required=True, help="One or more session IDs")
    sp.add_argument("--output", default="bench-report.md")
    sp.set_defaults(func=cmd_report)

    sp = sub.add_parser("dump", help="Dump events.jsonl for a session")
    sp.add_argument("session")
    sp.set_defaults(func=cmd_dump)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
