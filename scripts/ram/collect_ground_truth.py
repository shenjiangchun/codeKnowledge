"""Collect ground-truth (PR body + changed files) for the RAM shadow evaluation.

Pulls merged PRs from a GitHub repo via the ``gh`` CLI and writes a JSONL file
with one record per PR::

    {
      "number": 123,
      "title": "...",
      "body": "...",
      "files": ["src/a.java", "src/b.java"],
      "merged_at": "2025-04-12T..."
    }

The downstream consumer is ``shadow_runner.py``, which feeds ``body`` into
``analyze_requirement`` and compares the predicted impact set against ``files``.

Usage::

    python collect_ground_truth.py --repo owner/name --months 6 \\
        --out ground_truth.jsonl

Requires the ``gh`` CLI to be installed and authenticated. The script shells
out — keeps the dependency surface tiny (no PyGithub).
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import logging
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable

LOG = logging.getLogger("ram.collect_ground_truth")


def _check_gh_available() -> None:
    if shutil.which("gh") is None:
        raise SystemExit(
            "gh CLI is required (https://cli.github.com/). Install it and run "
            "`gh auth login` before invoking this script."
        )


def _gh_search_prs(repo: str, since_iso: str, limit: int) -> list[dict[str, Any]]:
    """Use ``gh pr list`` to fetch merged PRs since ``since_iso``."""
    cmd = [
        "gh", "pr", "list",
        "--repo", repo,
        "--state", "merged",
        "--limit", str(limit),
        "--search", f"merged:>={since_iso}",
        "--json", "number,title,body,mergedAt,files",
    ]
    LOG.info("running %s", " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise SystemExit(f"gh pr list failed: {result.stderr.strip()}")
    return json.loads(result.stdout or "[]")


def _normalize(record: dict[str, Any]) -> dict[str, Any]:
    files_raw = record.get("files") or []
    files = [
        f.get("path") if isinstance(f, dict) else str(f)
        for f in files_raw
    ]
    return {
        "number": int(record.get("number") or 0),
        "title": str(record.get("title") or ""),
        "body": str(record.get("body") or ""),
        "files": [f for f in files if f],
        "merged_at": str(record.get("mergedAt") or ""),
    }


def _write_jsonl(records: Iterable[dict[str, Any]], out_path: Path) -> int:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with out_path.open("w", encoding="utf-8") as fh:
        for rec in records:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            count += 1
    return count


def collect(repo: str, months: int, limit: int, out_path: Path) -> int:
    """Top-level entry: fetch + normalize + write. Returns count written."""
    since = _dt.datetime.utcnow() - _dt.timedelta(days=30 * months)
    since_iso = since.strftime("%Y-%m-%d")
    raw = _gh_search_prs(repo, since_iso, limit)
    normalized = [_normalize(r) for r in raw if r.get("body")]
    written = _write_jsonl(normalized, out_path)
    LOG.info("wrote %d records to %s", written, out_path)
    return written


def _parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Collect ground-truth PRs for RAM shadow eval")
    p.add_argument("--repo", required=True, help="owner/name (e.g. acme/widget)")
    p.add_argument("--months", type=int, default=6, help="look-back window in months (default 6)")
    p.add_argument("--limit", type=int, default=200, help="max PRs to fetch (default 200)")
    p.add_argument("--out", required=True, help="output JSONL file path")
    p.add_argument("--verbose", action="store_true")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv or sys.argv[1:])
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    _check_gh_available()
    collect(args.repo, args.months, args.limit, Path(args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
