"""Evaluation metrics for the Requirement Analysis Master (RAM) shadow mode.

All metrics operate on the file-set produced by the orchestrator's Impact stage
(``predicted``) versus the actual file-set touched by a merged PR (``actual``):

* ``recall_at_pr``    -- |predicted ∩ actual| / |actual|
* ``precision_at_pr`` -- |predicted ∩ actual| / |predicted|
* ``jaccard``         -- |predicted ∩ actual| / |predicted ∪ actual|
* ``validator_pass_rate`` -- mean(validator_passed_flags)

All functions are pure and side-effect-free so they can be exercised quickly
under pytest. See ``tests/test_eval_metrics.py`` for usage examples.
"""
from __future__ import annotations

from typing import Iterable, Sequence


def _to_set(files: Iterable[str]) -> frozenset[str]:
    return frozenset(f for f in files if f)


def recall_at_pr(predicted: Iterable[str], actual: Iterable[str]) -> float:
    """Fraction of files the predictor caught from the PR's true change set.

    Returns 0.0 when ``actual`` is empty (nothing to recall).
    """
    p = _to_set(predicted)
    a = _to_set(actual)
    if not a:
        return 0.0
    return len(p & a) / len(a)


def precision_at_pr(predicted: Iterable[str], actual: Iterable[str]) -> float:
    """Fraction of predicted files that turned out to be touched by the PR.

    Returns 0.0 when ``predicted`` is empty (no signal).
    """
    p = _to_set(predicted)
    a = _to_set(actual)
    if not p:
        return 0.0
    return len(p & a) / len(p)


def jaccard(predicted: Iterable[str], actual: Iterable[str]) -> float:
    """Jaccard similarity between predicted and actual file sets.

    Returns 0.0 when both sets are empty (undefined → conservative).
    """
    p = _to_set(predicted)
    a = _to_set(actual)
    union = p | a
    if not union:
        return 0.0
    return len(p & a) / len(union)


def validator_pass_rate(flags: Sequence[bool]) -> float:
    """Mean of a list of validator booleans. Returns 0.0 for an empty input."""
    if not flags:
        return 0.0
    return sum(1 for f in flags if f) / len(flags)


__all__ = [
    "recall_at_pr",
    "precision_at_pr",
    "jaccard",
    "validator_pass_rate",
]
