"""Unit tests for eval_metrics — the RAM shadow-mode scoring formulas."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from eval_metrics import (  # noqa: E402
    jaccard,
    precision_at_pr,
    recall_at_pr,
    validator_pass_rate,
)


def test_recall_at_pr_basic():
    predicted = {"a.java", "b.java", "c.java"}
    actual = {"a.java", "b.java", "d.java"}
    assert recall_at_pr(predicted, actual) == 2 / 3


def test_recall_at_pr_empty_actual_is_zero():
    assert recall_at_pr({"a.java"}, set()) == 0.0


def test_recall_at_pr_perfect():
    assert recall_at_pr({"a", "b"}, {"a", "b"}) == 1.0


def test_precision_at_pr_basic():
    predicted = ["a", "b", "c", "x"]
    actual = ["a", "b", "z"]
    assert precision_at_pr(predicted, actual) == 2 / 4


def test_precision_at_pr_empty_predicted_is_zero():
    assert precision_at_pr([], ["a"]) == 0.0


def test_jaccard_basic():
    # intersection = {a,b} (2), union = {a,b,c,d} (4) -> 0.5
    assert jaccard({"a", "b", "c"}, {"a", "b", "d"}) == 0.5


def test_jaccard_both_empty_is_zero():
    assert jaccard([], []) == 0.0


def test_jaccard_disjoint_is_zero():
    assert jaccard({"a"}, {"b"}) == 0.0


def test_validator_pass_rate_basic():
    assert validator_pass_rate([True, True, False, False]) == 0.5


def test_validator_pass_rate_empty_is_zero():
    assert validator_pass_rate([]) == 0.0


def test_validator_pass_rate_all_true():
    assert validator_pass_rate([True, True, True]) == 1.0


def test_ignores_empty_strings():
    # _to_set drops falsy entries to keep noisy inputs from skewing metrics
    assert recall_at_pr(["a", ""], ["a"]) == 1.0
    assert precision_at_pr(["a", ""], ["a"]) == 1.0
