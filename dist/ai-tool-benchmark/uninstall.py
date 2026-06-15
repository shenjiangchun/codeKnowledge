#!/usr/bin/env python3
"""
AI Tool Benchmark - One-click Uninstaller

What it removes (and ONLY these):
  1. Hooks in ~/.claude/settings.json whose command contains 'bench.py hook'
     (other hooks/permissions/env/settings are LEFT UNTOUCHED)
  2. Active session pointer:  ~/.claude/ai-bench/active.json
  3. Skill folder:            ~/.claude/skills/ai-tool-benchmark/
  4. (Optional) Data folder:  ~/.claude/ai-bench/   -- only with --purge-data

Safety:
  - settings.json is backed up to settings.json.bak.<timestamp> before edit
  - Dry-run by default — pass --yes to actually remove
  - Refuses to touch any setting that isn't a bench.py hook entry

Usage:
  python uninstall.py              # dry-run, show what WOULD be removed
  python uninstall.py --yes        # actually remove (keeps session data)
  python uninstall.py --yes --purge-data    # also delete all recorded sessions
"""
import argparse
import json
import shutil
import sys
from datetime import datetime
from pathlib import Path

# Force UTF-8 console on Windows
for _s in ("stdout", "stderr"):
    _x = getattr(sys, _s, None)
    if _x is not None and hasattr(_x, "reconfigure"):
        try:
            _x.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

HOME = Path.home()
CLAUDE_DIR = HOME / ".claude"
SETTINGS = CLAUDE_DIR / "settings.json"
SKILL_DIR = CLAUDE_DIR / "skills" / "ai-tool-benchmark"
DATA_DIR = CLAUDE_DIR / "ai-bench"
ACTIVE = DATA_DIR / "active.json"

BENCH_SIGNATURE = "bench.py hook"   # how we identify "our" hooks


def is_bench_hook(cmd: str) -> bool:
    """Return True only if this command string is one of our bench hook commands."""
    if not isinstance(cmd, str):
        return False
    return BENCH_SIGNATURE in cmd and "ai-tool-benchmark" in cmd.replace("\\", "/")


def clean_hooks(hooks_obj):
    """
    Walk the standard Claude Code hooks structure and remove only matching entries.
    Structure: { "<EventName>": [ { matcher?, hooks: [ {type, command}, ... ] }, ... ] }
    Return (cleaned_hooks_or_None, removed_count).
    """
    if not isinstance(hooks_obj, dict):
        return hooks_obj, 0

    removed = 0
    new_hooks = {}

    for event_name, matchers in hooks_obj.items():
        if not isinstance(matchers, list):
            new_hooks[event_name] = matchers
            continue
        new_matchers = []
        for matcher_entry in matchers:
            if not isinstance(matcher_entry, dict):
                new_matchers.append(matcher_entry)
                continue
            inner = matcher_entry.get("hooks")
            if not isinstance(inner, list):
                new_matchers.append(matcher_entry)
                continue
            kept_inner = []
            for h in inner:
                if isinstance(h, dict) and is_bench_hook(h.get("command", "")):
                    removed += 1
                    continue
                kept_inner.append(h)
            if kept_inner:
                new_entry = dict(matcher_entry)
                new_entry["hooks"] = kept_inner
                new_matchers.append(new_entry)
            # else: this matcher entry only had our hook — drop the whole entry
        if new_matchers:
            new_hooks[event_name] = new_matchers
        # else: this event has nothing left — drop the event key entirely

    return new_hooks, removed


def clean_settings_file(dry_run: bool):
    if not SETTINGS.exists():
        print(f"  [skip] settings.json not found at {SETTINGS}")
        return 0
    try:
        raw = SETTINGS.read_text(encoding="utf-8")
        data = json.loads(raw)
    except Exception as e:
        print(f"  [warn] settings.json is not valid JSON, skipping: {e}")
        return 0
    if not isinstance(data, dict) or "hooks" not in data:
        print("  [skip] no 'hooks' key in settings.json")
        return 0

    cleaned, removed = clean_hooks(data["hooks"])
    if removed == 0:
        print("  [skip] no bench.py hook entries found in settings.json")
        return 0

    if dry_run:
        print(f"  [dry-run] would remove {removed} bench hook entr{'y' if removed==1 else 'ies'} from settings.json")
        return removed

    # Backup
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = SETTINGS.with_suffix(f".json.bak.{ts}")
    shutil.copy2(SETTINGS, backup)
    print(f"  [backup] {backup}")

    new_data = dict(data)
    if cleaned:
        new_data["hooks"] = cleaned
    else:
        # hooks key became empty — remove it altogether
        new_data.pop("hooks", None)
    SETTINGS.write_text(json.dumps(new_data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  [done] removed {removed} hook entr{'y' if removed==1 else 'ies'} from settings.json")
    return removed


def remove_path(p: Path, dry_run: bool, label: str):
    if not p.exists():
        print(f"  [skip] {label} not found: {p}")
        return False
    if dry_run:
        print(f"  [dry-run] would remove {label}: {p}")
        return True
    if p.is_dir():
        shutil.rmtree(p, ignore_errors=False)
    else:
        p.unlink()
    print(f"  [done] removed {label}: {p}")
    return True


def main():
    ap = argparse.ArgumentParser(description="Uninstall AI Tool Benchmark — removes ONLY bench hooks + skill files.")
    ap.add_argument("--yes", action="store_true", help="actually perform removal (otherwise dry-run)")
    ap.add_argument("--purge-data", action="store_true", help="also delete ~/.claude/ai-bench/ (ALL recorded sessions)")
    args = ap.parse_args()

    dry_run = not args.yes
    mode = "DRY-RUN (no changes will be made)" if dry_run else "EXECUTE"

    print("=" * 60)
    print(f" AI Tool Benchmark — Uninstaller  [{mode}]")
    print("=" * 60)
    print(f" Home directory : {HOME}")
    print(f" settings.json  : {SETTINGS}")
    print(f" Skill folder   : {SKILL_DIR}")
    print(f" Data folder    : {DATA_DIR}   {'(KEEP)' if not args.purge_data else '(WILL PURGE)'}")
    print()

    print("[1/4] Clean hooks in settings.json")
    clean_settings_file(dry_run)

    print("\n[2/4] Remove active session pointer (if any)")
    remove_path(ACTIVE, dry_run, "active.json")

    print("\n[3/4] Remove skill folder")
    remove_path(SKILL_DIR, dry_run, "skill folder")

    print("\n[4/4] Data folder")
    if args.purge_data:
        remove_path(DATA_DIR, dry_run, "data folder")
    else:
        print(f"  [keep] {DATA_DIR}   (use --purge-data to also remove all sessions)")

    print()
    print("=" * 60)
    if dry_run:
        print(" Dry-run complete. Re-run with --yes to actually remove.")
        print(" Example:   python uninstall.py --yes")
        print(" To also delete recorded sessions:")
        print("            python uninstall.py --yes --purge-data")
    else:
        print(" Uninstall complete.")
    print("=" * 60)


if __name__ == "__main__":
    main()
