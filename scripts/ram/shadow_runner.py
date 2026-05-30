"""Shadow-mode runner for the RAM (Requirement Analysis Master).

For each PR record from ``ground_truth.jsonl`` produced by
``collect_ground_truth.py``, this script:

1. Calls the local RAM backend's ``POST /api/ram/sessions`` to start a new
   session with ``raw_input = pr.body`` and ``project_path``.
2. Polls the SSE stream (or the rejoin endpoint) until ``RUN_COMPLETED``.
3. Extracts the predicted impact file list and pushes a record to the output
   JSONL.
4. Runs eval metrics (``recall_at_pr``, ``precision_at_pr``, ``jaccard``,
   ``validator_pass_rate``) once per PR and again as an aggregate.

The output JSONL is fed directly into ``eval_metrics.py`` / dashboards.

Usage::

    python shadow_runner.py --input ground_truth.jsonl \\
        --project /abs/path/to/project \\
        --backend http://localhost:8080 \\
        --out shadow_results.jsonl

Designed to be importable for tests: ``run_for_pr`` is pure-ish and accepts an
HTTP-client callable so tests can mock it.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Callable

from eval_metrics import jaccard, precision_at_pr, recall_at_pr, validator_pass_rate

LOG = logging.getLogger("ram.shadow_runner")

HttpCallable = Callable[[str, str, dict[str, Any] | None], dict[str, Any]]


@dataclass(frozen=True)
class PrRecord:
    number: int
    title: str
    body: str
    files: list[str]
    merged_at: str


@dataclass(frozen=True)
class ShadowResult:
    pr_number: int
    predicted: list[str]
    actual: list[str]
    recall: float
    precision: float
    jaccard: float
    validator_passed: bool
    duration_s: float


def _http(method: str, url: str, payload: dict[str, Any] | None) -> dict[str, Any]:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"} if data else {},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310
        body = resp.read().decode("utf-8")
        if not body:
            return {}
        parsed = json.loads(body)
        if isinstance(parsed, dict) and "data" in parsed:
            return parsed["data"] if isinstance(parsed["data"], dict) else {"data": parsed["data"]}
        return parsed if isinstance(parsed, dict) else {"data": parsed}


def _poll_until_done(http: HttpCallable, backend: str, sid: str,
                     poll_interval: float = 1.0, timeout_s: float = 600.0) -> dict[str, Any]:
    """Poll ``GET /api/ram/sessions/{sid}`` until status terminal."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        info = http("GET", f"{backend}/api/ram/sessions/{sid}", None)
        status = str(info.get("status") or "").lower()
        if status in {"completed", "aborted", "error", "failed"}:
            return info
        time.sleep(poll_interval)
    raise TimeoutError(f"session {sid} did not terminate in {timeout_s}s")


def _fetch_impact_files(http: HttpCallable, backend: str, sid: str) -> tuple[list[str], bool]:
    """Read the session's events and extract the impact-stage file set."""
    events_url = f"{backend}/api/ram/sessions/{sid}/events"
    try:
        payload = http("GET", events_url, None)
    except urllib.error.URLError:
        # Older backends may not expose a sync events endpoint; fall back to
        # rejoin info which the controller can be extended to return.
        info = http("GET", f"{backend}/api/ram/sessions/{sid}", None)
        impact = info.get("impact") or {}
        return list(impact.get("files") or []), bool(info.get("validatorPassed"))

    events = payload.get("events") if isinstance(payload, dict) else payload
    files: list[str] = []
    validator_passed = False
    for evt in events or []:
        if not isinstance(evt, dict):
            continue
        etype = str(evt.get("type") or "")
        payload_obj = evt.get("payload") or {}
        if etype in {"IMPACT_DONE", "TOOL_RESULT"}:
            for key in ("impacted", "files", "modified"):
                vals = payload_obj.get(key)
                if isinstance(vals, list):
                    files.extend(str(v) for v in vals if v)
        if etype == "CHECKPOINT":
            v = payload_obj.get("validation") or {}
            if isinstance(v, dict) and v.get("passed") is True:
                validator_passed = True
    # de-dup while preserving order
    seen: set[str] = set()
    uniq = []
    for f in files:
        if f not in seen:
            seen.add(f)
            uniq.append(f)
    return uniq, validator_passed


def run_for_pr(pr: PrRecord, backend: str, project_path: str,
               http: HttpCallable = _http) -> ShadowResult:
    """Execute a single shadow run. Returns the metrics for this PR."""
    start = time.time()
    payload = {"rawInput": pr.body, "projectPath": project_path, "userId": "shadow"}
    resp = http("POST", f"{backend}/api/ram/sessions", payload)
    sid = str(resp.get("sessionId") or "")
    if not sid:
        raise RuntimeError(f"backend did not return sessionId; resp={resp!r}")
    _poll_until_done(http, backend, sid)
    predicted, validator_passed = _fetch_impact_files(http, backend, sid)
    duration = time.time() - start
    return ShadowResult(
        pr_number=pr.number,
        predicted=predicted,
        actual=list(pr.files),
        recall=recall_at_pr(predicted, pr.files),
        precision=precision_at_pr(predicted, pr.files),
        jaccard=jaccard(predicted, pr.files),
        validator_passed=validator_passed,
        duration_s=round(duration, 3),
    )


def _iter_records(path: Path):
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            yield PrRecord(
                number=int(obj.get("number") or 0),
                title=str(obj.get("title") or ""),
                body=str(obj.get("body") or ""),
                files=[str(f) for f in (obj.get("files") or [])],
                merged_at=str(obj.get("merged_at") or ""),
            )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="RAM shadow-mode runner")
    parser.add_argument("--input", required=True, help="ground_truth.jsonl path")
    parser.add_argument("--project", required=True, help="target project absolute path")
    parser.add_argument("--backend", default="http://localhost:8080", help="backend base URL")
    parser.add_argument("--out", required=True, help="output JSONL path for results")
    parser.add_argument("--limit", type=int, default=0, help="cap number of PRs (0 = all)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv or sys.argv[1:])

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    input_path = Path(args.input)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    results: list[ShadowResult] = []
    with out_path.open("w", encoding="utf-8") as fh:
        for i, pr in enumerate(_iter_records(input_path)):
            if args.limit and i >= args.limit:
                break
            try:
                res = run_for_pr(pr, args.backend, args.project)
            except Exception as exc:  # noqa: BLE001
                LOG.warning("PR #%d failed: %s", pr.number, exc)
                continue
            fh.write(json.dumps(asdict(res), ensure_ascii=False) + "\n")
            results.append(res)
            LOG.info("PR #%d recall=%.2f precision=%.2f jaccard=%.2f",
                     pr.number, res.recall, res.precision, res.jaccard)

    if results:
        agg = {
            "count": len(results),
            "mean_recall": sum(r.recall for r in results) / len(results),
            "mean_precision": sum(r.precision for r in results) / len(results),
            "mean_jaccard": sum(r.jaccard for r in results) / len(results),
            "validator_pass_rate": validator_pass_rate([r.validator_passed for r in results]),
        }
        LOG.info("aggregate: %s", agg)
        agg_path = out_path.with_suffix(".summary.json")
        agg_path.write_text(json.dumps(agg, indent=2, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
